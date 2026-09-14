package ai.rever.boss.plugin.dynamic.editortab

/** A read-only document that presents the old and proposed code as one inline review hunk. */
internal data class AiInlineReviewDocument(
    val text: String,
    val removedLineStart: Int,
    val removedLineCount: Int,
    val addedLineStart: Int,
    val addedLineCount: Int,
) {
    val controlAnchorLine: Int
        get() = if (addedLineCount > 0) addedLineStart + addedLineCount - 1 else removedLineStart
}

/**
 * Builds a shadow editor document without changing the live buffer.
 *
 * The selected source lines remain in place as the red half of the hunk. A second copy of the
 * same line span, with only the captured selection replaced, follows as the green half. Expanding
 * to line boundaries keeps partial-line edits readable and preserves their surrounding code.
 */
internal fun buildAiInlineReviewDocument(
    content: String,
    startOffset: Int,
    endOffset: Int,
    replacement: String,
): AiInlineReviewDocument {
    require(startOffset in 0..content.length)
    require(endOffset in startOffset..content.length)

    val lineStart = content.lastIndexOf('\n', (startOffset - 1).coerceAtLeast(0))
        .let { if (it < 0 || startOffset == 0) 0 else it + 1 }
    // A range ending at column zero belongs to the preceding line for review purposes.
    val lastSelectedOffset = if (endOffset > startOffset && content[endOffset - 1] == '\n') endOffset - 1 else endOffset
    val nextNewline = content.indexOf('\n', lastSelectedOffset)
    val lineEnd = if (nextNewline < 0) content.length else nextNewline + 1

    val originalBlock = content.substring(lineStart, lineEnd)
    val replacementBlock =
        content.substring(lineStart, startOffset) + replacement + content.substring(endOffset, lineEnd)
    val separator = if (originalBlock.endsWith('\n') || originalBlock.isEmpty()) "" else "\n"
    val removedStart = content.take(lineStart).count { it == '\n' }
    val removedCount = displayLineCount(originalBlock)
    val addedStart = removedStart + removedCount

    return AiInlineReviewDocument(
        text = content.substring(0, lineStart) + originalBlock + separator + replacementBlock + content.substring(lineEnd),
        removedLineStart = removedStart,
        removedLineCount = removedCount,
        addedLineStart = addedStart,
        addedLineCount = displayLineCount(replacementBlock),
    )
}

private fun displayLineCount(text: String): Int =
    when {
        text.isEmpty() -> 0
        text.endsWith('\n') -> text.count { it == '\n' }
        else -> text.count { it == '\n' } + 1
    }
