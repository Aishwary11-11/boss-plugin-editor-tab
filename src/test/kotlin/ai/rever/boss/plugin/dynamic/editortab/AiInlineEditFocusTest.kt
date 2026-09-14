package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiInlineEditFocusTest {
    @Test
    fun `empty idle prompt dismisses after it loses acquired focus`() {
        assertTrue(shouldDismissEmptyInlineEdit(true, false, "", busy = false, reviewing = false))
    }

    @Test
    fun `initial unfocused composition does not dismiss`() {
        assertFalse(shouldDismissEmptyInlineEdit(false, false, "", busy = false, reviewing = false))
    }

    @Test
    fun `typed busy and review sessions survive focus changes`() {
        assertFalse(shouldDismissEmptyInlineEdit(true, false, "keep this", busy = false, reviewing = false))
        assertFalse(shouldDismissEmptyInlineEdit(true, false, "", busy = true, reviewing = false))
        assertFalse(shouldDismissEmptyInlineEdit(true, false, "", busy = false, reviewing = true))
    }
}
