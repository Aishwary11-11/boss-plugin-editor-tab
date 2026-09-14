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
}
