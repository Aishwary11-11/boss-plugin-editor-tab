package ai.rever.bosseditor.features

import ai.rever.bosseditor.core.EditorDocument
import ai.rever.bosseditor.core.OffsetRange
import ai.rever.bosseditor.fold.VisualLineMapper
import ai.rever.bosseditor.highlight.TokenProvider
import ai.rever.bosseditor.theme.EditorColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Compose canvas for rendering the minimap.
 *
 * @param document The editor document to display
 * @param tokenProvider Optional token provider for colored rendering
 * @param colors Editor colors to use (passed explicitly from parent to ensure consistency)
 * @param visualLineMapper Maps between document and visual lines (handles folding)
 * @param firstVisibleLine First visible line in the editor
 * @param visibleLineCount Number of visible lines in the editor
 * @param currentLine Current cursor line (0-based), -1 if none
 * @param selection Current selection range
 * @param searchResults Search result ranges
 * @param occurrences Mark occurrences ranges
 * @param diagnostics Diagnostic information
 * @param config Minimap configuration
 * @param onLineClicked Callback when a line is clicked (returns visual line)
 * @param onDragToLine Callback when dragging to a line (returns visual line)
 * @param modifier Modifier for the canvas
 */
@Composable
fun MinimapCanvas(
    document: EditorDocument,
    tokenProvider: TokenProvider?,
    colors: EditorColors,
    visualLineMapper: VisualLineMapper,
    firstVisibleLine: Int,
    visibleLineCount: Int,
    currentLine: Int = -1,
    selection: OffsetRange? = null,
    searchResults: List<OffsetRange> = emptyList(),
    occurrences: List<OffsetRange> = emptyList(),
    diagnostics: List<DiagnosticInfo> = emptyList(),
    config: MinimapConfig = MinimapConfig(),
    onLineClicked: (Int) -> Unit = {},
    onDragToLine: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Track hover state
    var isHovered by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // Create renderer with explicitly passed colors and visual line mapper
    val renderer = remember(document, tokenProvider, colors, visualLineMapper) {
        MinimapRenderer(document, tokenProvider, colors, visualLineMapper).also {
            it.config = config
        }
    }

    // Update config when it changes
    LaunchedEffect(config) {
        renderer.config = config
    }

    // Create state
    val state = remember(
        firstVisibleLine,
        visibleLineCount,
        currentLine,
        selection,
        searchResults,
        occurrences,
        diagnostics,
        isHovered,
        isDragging
    ) {
        MinimapState(
            firstVisibleLine = firstVisibleLine,
            visibleLineCount = visibleLineCount,
            currentLine = currentLine,
            selection = selection,
            searchResults = searchResults,
            occurrences = occurrences,
            diagnostics = diagnostics,
            isHovered = isHovered,
            isDragging = isDragging
        )
    }

    // Calculate width
    val minimapWidth = renderer.calculateOptimalWidth()

    Canvas(
        modifier = modifier
            .width(minimapWidth.dp)
            .fillMaxHeight()
            .background(colors.background)  // Use explicit editor background color
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val line = renderer.getLineFromClick(offset.y, size.height.toFloat())
                    onLineClicked(line)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                ) { change, _ ->
                    val line = renderer.getLineFromClick(change.position.y, size.height.toFloat())
                    onDragToLine(line)
                }
            }
    ) {
        drawContext.canvas.apply {
            renderer.render(
                canvas = this,
                x = 0f,
                y = 0f,
                width = size.width,
                height = size.height,
                state = state
            )
        }
    }
}

/**
 * Minimap canvas with auto-managed state from editor state.
 */
@Composable
fun MinimapCanvasWithState(
    document: EditorDocument,
    tokenProvider: TokenProvider?,
    colors: EditorColors,
    visualLineMapper: VisualLineMapper,
    editorState: MinimapEditorState,
    config: MinimapConfig = MinimapConfig(),
    onScrollToLine: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    MinimapCanvas(
        document = document,
        tokenProvider = tokenProvider,
        colors = colors,
        visualLineMapper = visualLineMapper,
        firstVisibleLine = editorState.firstVisibleLine,
        visibleLineCount = editorState.visibleLineCount,
        selection = editorState.selection,
        searchResults = editorState.searchResults,
        occurrences = editorState.occurrences,
        diagnostics = editorState.diagnostics,
        config = config,
        onLineClicked = { line ->
            // Center the clicked line
            onScrollToLine(line)
        },
        onDragToLine = { line ->
            // Scroll to line while dragging
            onScrollToLine(line)
        },
        modifier = modifier
    )
}

/**
 * State interface for the minimap to observe from the editor.
 */
interface MinimapEditorState {
    val firstVisibleLine: Int
    val visibleLineCount: Int
    val selection: OffsetRange?
    val searchResults: List<OffsetRange>
    val occurrences: List<OffsetRange>
    val diagnostics: List<DiagnosticInfo>
}

/**
 * Basic implementation of MinimapEditorState.
 */
class BasicMinimapEditorState : MinimapEditorState {
    override var firstVisibleLine: Int by mutableStateOf(0)
    override var visibleLineCount: Int by mutableStateOf(30)
    override var selection: OffsetRange? by mutableStateOf(null)
    override var searchResults: List<OffsetRange> by mutableStateOf(emptyList())
    override var occurrences: List<OffsetRange> by mutableStateOf(emptyList())
    override var diagnostics: List<DiagnosticInfo> by mutableStateOf(emptyList())

    /**
     * Updates the viewport information.
     */
    fun updateViewport(firstLine: Int, lineCount: Int) {
        firstVisibleLine = firstLine
        visibleLineCount = lineCount
    }

    /**
     * Updates the selection.
     */
    fun updateSelection(range: OffsetRange?) {
        selection = range
    }

    /**
     * Updates search results.
     */
    fun updateSearchResults(results: List<OffsetRange>) {
        searchResults = results
    }

    /**
     * Updates mark occurrences.
     */
    fun updateOccurrences(ranges: List<OffsetRange>) {
        occurrences = ranges
    }

    /**
     * Updates diagnostics.
     */
    fun updateDiagnostics(diags: List<DiagnosticInfo>) {
        diagnostics = diags
    }
}

/**
 * Remember a BasicMinimapEditorState.
 */
@Composable
fun rememberMinimapEditorState(): BasicMinimapEditorState {
    return remember { BasicMinimapEditorState() }
}
