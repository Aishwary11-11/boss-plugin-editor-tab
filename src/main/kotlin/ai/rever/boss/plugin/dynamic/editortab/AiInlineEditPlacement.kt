package ai.rever.boss.plugin.dynamic.editortab

import kotlin.math.roundToInt

/** Pixel position for the inline AI card inside the editor surface. */
internal data class AiInlineEditPlacement(
    val x: Int,
    val y: Int,
)

/**
 * Places the inline AI card beside a caret while keeping it inside the visible editor.
 *
 * The card normally starts at the caret's horizontal position and below its line. Near the
 * bottom edge it opens above the line instead. The final clamp also covers carets that are
 * horizontally scrolled out of view and panes that are too short to fit either preferred
 * position.
 */
internal fun placeAiInlineEdit(
    containerWidth: Int,
    containerHeight: Int,
    popupWidth: Int,
    popupHeight: Int,
    anchorX: Float,
    anchorY: Float,
    lineHeight: Float,
    margin: Int,
    gap: Int,
): AiInlineEditPlacement {
    val safeMarginX = margin.coerceAtLeast(0).coerceAtMost((containerWidth - popupWidth).coerceAtLeast(0) / 2)
    val safeMarginY = margin.coerceAtLeast(0).coerceAtMost((containerHeight - popupHeight).coerceAtLeast(0) / 2)
    val maxX = (containerWidth - popupWidth - safeMarginX).coerceAtLeast(safeMarginX)
    val maxY = (containerHeight - popupHeight - safeMarginY).coerceAtLeast(safeMarginY)

    val belowY = anchorY + lineHeight.coerceAtLeast(0f) + gap.coerceAtLeast(0)
    val aboveY = anchorY - popupHeight - gap.coerceAtLeast(0)
    val opensAbove = belowY > maxY && aboveY >= safeMarginY
    val preferredY = if (opensAbove) aboveY else belowY

    return AiInlineEditPlacement(
        x = anchorX.roundToInt().coerceIn(safeMarginX, maxX),
        y = preferredY.roundToInt().coerceIn(safeMarginY, maxY),
    )
}
