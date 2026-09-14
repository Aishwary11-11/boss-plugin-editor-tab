package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals

class AiInlineEditPlacementTest {
    @Test
    fun `opens below the caret when there is room`() {
        val result = placeAiInlineEdit(
            containerWidth = 1000,
            containerHeight = 700,
            popupWidth = 420,
            popupHeight = 120,
            anchorX = 180f,
            anchorY = 200f,
            lineHeight = 20f,
            margin = 8,
            gap = 6,
        )

        assertEquals(180, result.x)
        assertEquals(226, result.y)
    }

    @Test
    fun `opens above the caret near the bottom edge`() {
        val result = placeAiInlineEdit(
            containerWidth = 1000,
            containerHeight = 500,
            popupWidth = 420,
            popupHeight = 140,
            anchorX = 240f,
            anchorY = 450f,
            lineHeight = 20f,
            margin = 8,
            gap = 6,
        )

        assertEquals(304, result.y)
    }

    @Test
    fun `clamps a horizontally scrolled caret at both edges`() {
        val left = placeAiInlineEdit(600, 500, 300, 100, -80f, 100f, 20f, 8, 6)
        val right = placeAiInlineEdit(600, 500, 300, 100, 590f, 100f, 20f, 8, 6)

        assertEquals(8, left.x)
        assertEquals(292, right.x)
    }

    @Test
    fun `clamps vertically when neither preferred side fits`() {
        val result = placeAiInlineEdit(
            containerWidth = 500,
            containerHeight = 200,
            popupWidth = 300,
            popupHeight = 150,
            anchorX = 100f,
            anchorY = 70f,
            lineHeight = 20f,
            margin = 8,
            gap = 6,
        )

        assertEquals(42, result.y)
    }

    @Test
    fun `handles a popup larger than an unmeasured or tiny surface`() {
        val result = placeAiInlineEdit(100, 60, 300, 120, 50f, 30f, 20f, 8, 6)

        assertEquals(0, result.x)
        assertEquals(0, result.y)
    }
}
