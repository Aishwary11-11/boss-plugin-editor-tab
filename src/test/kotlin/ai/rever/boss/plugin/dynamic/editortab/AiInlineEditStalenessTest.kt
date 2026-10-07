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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `caret at the end of a line opens the compose over the whole line`() {
        val state = EditorState("val a = 1\n", null)
        val service = AiInlineEditService(BareContext(), CoroutineScope(Job()))
        service.bind(null, state)
        state.moveCaretToOffset("val a = 1".length) // caret at EOL

        assertTrue(service.start(state, "kotlin"), "EOL must still open the compose")
        val s = service.session.value
        assertEquals(0, s?.startLine)
        assertEquals(0, s?.startCol, "target expands to the line start")
        assertEquals(0, s?.endLine)
        assertEquals("val a = 1".length, s?.endCol)
        assertEquals("val a = 1", s?.selectionText)
    }

    @Test
    fun `caret on an empty line opens the compose ready to generate`() {
        val state = EditorState("first()\n\nlast()\n", null)
        val service = AiInlineEditService(BareContext(), CoroutineScope(Job()))
        service.bind(null, state)
        state.moveCaretToOffset("first()\n".length) // caret on the empty middle line

        assertTrue(service.start(state, "kotlin"), "an empty line must still open the compose")
        val s = service.session.value
        assertEquals(1, s?.startLine)
        assertEquals(1, s?.endLine)
        assertEquals("", s?.selectionText, "empty target: the AI generates at the caret")
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

    @Test
    fun `caret at the end of a whitespace-only line generates at the caret, keeping the indentation`() {
        val state = EditorState("first()\n    \nlast()\n", null)
        val service = AiInlineEditService(BareContext(), CoroutineScope(Job()))
        service.bind(null, state)
        state.moveCaretToOffset("first()\n    ".length) // caret at EOL of the "    " line (column 4)

        assertTrue(service.start(state, "kotlin"), "a blank line must still open the compose")
        val s = service.session.value
        assertEquals(1, s?.startLine)
        assertEquals(1, s?.endLine)
        assertEquals(4, s?.startCol, "the target stays at the caret, not expanded over the indentation")
        assertEquals(4, s?.endCol)
        assertEquals("", s?.selectionText, "a blank line is an empty target: generate mode")
    }

    @Test
    fun `buildRequest rewrites a non-blank selection`() {
        val req = AiInlineEditService.buildRequest("add a docstring", "x = 1", "python")
        assertTrue(req.system.contains("rewrite"), "a non-blank selection is a rewrite, not a generation")
        val user = req.messages.single().text
        assertTrue(user.contains("<selected_code>\nx = 1</selected_code>"))
        assertFalse(user.contains("<context>"))
    }

    @Test
    fun `buildRequest switches to generate mode for a blank selection`() {
        val context = "<context>\na\nb\n</context>\nThe caret is on line 2 (1-based) of this block, column 1."
        val req = AiInlineEditService.buildRequest("generate a hello", "  ", "python", context)
        assertTrue(req.system.contains("generate"), "a blank selection is a generation, not a rewrite")
        val user = req.messages.single().text
        assertFalse(user.contains("<selected_code>"))
        assertTrue(user.contains(context))
        assertTrue(user.contains("Generate the code to insert at the caret."))
    }

    @Test
    fun `caretContext returns a few lines around the line and states the caret`() {
        val state = EditorState("l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\n", null)
        val ctx = AiInlineEditService.caretContext(state.document, 4, 0)
        assertNotNull(ctx)
        assertTrue(ctx!!.startsWith("<context>\nl2\nl3\nl4\nl5\nl6\nl7\nl8\n</context>"), ctx)
        assertTrue(ctx.contains("line 4 (1-based) of this block, column 1"), ctx)
    }

    @Test
    fun `caretContext clamps at the document edges`() {
        val state = EditorState("a\nb\n", null)
        val ctx = AiInlineEditService.caretContext(state.document, 0, 0)
        assertEquals("<context>\na\nb\n</context>\nThe caret is on line 1 (1-based) of this block, column 1.", ctx)
    }

    @Test
    fun `caretContext on the final blank line keeps the stated line in the block`() {
        // "a\nb\n" is 3 lines: a, b, and the empty one after the final
        // newline. Cmd+K at the bottom of a file puts the caret on that
        // blank line - the trailing-blank trim must not lift the window
        // above the caret, or the model would be told to insert on a line
        // that is not in the block it was given.
        val state = EditorState("a\nb\n", null)
        val ctx = AiInlineEditService.caretContext(state.document, 2, 0)
        assertEquals(
            "<context>\na\nb\n\n</context>\nThe caret is on line 3 (1-based) of this block, column 1.",
            ctx,
        )
    }

    @Test
    fun `caretContext is null for an empty document`() {
        val state = EditorState("", null)
        assertNull(AiInlineEditService.caretContext(state.document, 0, 0))
    }
}
