package ai.rever.bosseditor.features

import ai.rever.bosseditor.core.EditorDocument
import ai.rever.bosseditor.fold.VisualLineMapper
import ai.rever.bosseditor.highlight.Token
import ai.rever.bosseditor.highlight.TokenProvider
import ai.rever.bosseditor.highlight.TokenType
import ai.rever.bosseditor.highlight.LexerState
import ai.rever.bosseditor.theme.EditorColors
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle

/**
 * Renders the minimap (document overview) for the editor.
 *
 * The minimap provides a scaled-down view of the entire document,
 * showing syntax highlighting colors and the current viewport position.
 * Uses VisualLineMapper to respect code folding state.
 */
class MinimapRenderer(
    private val document: EditorDocument,
    private val tokenProvider: TokenProvider?,
    private val colors: EditorColors,
    private val visualLineMapper: VisualLineMapper
) {
    private val minimap = Minimap(document)

    /**
     * Current minimap configuration.
     */
    var config: MinimapConfig
        get() = minimap.config
        set(value) {
            minimap.config = value
        }

    /**
     * Renders the minimap to the canvas.
     *
     * @param canvas The Compose canvas to render to
     * @param x The X position of the minimap
     * @param y The Y position of the minimap
     * @param width The width of the minimap
     * @param height The height of the minimap
     * @param state The current minimap state
     */
    fun render(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        state: MinimapState
    ) {
        // Always draw background first, even if minimap is disabled or empty
        drawBackground(canvas, x, y, width, height)

        // Use visible line count (respects folding)
        val visibleLineCount = visualLineMapper.visibleLineCount
        if (!config.enabled || visibleLineCount == 0) return

        // Draw left border for visual separation
        drawLeftBorder(canvas, x, y, height)

        // Calculate line height based on visible line count and available height
        val lineHeight = calculateLineHeight(height, visibleLineCount)

        // Draw current line indicator (behind content) - convert document line to visual line
        if (state.currentLine >= 0) {
            val visualLine = visualLineMapper.documentToVisual(state.currentLine)
            if (visualLine >= 0) {
                drawCurrentLineIndicator(canvas, x, y, width, lineHeight, visualLine)
            }
        }

        // Draw document content (fold-aware)
        if (tokenProvider != null && config.renderCharacters) {
            drawWithSyntaxHighlighting(canvas, x, y, width, lineHeight, state)
        } else {
            drawSimplified(canvas, x, y, width, lineHeight, state)
        }

        // Draw viewport indicator
        if (config.showSlider) {
            drawViewportIndicator(canvas, x, y, width, height, state)
        }

        // Draw highlights (search, selection, occurrences)
        drawHighlights(canvas, x, y, width, height, state)

        // Draw diagnostic markers
        drawDiagnosticMarkers(canvas, x, width, height, state)
    }

    private fun drawLeftBorder(canvas: Canvas, x: Float, y: Float, height: Float) {
        val paint = Paint().apply {
            // Use gutter border color for consistency with editor
            color = colors.gutterBorder
            style = PaintingStyle.Fill
        }
        canvas.drawRect(rectXYWH(x, y, 1f, height), paint)
    }

    private fun drawCurrentLineIndicator(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        lineHeight: Float,
        currentLine: Int
    ) {
        val lineY = y + currentLine * lineHeight
        val paint = Paint().apply {
            // Use editor's current line highlight color
            color = colors.currentLineHighlight
            style = PaintingStyle.Fill
        }
        canvas.drawRect(
            rectXYWH(x + 1f, lineY, width - 1f, maxOf(lineHeight, 2f)),
            paint
        )
    }

    private fun drawBackground(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        val paint = Paint().apply {
            // Use the main editor background color for seamless appearance
            color = colors.background
            style = PaintingStyle.Fill
        }
        canvas.drawRect(rectXYWH(x, y, width, height), paint)
    }

    private fun calculateLineHeight(availableHeight: Float, lineCount: Int): Float {
        val naturalHeight = lineCount * config.lineHeight
        return if (naturalHeight > availableHeight) {
            availableHeight / lineCount
        } else {
            config.lineHeight
        }
    }

    private fun drawSimplified(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        lineHeight: Float,
        state: MinimapState
    ) {
        val paint = Paint().apply {
            style = PaintingStyle.Fill
            color = colors.text
        }

        val maxChars = (width / config.charWidth).toInt()
        val visibleLineCount = visualLineMapper.visibleLineCount
        val documentLineCount = document.lineCount

        // Iterate over visual lines (respects folding)
        for (visualLine in 0 until visibleLineCount) {
            val documentLine = visualLineMapper.visualToDocument(visualLine)
            if (documentLine < 0 || documentLine >= documentLineCount) continue

            val lineStart = document.getLineStartOffset(documentLine)
            val lineEnd = document.getLineEndOffset(documentLine)
            val lineLength = lineEnd - lineStart

            // Skip empty lines
            if (lineLength <= 0) continue

            val lineY = y + visualLine * lineHeight
            val displayLength = minOf(lineLength, maxChars)
            val lineWidth = displayLength * config.charWidth

            canvas.drawRect(
                rectXYWH(x + 2f, lineY, lineWidth, maxOf(lineHeight - 1f, 1f)),
                paint
            )
        }
    }

    private fun drawWithSyntaxHighlighting(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        lineHeight: Float,
        state: MinimapState
    ) {
        val paint = Paint().apply {
            style = PaintingStyle.Fill
        }

        val maxChars = (width / config.charWidth).toInt()
        val visibleLineCount = visualLineMapper.visibleLineCount
        val documentLineCount = document.lineCount

        // Track lexer state across document lines (must process in document order for proper state)
        var lexerState = LexerState.NORMAL
        var currentDocLine = 0

        // Iterate over visual lines (respects folding)
        for (visualLine in 0 until visibleLineCount) {
            val documentLine = visualLineMapper.visualToDocument(visualLine)
            if (documentLine < 0 || documentLine >= documentLineCount) continue

            // Process any skipped document lines to maintain lexer state (for folded regions)
            while (currentDocLine < documentLine) {
                if (tokenProvider != null) {
                    val lineStart = document.getLineStartOffset(currentDocLine)
                    val lineEnd = document.getLineEndOffset(currentDocLine)
                    val lineText = document.getText(lineStart, lineEnd).trimEnd('\n', '\r')
                    val result = tokenProvider.tokenizeLine(lineText, currentDocLine, lexerState)
                    lexerState = result.endState
                }
                currentDocLine++
            }

            // Tokenize current visible line
            val tokens = if (tokenProvider != null) {
                val lineStart = document.getLineStartOffset(documentLine)
                val lineEnd = document.getLineEndOffset(documentLine)
                val lineText = document.getText(lineStart, lineEnd).trimEnd('\n', '\r')
                val result = tokenProvider.tokenizeLine(lineText, documentLine, lexerState)
                lexerState = result.endState
                currentDocLine = documentLine + 1
                result.tokens
            } else {
                emptyList()
            }

            val lineY = y + visualLine * lineHeight

            // Draw tokens for this visual line
            for (token in tokens) {
                if (token.startOffset >= maxChars) continue

                val startX = x + 2f + token.startOffset * config.charWidth
                val endColumn = minOf(token.endOffset, maxChars)
                val segmentWidth = (endColumn - token.startOffset) * config.charWidth

                if (segmentWidth <= 0) continue

                paint.color = getTokenColor(token.type)
                canvas.drawRect(
                    rectXYWH(startX, lineY, segmentWidth, maxOf(lineHeight - 1f, 1f)),
                    paint
                )
            }
        }
    }

    private fun drawViewportIndicator(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        state: MinimapState
    ) {
        // Use visible line count (respects folding) for consistent rendering
        val visibleLineCount = visualLineMapper.visibleLineCount
        val lineHeight = calculateLineHeight(height, visibleLineCount)

        // Calculate viewport bounds using consistent line height
        val viewportY = state.firstVisibleLine * lineHeight
        val viewportHeight = state.visibleLineCount * lineHeight

        val bounds = ViewportBounds(
            y = viewportY.coerceIn(0f, height),
            height = viewportHeight.coerceAtMost(height - viewportY)
        )

        // Use brighter color when hovered or dragging
        val viewportColor = if (state.isHovered || state.isDragging) {
            colors.minimapSliderHover
        } else {
            colors.minimapViewport
        }

        // Draw viewport background
        val bgPaint = Paint().apply {
            color = viewportColor
            style = PaintingStyle.Fill
        }
        canvas.drawRect(
            rectXYWH(x + 1f, y + bounds.y, width - 1f, bounds.height),
            bgPaint
        )

        // Draw viewport border (slightly brighter when hovered)
        val borderColor = if (state.isHovered || state.isDragging) {
            colors.minimapViewportBorder.copy(alpha = 1f)
        } else {
            colors.minimapViewportBorder
        }
        val borderPaint = Paint().apply {
            color = borderColor
            style = PaintingStyle.Stroke
            strokeWidth = 1f
        }
        canvas.drawRect(
            rectXYWH(x + 1f, y + bounds.y, width - 1f, bounds.height),
            borderPaint
        )
    }

    private fun drawHighlights(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        state: MinimapState
    ) {
        val paint = Paint().apply {
            style = PaintingStyle.Fill
        }

        // Use visible line count for consistent line height
        val visibleLineCount = visualLineMapper.visibleLineCount
        if (visibleLineCount == 0) return
        val lineHeight = calculateLineHeight(height, visibleLineCount)

        // Draw search highlights (only for visible lines)
        if (config.showSearchHighlights && state.searchResults.isNotEmpty()) {
            paint.color = colors.minimapSearchHighlight
            for (result in state.searchResults) {
                val position = document.offsetToPosition(result.start)
                val visualLine = visualLineMapper.documentToVisual(position.line)
                if (visualLine < 0) continue  // Skip if inside folded region
                val highlightY = visualLine * lineHeight
                canvas.drawRect(
                    rectXYWH(x + width - 4f, y + highlightY, 3f, maxOf(lineHeight, 2f)),
                    paint
                )
            }
        }

        // Draw selection highlights (only for visible lines)
        if (config.showSelection && state.selection != null) {
            paint.color = colors.minimapSelection
            val startPos = document.offsetToPosition(state.selection.start)
            val endPos = document.offsetToPosition(state.selection.end)
            for (docLine in startPos.line..endPos.line) {
                val visualLine = visualLineMapper.documentToVisual(docLine)
                if (visualLine < 0) continue  // Skip if inside folded region
                val highlightY = visualLine * lineHeight
                canvas.drawRect(
                    rectXYWH(x, y + highlightY, width, maxOf(lineHeight, 2f)),
                    paint
                )
            }
        }

        // Draw occurrence highlights (only for visible lines)
        if (state.occurrences.isNotEmpty()) {
            paint.color = colors.minimapOccurrence
            for (occurrence in state.occurrences) {
                val position = document.offsetToPosition(occurrence.start)
                val visualLine = visualLineMapper.documentToVisual(position.line)
                if (visualLine < 0) continue  // Skip if inside folded region
                val highlightY = visualLine * lineHeight
                canvas.drawRect(
                    rectXYWH(x + width - 4f, y + highlightY, 3f, maxOf(lineHeight, 2f)),
                    paint
                )
            }
        }
    }

    private fun drawDiagnosticMarkers(
        canvas: Canvas,
        x: Float,
        width: Float,
        height: Float,
        state: MinimapState
    ) {
        if (state.diagnostics.isEmpty()) return

        // Use visible line count for consistent line height
        val visibleLineCount = visualLineMapper.visibleLineCount
        if (visibleLineCount == 0) return
        val lineHeight = calculateLineHeight(height, visibleLineCount)

        val paint = Paint().apply {
            style = PaintingStyle.Fill
        }

        for (diagnostic in state.diagnostics) {
            val position = document.offsetToPosition(diagnostic.range.start)
            val visualLine = visualLineMapper.documentToVisual(position.line)
            if (visualLine < 0) continue  // Skip if inside folded region
            val markerY = visualLine * lineHeight

            paint.color = when (diagnostic.severity) {
                DiagnosticSeverity.ERROR -> colors.minimapError
                DiagnosticSeverity.WARNING -> colors.minimapWarning
                DiagnosticSeverity.INFO -> colors.minimapInfo
                DiagnosticSeverity.HINT -> colors.minimapHint
            }
            canvas.drawRect(
                rectXYWH(x + width - 4f, markerY, 3f, 2f),
                paint
            )
        }
    }

    private fun getTokenColor(tokenType: TokenType): androidx.compose.ui.graphics.Color {
        // Use the theme's minimap token color (slightly dimmed for better visual balance)
        return colors.getMinimapTokenColor(tokenType)
    }

    /**
     * Handles click on the minimap.
     *
     * @param clickY The Y coordinate of the click relative to minimap top
     * @param minimapHeight The total height of the minimap
     * @return The visual line number to scroll to
     */
    fun getLineFromClick(clickY: Float, minimapHeight: Float): Int {
        val visibleLineCount = visualLineMapper.visibleLineCount
        if (visibleLineCount == 0) return 0

        val lineHeight = calculateLineHeight(minimapHeight, visibleLineCount)
        val visualLine = (clickY / lineHeight).toInt()
        return visualLine.coerceIn(0, visibleLineCount - 1)
    }

    /**
     * Calculates the optimal width for the minimap based on visible lines.
     */
    fun calculateOptimalWidth(): Float {
        val visibleLineCount = visualLineMapper.visibleLineCount
        if (visibleLineCount == 0) return config.minWidth

        var maxLineLength = 0
        val linesToCheck = minOf(visibleLineCount, 1000)
        val documentLineCount = document.lineCount

        for (visualLine in 0 until linesToCheck) {
            val documentLine = visualLineMapper.visualToDocument(visualLine)
            if (documentLine < 0 || documentLine >= documentLineCount) continue

            val lineStart = document.getLineStartOffset(documentLine)
            val lineEnd = document.getLineEndOffset(documentLine)
            maxLineLength = maxOf(maxLineLength, lineEnd - lineStart)
        }

        val calculatedWidth = maxLineLength * config.charWidth
        return calculatedWidth.coerceIn(config.minWidth, config.maxWidth)
    }
}

/**
 * State for minimap rendering.
 */
data class MinimapState(
    /**
     * First visible line in the editor viewport.
     */
    val firstVisibleLine: Int = 0,

    /**
     * Number of visible lines in the editor viewport.
     */
    val visibleLineCount: Int = 30,

    /**
     * Current cursor line (0-based). -1 if no cursor.
     */
    val currentLine: Int = -1,

    /**
     * Current selection range (if any).
     */
    val selection: ai.rever.bosseditor.core.OffsetRange? = null,

    /**
     * Search result ranges.
     */
    val searchResults: List<ai.rever.bosseditor.core.OffsetRange> = emptyList(),

    /**
     * Mark occurrences ranges.
     */
    val occurrences: List<ai.rever.bosseditor.core.OffsetRange> = emptyList(),

    /**
     * Diagnostic information (errors, warnings).
     */
    val diagnostics: List<DiagnosticInfo> = emptyList(),

    /**
     * Whether the minimap is being hovered.
     */
    val isHovered: Boolean = false,

    /**
     * Whether the viewport indicator is being dragged.
     */
    val isDragging: Boolean = false
)

// Preserve the renderer's position/size coordinates using Compose geometry.
private fun rectXYWH(x: Float, y: Float, width: Float, height: Float): Rect =
    Rect(Offset(x, y), Size(width, height))
