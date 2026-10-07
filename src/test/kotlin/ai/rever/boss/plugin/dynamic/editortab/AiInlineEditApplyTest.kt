package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorPosition
import ai.rever.bosseditor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiInlineEditApplyTest {
    @Test
    fun `accepted replacement is exactly one undo step`() {
        val state = EditorState("val answer = oldValue\n", null)
        val start = EditorPosition(0, 13)
        val end = EditorPosition(0, 21)

        applyAcceptedAiInlineEdit(state, start, end, "compute()")

        assertEquals("val answer = compute()\n", state.document.getText())
        assertTrue(state.undo())
        assertEquals("val answer = oldValue\n", state.document.getText())
    }

    @Test
    fun `a generated insertion lands at the captured caret, not the live one`() {
        // The degenerate (generate-mode) range: start == end. An empty
        // selection inserts at the LIVE caret, so if the pointer wandered
        // while the preview was open, the code must still land where the
        // session was captured.
        val state = EditorState("first()\n\nlast()\n", null)
        state.moveCaretToOffset(0) // caret moved away after the session was captured
        applyAcceptedAiInlineEdit(state, EditorPosition(1, 0), EditorPosition(1, 0), "middle()")
        assertEquals("first()\nmiddle()\nlast()\n", state.document.getText())
    }
}
