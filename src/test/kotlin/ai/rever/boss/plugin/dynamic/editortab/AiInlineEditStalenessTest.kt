package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.bosseditor.core.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Inline AI edit must never apply a rewrite at offsets the document has moved past.
 *
 * The guard read only the shared buffer's version, so a viewport with NO
 * shared buffer - an untitled document, or any viewport holding a private
 * EditorState - had no check at all: accepting after typing replaced the
 * pre-typing range. [AiInlineEditService.start] always captured a version
 * (falling back to the document's own), so only the two guards were wrong.
 */
class AiInlineEditStalenessTest {

    /** The three members PluginContext actually requires; nothing here calls them. */
    private class BareContext : PluginContext {
        override val panelRegistry: PanelRegistry get() = error("not used in this test")
        override val tabRegistry: TabRegistry get() = error("not used in this test")
        override val pluginScope: CoroutineScope get() = error("not used in this test")
    }

    private fun serviceOver(state: EditorState): AiInlineEditService =
        AiInlineEditService(BareContext(), CoroutineScope(Job())).also {
            // bind(null, ...) is the untitled / private-EditorState viewport:
            // there is no shared buffer to take a version from.
            it.bind(null, state)
            assertTrue(it.start(state, "kotlin"), "a session must open")
        }

    @Test
    fun `a buffer-less session goes stale when the document moves`() {
        val state = EditorState("val a = 1\n", null)
        val service = serviceOver(state)

        state.document.replace(0, 0, "// typed while reviewing\n")

        assertTrue(service.isStale())
        assertFalse(service.applyAccepted(), "an apply at pre-typing offsets must be refused")
    }

    @Test
    fun `a buffer-less session applies while the document has not moved`() {
        val state = EditorState("val a = 1\n", null)
        val service = serviceOver(state)

        assertFalse(service.isStale())
        assertTrue(service.applyAccepted())
    }

    @Test
    fun `rejecting a review candidate leaves the live document and undo history untouched`() {
        val state = EditorState("val a = 1\n", null)
        val service = serviceOver(state)
        val undoCount = state.undoManager.undoCount

        service.cancel()

        assertEquals("val a = 1\n", state.document.getText())
        assertEquals(undoCount, state.undoManager.undoCount)
    }

    @Test
    fun `a session over a shared buffer still tracks the buffer version`() {
        val path = "/tmp/et-inline-stale-test/Foo.kt"
        val buffer = EditorBufferRegistry.acquire(path, "val a = 1\n", "kotlin")
        try {
            val service = AiInlineEditService(BareContext(), CoroutineScope(Job()))
            service.bind(buffer, buffer.editorState)
            assertTrue(service.start(buffer.editorState, "kotlin"))

            assertFalse(service.isStale())
            buffer.editorState.document.replace(0, 0, "x")
            assertTrue(service.isStale())
            assertFalse(service.applyAccepted())
        } finally {
            EditorBufferRegistry.release(path)
        }
    }

    @Test
    fun `pressing the shortcut again preserves the active session and captured offsets`() {
        val state = EditorState("first()\nsecond()\n", null)
        val scope = CoroutineScope(Job())
        val service = AiInlineEditService(BareContext(), scope)
        service.bind(null, state)
        try {
            state.moveCaretToOffset(0)
            assertTrue(service.start(state, "kotlin"))
            service.setPrompt("rewrite first")
            val original = service.session.value

            state.moveCaretToOffset("first()\n".length)
            assertTrue(service.start(state, "kotlin"))

            assertEquals(original, service.session.value)
            assertEquals("rewrite first", service.session.value?.prompt)
            assertEquals(0, service.session.value?.startLine)
        } finally {
            scope.cancel()
        }
    }
}
