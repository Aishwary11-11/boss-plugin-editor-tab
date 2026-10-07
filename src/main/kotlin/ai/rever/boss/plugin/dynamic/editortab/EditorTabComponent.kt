package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.ui.ContextMenuItemData
import ai.rever.bosseditor.compose.BossEditor
import ai.rever.bosseditor.compose.EditorHover
import ai.rever.bosseditor.compose.NavigationResolveResult
import ai.rever.bosseditor.config.BossDirectories
import ai.rever.bosseditor.features.UsagesPopup
import ai.rever.bosseditor.features.UsagesPopupState
import ai.rever.bosseditor.features.NavigationFeedbackPopup
import ai.rever.bosseditor.features.NavigationFeedbackState
import ai.rever.bosseditor.features.NavigationFailureReason
import ai.rever.bosseditor.features.SearchManager
import ai.rever.bosseditor.features.SearchOptions
import ai.rever.bosseditor.features.EditorLineDecoration
import ai.rever.bosseditor.features.EditorInlineSuggestion
import ai.rever.bosseditor.ui.SearchBar
import ai.rever.bosseditor.ui.GoToLineDialog
import ai.rever.bosseditor.largefile.LargeFileDocument
import ai.rever.bosseditor.largefile.LargeFileLimitationsDialog
import ai.rever.bosseditor.lsp.config.LspSettingsManager
import ai.rever.bosseditor.psi.ReferenceLocation
import ai.rever.bosseditor.psi.DefinitionInfo
import ai.rever.bosseditor.refactoring.RefactorContext
import ai.rever.bosseditor.refactoring.RefactorResult
import ai.rever.bosseditor.refactoring.SymbolKind
import ai.rever.bosseditor.refactoring.WorkspaceEditApplier
import ai.rever.bosseditor.refactoring.psi.ExtractVariableRefactoring
import ai.rever.bosseditor.refactoring.psi.ExtractMethodRefactoring
import ai.rever.bosseditor.refactoring.psi.InlineRefactoring
import ai.rever.bosseditor.refactoring.ExtractVariableParams
import ai.rever.bosseditor.refactoring.ExtractMethodParams
import ai.rever.bosseditor.ui.RenameDialog
import ai.rever.bosseditor.ui.ExtractVariableDialog
import ai.rever.bosseditor.ui.ExtractMethodDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ai.rever.bosseditor.core.EditorPosition
import ai.rever.bosseditor.core.EditorRange
import ai.rever.bosseditor.core.EditorState
import ai.rever.bosseditor.core.ScrollOffset
import ai.rever.bosseditor.core.VisibleViewport
import ai.rever.bosseditor.highlight.Token
import ai.rever.bosseditor.highlight.TokenCache
import ai.rever.bosseditor.highlight.TokenType
import ai.rever.bosseditor.highlight.lexers.BaseLexer
import ai.rever.bosseditor.psi.SemanticCache
import ai.rever.bosseditor.psi.SemanticType
import ai.rever.bosseditor.highlight.lexers.KotlinLexer
import ai.rever.bosseditor.highlight.lexers.JavaLexer
import ai.rever.bosseditor.highlight.lexers.JavaScriptLexer
import ai.rever.bosseditor.highlight.lexers.TypeScriptLexer
import ai.rever.bosseditor.highlight.lexers.PythonLexer
import ai.rever.bosseditor.highlight.lexers.GoLexer
import ai.rever.bosseditor.highlight.lexers.RustLexer
import ai.rever.bosseditor.highlight.lexers.SwiftLexer
import ai.rever.bosseditor.highlight.lexers.JsonLexer
import ai.rever.bosseditor.highlight.lexers.YamlLexer
import ai.rever.bosseditor.highlight.lexers.XmlLexer
import ai.rever.bosseditor.highlight.lexers.HtmlLexer
import ai.rever.bosseditor.highlight.lexers.CssLexer
import ai.rever.bosseditor.highlight.lexers.SqlLexer
import ai.rever.bosseditor.highlight.lexers.ShellLexer
import ai.rever.bosseditor.highlight.lexers.MarkdownLexer
import ai.rever.bosseditor.highlight.lexers.TomlLexer
import ai.rever.bosseditor.highlight.lexers.CLexer
import ai.rever.bosseditor.highlight.lexers.CSharpLexer
import ai.rever.bosseditor.highlight.lexers.GroovyLexer
import ai.rever.bosseditor.highlight.lexers.ScalaLexer
import ai.rever.bosseditor.highlight.lexers.RubyLexer
import ai.rever.bosseditor.highlight.lexers.PHPLexer
import ai.rever.bosseditor.highlight.lexers.PerlLexer
import ai.rever.bosseditor.highlight.lexers.LuaLexer
import ai.rever.bosseditor.rendering.EditorToken
import ai.rever.bosseditor.theme.EditorTheme
import ai.rever.bosseditor.theme.LocalEditorTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import compose.icons.FeatherIcons
import compose.icons.feathericons.Play
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.reflect.full.memberProperties

/**
 * Code editor tab component with syntax highlighting and editing capabilities.
 *
 * This component renders a full-featured code editor using the BossEditor library
 * from Maven Central. It supports file loading/saving, syntax highlighting
 * for multiple languages, code folding, and modification tracking.
 *
 * **Features match the bundled BOSS editor:**
 * - Settings loaded from editor-settings.json under the BOSS data root
 * - Theme support: the host theme by default, or one of the bundled themes
 *   (Dark, Light, Dracula, Monokai, Solarized) once one is chosen
 * - Font customization (family, size, ligatures, line spacing)
 * - Status bar with cursor position, language, save status
 * - Minimap (optional)
 * - Advanced toggles (mark occurrences, highlight current line, navigation)
 */
/**
 * Viewport height assumed only when the editor canvas has not been laid out yet and so has
 * reported no real size. Navigation that still lands here has nothing better to centre against.
 */
internal const val UNMEASURED_VIEWPORT_HEIGHT_PX = 600f

/**
 * How long navigation will wait for the canvas's first measurement before centring on the
 * assumed height above. One frame is the normal case; this only bounds a pathological one.
 */
private const val FIRST_LAYOUT_TIMEOUT_MS = 1_000L

/** Line height and viewport height to centre a line against. */
internal data class ScrollMetrics(val lineHeight: Float, val viewportHeight: Float)

/**
 * Picks the metrics for centring a line: the canvas's own measurements when it has published
 * them, the caller's font-metric estimate otherwise.
 *
 * Each metric is guarded independently rather than treating one as an is-it-measured flag for
 * both - `scrollToLine` divides by `lineHeight`, so a zero slipping through on the strength of
 * a non-zero `viewportHeight` would be a divide-by-zero rather than a bad scroll.
 */
internal fun scrollMetrics(viewport: VisibleViewport, fallbackLineHeight: Float): ScrollMetrics =
    ScrollMetrics(
        lineHeight = viewport.lineHeight.takeIf { it > 0f } ?: fallbackLineHeight,
        viewportHeight = viewport.viewportHeight.takeIf { it > 0f } ?: UNMEASURED_VIEWPORT_HEIGHT_PX
    )

/**
 * Y offset at which a gutter icon for [visualLine] should be drawn, or null when that line is
 * not on screen - either hidden inside a collapsed fold (a negative visual line) or scrolled
 * out of the viewport.
 *
 * Takes a visual line, never a document line: with a fold collapsed above it the two diverge,
 * and the icon has to follow what is painted.
 */
internal fun gutterIconOffsetPx(
    visualLine: Int,
    metrics: ScrollMetrics,
    scrollOffsetY: Int
): Float? {
    if (visualLine < 0) return null
    val offset = visualLine * metrics.lineHeight - scrollOffsetY
    // One line of slack at the top so a partially scrolled row still draws.
    if (offset <= -metrics.lineHeight || offset >= metrics.viewportHeight) return null
    return offset
}

class EditorTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val context: PluginContext,
    private val markdownSettingsManager: MarkdownViewSettingsManager,
    private val autoSaveSettingsManager: AutoSaveSettingsManager,
    private val externalReloadSettingsManager: ExternalReloadSettingsManager
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = EditorTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // File path from config - handle both EditorTabData (from plugin) and EditorTabInfo (from host)
    private val filePath: String = extractFilePath(config)
    // Use project path from context if available, otherwise derive from file path
    private val projectPath: String = context.projectPath ?: filePath.substringBeforeLast('/')

    /**
     * Extracts file path from config, handling both:
     * - EditorTabData (dynamic plugin's own class)
     * - EditorTabInfo (host app's bundled class)
     *
     * Uses reflection to access filePath property since the classes are different.
     */
    private fun extractFilePath(config: TabInfo): String {
        // First try our own EditorTabData
        if (config is EditorTabData) {
            return config.filePath
        }

        // Try to get filePath via reflection (for EditorTabInfo from host)
        return try {
            val filePathProperty = config::class.memberProperties.find { it.name == "filePath" }
            filePathProperty?.getter?.call(config) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // Initial content and error state
    private var initialContent: String = ""
    private var loadError: String? = null
    private var isLargeFile: Boolean = false
    private var fileSizeBytes: Long = 0L

    // The shared buffer this viewport holds a reference on (null: untitled
    // document, a file that failed to load, or a large-file preview - the
    // preview is a truncation, not the file, so it must not be registered).
    // Acquired in init, released in onDestroy: a pair no composable early
    // return can break. The old version acquired in a `remember` after two
    // early returns while still releasing unconditionally, so a tab that
    // failed to load over-released a sibling tab's live buffer.
    private var buffer: EditorBuffer? = null
        private set

    // The AI services are built once per COMPONENT, not per composition. They
    // are handed the component's coroutineScope, so a service rebuilt on
    // re-entering the composition (a tab switch, a split re-layout) would
    // leave the PREVIOUS one's in-flight request still streaming on that
    // scope, into a StateFlow nobody reads, with the tokens already spent.
    //
    // runCatching rather than a try/catch on Exception because the failure
    // being guarded is a LinkageError: AiGatewayAPI is a parent-first api
    // class that hosts before 9.4.5 do not carry. Belt and braces only - this
    // plugin's floor (minApiVersion 1.0.87, minBossVersion 9.5.7) is far above
    // that, so such a host cannot load it at all and this guard cannot fire
    // today. It is here for a future in which the floor drops, not for any
    // shipping host.
    private val aiCompletion: AiTabCompletionService? by lazy {
        runCatching { AiTabCompletionService(context, coroutineScope) }.getOrNull()
    }

    private val aiInlineEdit: AiInlineEditService? by lazy {
        runCatching { AiInlineEditService(context, coroutineScope) }.getOrNull()
    }

    // The file as this tab last saw it, for the large-file poll alone. Normal
    // files baseline on the buffer's knownSignature (owned by the
    // plugin-wide watcher); large files have no buffer, so the poll keeps its
    // own snapshot.
    private var largeFileBaseline: DiskSnapshot = DiskSnapshot.MISSING

    // Language detection
    private val language: String = detectLanguage(filePath)

    // Markdown files get an Edit/Split/Preview toggle (rendered via BrowserService)
    private val isMarkdown: Boolean = language == "markdown"

    init {
        lifecycle.subscribe(
            callbacks = object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    coroutineScope.cancel()
                    // Drop this viewport's reference on the shared buffer; the
                    // registry removes the buffer only when the last viewport
                    // goes. Only when we actually acquired one - a failed load
                    // or a large-file preview did not, and releasing without
                    // having acquired would drop a sibling tab's live buffer.
                    buffer?.let { EditorBufferRegistry.release(it.path) }
                }
            }
        )

        // Load file content synchronously during init
        if (filePath.isNotEmpty()) {
            loadFileContent()
            // Acquired here, not in the composable: loadFileContent can leave a
            // loadError (missing file), and the composable returns early on it -
            // an acquire after those returns could never pair with the release
            // above. Large files keep a private EditorState (their buffer would
            // hold a truncated preview that buffer tools would present as the
            // live content).
            if (loadError == null && !isLargeFile) {
                buffer = EditorBufferRegistry.acquire(filePath, initialContent, language)
            }
        } else {
            initialContent = "// New file\n// Start typing...\n"
        }
    }

    private fun loadFileContent() {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                loadError = "File not found: $filePath"
                return
            }

            fileSizeBytes = file.length()
            // Stat BEFORE reading, never after: a write landing between the two would
            // otherwise be adopted as our baseline while we hold the pre-write content, and
            // the tab would sit stale forever. This way round the next poll simply sees a
            // difference, re-reads, and finds the content already matches.
            //
            // The baseline is the buffer's knownSignature for normal files (the
            // plugin-wide watcher owns it from here); largeFileBaseline is for
            // the large-file preview's own poll, since it has no buffer.
            largeFileBaseline = snapshotFile()

            // Check if this is a large file (>10MB)
            if (LargeFileDocument.shouldUseLargeFileAdapter(file)) {
                isLargeFile = true
                // For large files, we'll show a dialog and load in read-only mode
                // Load first 1MB for preview
                val previewSize = 1024 * 1024L
                initialContent = if (fileSizeBytes <= previewSize) {
                    file.readText()
                } else {
                    file.inputStream().bufferedReader().use { reader ->
                        val chars = CharArray(previewSize.toInt())
                        val read = reader.read(chars)
                        if (read > 0) String(chars, 0, read) + "\n\n// ... [File truncated - ${formatSize(fileSizeBytes)} total] ..."
                        else ""
                    }
                }
                loadError = null
                return
            }

            isLargeFile = false
            initialContent = file.readText()
            loadError = null
        } catch (e: Exception) {
            loadError = "Error loading file: ${e.message}"
            System.err.println("[EditorTabComponent] Failed to load file '$filePath': ${e.message}")
        }
    }

    /**
     * Writes [content] and returns the snapshot of what is now on disk, or null if the write
     * failed.
     *
     * Re-baselining the buffer is done here through noteWrittenByUs() rather than by the
     * caller: every save on this file (editor tab, diff tab, MCP tool) is invisible to the
     * watcher the same way.
     */
    private fun saveFile(content: String): DiskSnapshot? {
        if (filePath.isEmpty()) return null

        return try {
            val file = File(filePath)
            // Create parent directories if they don't exist (matches bundled editor)
            file.parentFile?.mkdirs()
            file.writeText(content)
            // SHARED bookkeeping, so the watcher does not report our own write -
            // and so a save made here is seen by every other viewport on this buffer.
            EditorBufferRegistry.find(filePath)?.noteWrittenByUs()
            snapshotFile()
        } catch (e: Exception) {
            System.err.println("[EditorTabComponent] Failed to save file '$filePath': ${e.message}")
            null
        }
    }

    /** One stat of the open file: cheap, and the only thing the poll does while nothing moves. */
    private fun snapshotFile(): DiskSnapshot {
        if (filePath.isEmpty()) return DiskSnapshot.MISSING

        return try {
            val file = File(filePath)
            if (!file.exists()) {
                DiskSnapshot.MISSING
            } else {
                DiskSnapshot(exists = true, lastModified = file.lastModified(), size = file.length())
            }
        } catch (e: Exception) {
            // A stat that throws (permissions, a filesystem going away) is not evidence the
            // file was deleted, so report no news rather than inventing a deletion.
            System.err.println("[EditorTabComponent] Error checking file '$filePath': ${e.message}")
            largeFileBaseline
        }
    }

    /** The file's current bytes, or null if it is gone or unreadable. */
    private fun readDiskText(): String? {
        if (filePath.isEmpty()) return null

        return try {
            val file = File(filePath)
            if (!file.exists()) null else file.readText()
        } catch (e: Exception) {
            System.err.println("[EditorTabComponent] Error reading file '$filePath': ${e.message}")
            null
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }

    @Composable
    override fun Content() {
        BossTheme {
            val settings by PluginEditorSettings.settings.collectAsState()
            ApplyHostChromeToEditor()
            val hostTheme = rememberHostEditorTheme()
            val editorTheme = remember(settings.followHostTheme, settings.themeName, hostTheme) {
                resolveEditorTheme(settings.followHostTheme, settings.themeName, hostTheme)
            }
            // Provided here rather than only handed to BossEditor: the search bar,
            // the rename/extract dialogs and the usages popup are siblings of the
            // editor canvas, so with only the canvas themed they kept rendering
            // against LocalEditorTheme's static Dark default.
            CompositionLocalProvider(LocalEditorTheme provides editorTheme) {
                EditorTabContent(settings)
            }
        }
    }

    @Composable
    private fun EditorTabContent(settings: PluginEditorSettingsData) {
        val scope = rememberCoroutineScope()

        // Settings arrive from Content(), which already collects them to resolve the
        // theme - collecting again here would recompose this subtree twice per change.
        val markdownViewSettings by markdownSettingsManager.settings.collectAsState()
        val markdownSettingsLoaded by markdownSettingsManager.isLoaded.collectAsState()

        // Get tab update provider for title updates
        val tabUpdateProviderFactory = context.tabUpdateProviderFactory
        val tabUpdateProvider = remember(config.id) {
            tabUpdateProviderFactory?.createProvider(config.id, EditorTabType.typeId)
        }

        // Show error if any
        if (loadError != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = loadError ?: "",
                    color = BossThemeColors.ErrorColor
                )
            }
            return
        }

        // Wait for the persisted Markdown preference before composing either
        // editor or preview, avoiding a transient Preview for Edit/Split users.
        if (isMarkdown && !markdownSettingsLoaded) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        // Create editor state. Files with a path share ONE buffer per path across
        // tabs and splits (decision D3): the registry hands every viewport the
        // same EditorState, so edits in one are instantly visible in the
        // others. The reference was taken in init - see [buffer]; this tab
        // holds it until onDestroy. Untitled documents and large-file
        // previews have no buffer and keep a private state.
        val editorBuffer = buffer
        val editorState =
            editorBuffer?.editorState ?: remember(filePath) {
                EditorState(initialContent, null)
            }

        // AI tab completion (ghost text) and Cmd/Ctrl+I/K inline edit, from the
        // component-level services - see their declarations for why they are
        // not remembered per composition.
        val aiCompletion = this.aiCompletion
        val ghostSuggestion = aiCompletion?.suggestion?.collectAsState()?.value
        val completionSettings by AiCompletionSettings.settings.collectAsState()

        val aiInlineEdit = this.aiInlineEdit
        aiInlineEdit?.bind(editorBuffer, editorState)
        val aiEditSession = aiInlineEdit?.session?.collectAsState()?.value
        var editorSurfaceSize by remember { mutableStateOf(IntSize.Zero) }

        // Get window ID for filtering navigation events (exactly like bundled editor)
        val windowId = context.windowId ?: ""

        // Get font family from settings (matches bundled editor exactly)
        val composeFontFamily = remember(settings.fontFamily) {
            try {
                FontFamily.Monospace
            } catch (e: Exception) {
                FontFamily.Monospace
            }
        }

        // Calculate line height for scrolling (same calculation as bundled EditorCanvas)
        val textMeasurer = rememberTextMeasurer()
        val lineHeightPx = remember(settings.fontSize, composeFontFamily, settings.lineSpacing) {
            val style = TextStyle(
                fontFamily = composeFontFamily,
                fontSize = settings.fontSize.sp
            )
            textMeasurer.measure("M", style).size.height.toFloat() * settings.lineSpacing
        }

        // Centre a line in the viewport, for navigation that jumps somewhere the caret was
        // not. BossEditor measures the canvas and publishes the result on visibleViewport,
        // so use those numbers rather than this composable's own font-metric guess and a
        // hardcoded height - in any window taller than the old 600px estimate, the "centred"
        // line landed well above centre.
        //
        // Still routed through scrollToLine rather than left to the library's caret-follow:
        // that follow deliberately does the minimum, parking the target against whichever
        // edge it entered from, and it does not expandToReveal a fold the target sits inside.
        fun scrollLineIntoView(line: Int) {
            val metrics = scrollMetrics(editorState.visibleViewport.value, lineHeightPx)
            editorState.scrollToLine(line, metrics.lineHeight, metrics.viewportHeight)
        }

        // Same, for navigation that can arrive before the canvas has been laid out.
        // navigationTargetProvider replays its targets, so a deep link or a cross-file
        // go-to-definition is consumed in roughly the frame the tab first composes - at which
        // point visibleViewport is still zeroed and the plain helper above would centre
        // against the assumed height, which is exactly the case this is all meant to fix.
        // Waiting one measurement costs nothing on the paths that are already laid out,
        // because the filter passes immediately.
        suspend fun scrollLineIntoViewOnceMeasured(line: Int) {
            val viewport = withTimeoutOrNull(FIRST_LAYOUT_TIMEOUT_MS) {
                editorState.visibleViewport.first { it.viewportHeight > 0f && it.lineHeight > 0f }
            } ?: editorState.visibleViewport.value
            val metrics = scrollMetrics(viewport, lineHeightPx)
            editorState.scrollToLine(line, metrics.lineHeight, metrics.viewportHeight)
        }

        // Listen for navigation targets (cursor positioning after navigation)
        // Matches bundled BossEditorIntegration exactly
        LaunchedEffect(filePath, editorState, windowId) {
            val navProvider = context.navigationTargetProvider ?: return@LaunchedEffect
            navProvider.targets.collect { target ->
                // Only process if this editor is showing the target file and event is for this window
                val isForThisWindow = target.sourceWindowId == windowId
                if (isForThisWindow && target.filePath == filePath && target.line > 0) {
                    try {
                        // Convert 1-based line/column to 0-based EditorPosition
                        val line = (target.line - 1).coerceAtLeast(0)
                        val column = (target.column - 1).coerceAtLeast(0)

                        // Position cursor
                        val position = EditorPosition(line, column)
                        editorState.moveCaret(position)
                        editorState.clearSelection()

                        // Scroll to make the line visible
                        scrollLineIntoViewOnceMeasured(line)

                        // Clear replay cache after consumption to avoid re-triggering
                        navProvider.clearCache()
                    } catch (e: Exception) {
                        // Log navigation errors without crashing
                        System.err.println("[EditorTabComponent] Navigation target processing failed: ${e.message}")
                    }
                }
            }
        }

        // Track cursor position for status bar
        var cursorLine by remember { mutableStateOf(1) }
        var cursorColumn by remember { mutableStateOf(1) }

        // Track modification state
        val isModified by editorState.isModified.collectAsState()

        // Track save state
        var isSaving by remember { mutableStateOf(false) }
        var saveError by remember { mutableStateOf<String?>(null) }

        // Bumped on every edit, purely to restart the auto save debounce below. isModified
        // cannot do that job: it latches true on the first edit and stays there, so it would
        // fire the timer once and never again.
        var editVersion by remember { mutableStateOf(0) }
        val autoSaveEnabled by autoSaveSettingsManager.enabled.collectAsState()

        // Review uses a private, read-only EditorState. The shared live document remains byte-for-
        // byte unchanged until Accept, so other splits, LSP, autosave and undo never observe a
        // proposal. The shadow contains the old block followed by the proposed block as a unified
        // red/green hunk.
        val reviewIsStale = aiEditSession?.done == true &&
            editorState.document.documentVersion != aiEditSession.bufferVersion
        LaunchedEffect(reviewIsStale, aiEditSession?.replacement) {
            if (reviewIsStale) aiInlineEdit.markStale()
        }
        val aiReviewDocument = remember(
            aiEditSession?.done,
            aiEditSession?.replacement,
            aiEditSession?.bufferVersion,
            editorState.document.documentVersion,
        ) {
            aiEditSession?.takeIf { it.done && it.replacement.isNotEmpty() && !reviewIsStale }?.let { session ->
                // This runs during composition. A corrupt/out-of-range captured position must
                // suppress the review, never let buildAiInlineReviewDocument's require crash UI.
                runCatching {
                    val start = editorState.document.positionToOffset(session.startLine, session.startCol)
                    val end = editorState.document.positionToOffset(session.endLine, session.endCol)
                    buildAiInlineReviewDocument(editorState.document.getText(), start, end, session.replacement)
                }.getOrNull()
            }
        }
        val reviewEditorState = remember(aiReviewDocument?.text, filePath) {
            aiReviewDocument?.let { review ->
                EditorState(review.text, filePath).also { shadow ->
                    shadow.moveCaret(EditorPosition(review.controlAnchorLine, 0))
                    shadow.setScrollOffset(editorState.scrollOffset.value)
                }
            }
        }
        val displayedEditorState = reviewEditorState ?: editorState
        val reviewRemovedColor = BossThemeColors.ErrorColor.copy(alpha = 0.20f)
        val reviewAddedColor = BossThemeColors.SuccessColor.copy(alpha = 0.20f)
        val reviewLineDecorations = remember(aiReviewDocument, reviewRemovedColor, reviewAddedColor) {
            aiReviewDocument?.let { review ->
                buildList {
                    repeat(review.removedLineCount) { offset ->
                        add(EditorLineDecoration(review.removedLineStart + offset, reviewRemovedColor))
                    }
                    repeat(review.addedLineCount) { offset ->
                        add(EditorLineDecoration(review.addedLineStart + offset, reviewAddedColor))
                    }
                }
            }.orEmpty()
        }

        // Search state
        var showSearchBar by remember { mutableStateOf(false) }
        var showReplaceInSearchBar by remember { mutableStateOf(false) }
        val searchManager = remember(editorState.document) { SearchManager(editorState.document) }
        var searchMatches by remember { mutableStateOf<List<EditorRange>>(emptyList()) }
        var currentSearchMatchIndex by remember { mutableStateOf(-1) }

        // Go-to-line dialog state
        var showGoToLineDialog by remember { mutableStateOf(false) }

        // Large file dialog state
        var showLargeFileDialog by remember { mutableStateOf(isLargeFile) }

        // Focus requester for keyboard handling
        val editorFocusRequester = remember { FocusRequester() }

        // Markdown preview state (only meaningful when isMarkdown). Settings are
        // loaded before reaching this point, so the configured mode is the first
        // mode composed and remains local to this tab afterward.
        var viewMode by remember {
            mutableStateOf(
                markdownViewSettings.initialViewMode(isDocumentEmpty = initialContent.isBlank())
            )
        }
        var markdownText by remember { mutableStateOf(initialContent) }

        // State for detected main functions (for run gutter)
        var detectedMainFunctions by remember { mutableStateOf<List<DetectedMainFunction>>(emptyList()) }

        // Version counter to force tokenProvider recreation when semantic data arrives.
        // Incrementing this causes `remember(tokenCache, filePath, semanticVersion)` to
        // recreate the lambda, which triggers the editor to re-render with semantic colors.
        var semanticVersion by remember { mutableStateOf(0) }

        fun noteLiveTextChanged() {
            editVersion++
            if (isMarkdown) {
                markdownText = editorState.document.getText()
            }
            if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                coroutineScope.launch {
                    semanticTokens.analyzeFile(filePath, editorState.document.getText())
                    semanticVersion++
                }
            }
            if (!isLargeFile) {
                aiCompletion?.schedule(editorState, filePath, language, completionSettings)
            }
        }

        // Bumped by every reload from disk. `initialContent` is a plain field rather than
        // snapshot state, so the two effects below would otherwise never re-run and the tab
        // would keep highlighting - and offering run icons for - the content it opened with.
        var contentVersion by remember { mutableStateOf(0) }

        // Trigger PSI semantic analysis on initial load. The plugin owns the PSI
        // stack (BossEditor is bundled in this JAR), so the provider writes
        // straight into the SemanticCache the editor's tokenProvider reads —
        // the old host-cache bridge is gone.
        LaunchedEffect(filePath, contentVersion) {
            if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                semanticTokens.analyzeFile(filePath, initialContent)
                semanticVersion++
            }
        }

        // Detect main functions when content changes
        LaunchedEffect(contentVersion, filePath, language) {
            if (filePath.isNotEmpty() && initialContent.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        val detected = MainFunctionDetector.detectInContent(initialContent, language, filePath)
                        withContext(Dispatchers.Main) {
                            detectedMainFunctions = detected
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            detectedMainFunctions = emptyList()
                        }
                    }
                }
            } else {
                detectedMainFunctions = emptyList()
            }
        }

        // State for usages popup
        var usagesPopupState by remember { mutableStateOf(UsagesPopupState.Hidden) }

        // State for navigation feedback popup
        var navigationFeedbackState: NavigationFeedbackState by remember { mutableStateOf(NavigationFeedbackState.Hidden) }

        // State for rename dialog
        var showRenameDialog by remember { mutableStateOf(false) }
        var renameSymbolName by remember { mutableStateOf("") }
        var renameSymbolKind by remember { mutableStateOf<SymbolKind?>(null) }
        var renamePosition by remember { mutableStateOf(EditorPosition(0, 0)) }
        var renameDialogOffset by remember { mutableStateOf(IntOffset.Zero) }

        // State for extract variable dialog
        var showExtractVariableDialog by remember { mutableStateOf(false) }
        var extractVariableSuggestedName by remember { mutableStateOf("") }
        var extractVariableExpression by remember { mutableStateOf("") }
        var extractVariableSelection by remember { mutableStateOf<EditorRange?>(null) }

        // State for extract method dialog
        var showExtractMethodDialog by remember { mutableStateOf(false) }
        var extractMethodSuggestedName by remember { mutableStateOf("") }
        var extractMethodCode by remember { mutableStateOf("") }
        var extractMethodSelection by remember { mutableStateOf<EditorRange?>(null) }

        // Create refactoring instances (RenameRefactoring needs NavigationService but we'll use simple text-based rename)
        val extractVariableRefactoring = remember { ExtractVariableRefactoring() }
        val extractMethodRefactoring = remember { ExtractMethodRefactoring() }
        val inlineRefactoring = remember { InlineRefactoring() }

        // Update tab title when modification state changes
        LaunchedEffect(isModified) {
            val baseTitle = filePath.substringAfterLast('/').ifEmpty { "Untitled" }
            val displayTitle = if (isModified) "$baseTitle *" else baseTitle
            tabUpdateProvider?.updateTitle(displayTitle)
        }

        // Create lexer based on language (matches bundled editor exactly)
        val lexer = remember(language) {
            getLexerForLanguage(language.lowercase())
        }

        // Create token cache for multi-line state tracking (only if lexer is available)
        // Matches bundled BossEditorIntegration exactly
        val tokenCache = remember(lexer, editorState.document) {
            lexer?.let { TokenCache(editorState.document, it) }
        }

        // Dispose token cache when composable is disposed
        DisposableEffect(tokenCache) {
            onDispose {
                tokenCache?.dispose()
            }
        }

        // Token provider for lexer-based + semantic syntax highlighting (matches bundled editor exactly)
        // Uses TokenCache for proper multi-line state tracking (block comments, raw strings, etc.)
        // Merges with SemanticCache for PSI-based semantic highlighting (function calls, properties, etc.)
        val tokenProvider: (Int) -> List<EditorToken> = remember(tokenCache, filePath, semanticVersion) {
            { lineNumber ->
                // Get lexer-based tokens (cached, handles multi-line state)
                val lexerTokens: List<Token> = tokenCache?.getLineTokens(lineNumber) ?: emptyList()

                // Get semantic tokens from PSI analysis (if available for this file)
                val semanticTokens = getSemanticTokensForLine(editorState.document, filePath, lineNumber)

                // Merge tokens (semantic takes precedence for overlapping ranges)
                val mergedTokens = if (semanticTokens.isNotEmpty()) {
                    mergeTokens(lexerTokens, semanticTokens)
                } else {
                    lexerTokens
                }

                EditorToken.fromTokens(mergedTokens)
            }
        }
        val emptyTokenProvider: (Int) -> List<EditorToken> = remember { { _: Int -> emptyList() } }

        // Resolved in Content() (host theme unless a fixed theme was chosen) and
        // provided to this subtree, so the canvas and the popups around it cannot
        // disagree about which theme is active.
        val editorTheme = LocalEditorTheme.current
        val editorInlineSuggestion = remember(ghostSuggestion, aiReviewDocument, editorTheme.colors.text) {
            ghostSuggestion?.takeIf { aiReviewDocument == null }?.let { suggestion ->
                EditorInlineSuggestion(
                    position = suggestion.position,
                    text = suggestion.text,
                    color = editorTheme.colors.text.copy(alpha = 0.45f),
                )
            }
        }

        // Parse minimap custom colors from settings (matches bundled editor exactly)
        val minimapBgColor = remember(settings.minimapBackgroundColor) {
            settings.minimapBackgroundColor?.let { parseHexColor(it) }
        }
        val minimapFgColor = remember(settings.minimapForegroundColor) {
            settings.minimapForegroundColor?.let { parseHexColor(it) }
        }

        // --- Following the file on disk -------------------------------------------------
        //
        // Normal files have exactly one owner: the plugin-wide
        // [ExternalChangeWatcher] (installed in EditorTabDynamicPlugin), which
        // walks the buffer registry - so the diff tab's editable pane is
        // covered too, and the RELOAD verdict honors the user's "reload
        // externally changed files" setting. There used to be a per-tab poll
        // here running alongside the watcher: two decision surfaces for one
        // event, two baselines (the watcher's reloads never updated this one),
        // and two stat loops per file.
        //
        // Large files are the exception: their document is a truncated
        // preview, not a registered buffer, so the watcher never sees them.
        // This poll is their only owner - and since the preview cannot hold
        // the file, it can only tell the user, never reload.
        var diskNotice by remember { mutableStateOf<String?>(null) }

        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(filePath, windowFocused) {
            if (!isLargeFile || filePath.isEmpty()) return@LaunchedEffect

            val quietInterval =
                if (windowFocused) DISK_POLL_INTERVAL_MILLIS else DISK_BACKGROUND_POLL_INTERVAL_MILLIS
            var baseline = largeFileBaseline

            while (isActive) {
                delay(quietInterval)
                val snapshot = withContext(Dispatchers.IO) { snapshotFile() }
                if (snapshot == baseline) continue
                baseline = snapshot
                largeFileBaseline = snapshot
                diskNotice =
                    if (snapshot.exists) {
                        "changed on disk - reopen to refresh"
                    } else {
                        // Deliberately no emptying of the buffer: the text on screen may be
                        // the only copy left. saveFile recreates parent directories, so
                        // Cmd+S puts it back.
                        "deleted on disk"
                    }
            }
        }

        // One save path for both Cmd+S and auto save, so they cannot drift on what counts as
        // saved or how failure is surfaced.
        suspend fun persistDocument() {
            if (isLargeFile || filePath.isEmpty()) return
            if (!editorState.isModified.value) return

            // Never write over something another program put there. This matters most for
            // auto save, which fires on a timer the user is not thinking about: without the
            // check, a debounce landing after vim wrote the file would erase that write with
            // no prompt and no trace.
            //
            // Not gated on the reload setting. That setting is about whether the tab follows
            // the file; this is about not destroying someone else's work on the way out, which
            // is worth asking about however the tab is configured.
            //
            // Asked of the BUFFER's baseline, not a tab-private copy: the watcher owns
            // external changes and re-baselines it after every verdict, so a save right
            // after a watcher reload cannot re-report the same change. A real conflict is
            // surfaced through the same bar the watcher uses - one decision surface, not a
            // second dialog.
            buffer?.let { b ->
                val current = withContext(Dispatchers.IO) { signatureOf(File(filePath)) }
                if (current != b.knownSignature && current.exists) {
                    val diskText = withContext(Dispatchers.IO) { readDiskText() }
                    if (diskText != null && diskText != editorState.document.getText()) {
                        b.setExternalState(ExternalState.CONFLICT)
                        return
                    }
                }
            }

            isSaving = true
            saveError = null
            val content = editorState.document.getText()
            if (withContext(Dispatchers.IO) { saveFile(content) } != null) {
                editorState.markAsSaved()
            } else {
                saveError = "Failed to save file"
            }
            isSaving = false
        }

        // Auto save. Keyed on editVersion, so each keystroke cancels the pending timer and
        // starts a new one - a burst of typing produces one write, not one per character.
        // Also keyed on the toggle, so switching it on with unsaved changes flushes them.
        LaunchedEffect(editVersion, autoSaveEnabled, isLargeFile, filePath) {
            if (!autoSaveEnabled) return@LaunchedEffect
            delay(AUTO_SAVE_DEBOUNCE_MILLIS)
            persistDocument()
        }

        // Helper function to perform search and update state
        fun performSearch(query: String, options: SearchOptions) {
            if (query.isEmpty()) {
                searchMatches = emptyList()
                currentSearchMatchIndex = -1
                return
            }
            searchManager.search(query, options)
            // Convert OffsetRange to EditorRange for highlighting
            searchMatches = searchManager.allMatches.map { match ->
                val startPos = editorState.document.offsetToPosition(match.startOffset)
                val endPos = editorState.document.offsetToPosition(match.endOffset)
                EditorRange(startPos, endPos)
            }
            currentSearchMatchIndex = searchManager.currentIndex
        }

        // Helper to navigate to current match
        fun navigateToCurrentMatch() {
            val match = searchManager.currentMatch ?: return
            val pos = editorState.document.offsetToPosition(match.startOffset)
            editorState.moveCaret(EditorPosition(pos.line, pos.column))
            scrollLineIntoView(pos.line)
            currentSearchMatchIndex = searchManager.currentIndex
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(editorFocusRequester)
                .onPreviewKeyEvent { event ->
                    // The focused EditorCanvas has its own key handler. AI
                    // shortcuts must run in the tunnelling phase so they cannot
                    // be consumed before this component's bubbling handler.
                    val inlineAiModifier =
                        if (isMacPlatform()) event.isMetaPressed else event.isCtrlPressed
                    val composeShortcut =
                        event.type == KeyEventType.KeyDown &&
                            inlineAiModifier &&
                            !event.isShiftPressed && !event.isAltPressed &&
                            (event.key == Key.I || event.key == Key.K) &&
                            !isLargeFile
                    if (composeShortcut) {
                        return@onPreviewKeyEvent aiInlineEdit?.start(editorState, language) == true
                    }
                    // Preview (tunneling) phase, so this runs before the editor's
                    // own handler: with a ghost suggestion showing, Tab must
                    // accept it instead of inserting an indent, Esc must dismiss
                    // it instead of closing the search bar.
                    if (event.type != KeyEventType.KeyDown || ghostSuggestion == null) {
                        false
                    } else {
                        when {
                            event.key == Key.Tab && !event.isShiftPressed && !event.isMetaPressed &&
                                !event.isCtrlPressed && !event.isAltPressed ->
                                aiCompletion.accept(editorState)
                            event.key == Key.Escape -> aiCompletion.dismiss()
                            else -> false
                        }
                    }
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        // Cmd+F / Ctrl+F style: the find and go-to-line
                        // shortcuts follow the host convention on every
                        // platform. The Mac EDITING shortcuts (line delete,
                        // line jump) are Cmd-only: on Windows/Linux the
                        // Ctrl+arrow and Ctrl+Backspace keys keep their
                        // native word-wise behaviour in the editor.
                        val isMeta = event.isMetaPressed || event.isCtrlPressed
                        val isCmd = event.isMetaPressed
                        val inlineAiModifier =
                            if (isMacPlatform()) event.isMetaPressed else event.isCtrlPressed
                        // Both shortcut helpers are evaluated at most ONCE per
                        // key (each copies the whole document, and the old
                        // guard-then-!! pattern paid that twice). The results
                        // feed [editorShortcutFor], a pure function that
                        // decides what this key does - tested as a table,
                        // because a `when` branch whose condition matches is
                        // the END of dispatch, and conditions on
                        // isLargeFile/hasSelection alone match almost every
                        // keydown (that mistake once made Cmd+S/F/K/F3/
                        // Escape unreachable on every normal file). The
                        // composable only executes the decision.
                        val editRange =
                            if (!isLargeFile && !editorState.hasSelection) {
                                editingShortcutRange(event, isCmd, editorState)
                            } else {
                                null
                            }
                        val caretTarget =
                            if (!isLargeFile) caretShortcutTarget(event, isCmd, editorState) else null
                        val action =
                            editorShortcutFor(
                                key = event.key,
                                isMeta = isMeta,
                                isInlineAiModifier = inlineAiModifier,
                                isShift = event.isShiftPressed,
                                isLargeFile = isLargeFile,
                                showSearchBar = showSearchBar,
                                editRange = editRange,
                                caretTarget = caretTarget,
                            )
                        when (action) {
                            is EditorKeyAction.EditRange -> {
                                editorState.document.replace(action.range.first, action.range.last + 1, "")
                                true
                            }

                            is EditorKeyAction.MoveCaret -> {
                                editorState.moveCaret(editorState.document.offsetToPosition(action.offset))
                                true
                            }

                            // Cmd+F or Ctrl+F: Show find
                            EditorKeyAction.ShowFind -> {
                                showSearchBar = true
                                showReplaceInSearchBar = false
                                true
                            }
                            // Cmd+H or Ctrl+H: Show find and replace
                            EditorKeyAction.ShowFindReplace -> {
                                showSearchBar = true
                                showReplaceInSearchBar = true
                                true
                            }
                            // Cmd+G or Ctrl+G or Cmd+L: Go to line
                            EditorKeyAction.GoToLine -> {
                                showGoToLineDialog = true
                                true
                            }
                            // Cmd+Y: Redo (alternative to Cmd+Shift+Z)
                            EditorKeyAction.Redo -> {
                                editorState.redo()
                                true
                            }
                            // Cmd/Ctrl+I or Cmd/Ctrl+K: AI inline edit on the
                            // selection (or current line).
                            EditorKeyAction.InlineAiEdit -> {
                                aiInlineEdit?.start(editorState, language) == true
                            }
                            // Cmd+S or Ctrl+S: Save file
                            EditorKeyAction.Save -> {
                                scope.launch { persistDocument() }
                                true
                            }
                            // F3: Find next
                            EditorKeyAction.FindNext -> {
                                if (searchManager.matchCount > 0) {
                                    searchManager.findNext()
                                    navigateToCurrentMatch()
                                }
                                true
                            }
                            // Shift+F3: Find previous
                            EditorKeyAction.FindPrevious -> {
                                if (searchManager.matchCount > 0) {
                                    searchManager.findPrevious()
                                    navigateToCurrentMatch()
                                }
                                true
                            }
                            // Escape: Close search bar
                            EditorKeyAction.CloseSearch -> {
                                showSearchBar = false
                                searchMatches = emptyList()
                                currentSearchMatchIndex = -1
                                searchManager.clearSearch()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            // Search bar (shown above editor when active)
            if (showSearchBar) {
                SearchBar(
                    showReplace = showReplaceInSearchBar,
                    matchCount = searchManager.matchCount,
                    currentMatchIndex = currentSearchMatchIndex,
                    onSearch = { query, options ->
                        performSearch(query, options)
                    },
                    onFindNext = {
                        searchManager.findNext()
                        navigateToCurrentMatch()
                    },
                    onFindPrevious = {
                        searchManager.findPrevious()
                        navigateToCurrentMatch()
                    },
                    onReplace = { replacement ->
                        if (searchManager.replace(replacement)) {
                            performSearch(searchManager.query, searchManager.options)
                            navigateToCurrentMatch()
                        }
                    },
                    onReplaceAll = { replacement ->
                        searchManager.replaceAll(replacement)
                        searchMatches = emptyList()
                        currentSearchMatchIndex = -1
                    },
                    onClose = {
                        showSearchBar = false
                        searchMatches = emptyList()
                        currentSearchMatchIndex = -1
                        searchManager.clearSearch()
                    }
                )
            }

            // The file changed underneath us, or went away. Above the editor
            // rather than in the status bar: a conflict is a decision the user
            // has to make, not a status to glance at.
            if (editorBuffer != null) {
                ExternalChangeBar(
                    buffer = editorBuffer,
                    onReload = {
                        // Re-baselines the buffer's own signature; the tab keeps no
                        // copy anymore, so there is nothing else to update.
                        ExternalChangeWatcher.current()?.resolveByReloading(editorBuffer)
                        diskNotice = null
                    },
                    onKeepMine = {
                        ExternalChangeWatcher.current()?.resolveByKeepingMine(editorBuffer)
                    },
                )
            }

            // Main editor area with run gutter
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Run gutter (for detected main functions)
                if (detectedMainFunctions.isNotEmpty()) {
                    EditorRunGutter(
                        detectedMainFunctions = detectedMainFunctions,
                        editorState = editorState,
                        fontSize = settings.fontSize,
                        fontFamily = composeFontFamily,
                        lineSpacing = settings.lineSpacing,
                        onRun = { mainFunc ->
                            // Use runConfigurationDataProvider if available (matches bundled executeDetectedMainFunction exactly)
                            val windowId = context.windowId ?: return@EditorRunGutter
                            context.runConfigurationDataProvider?.let { provider ->
                                scope.launch {
                                    // Find actual project root and generate command (matches bundled editor exactly)
                                    val actualProjectRoot = MainFunctionDetector.findProjectRoot(mainFunc.filePath)
                                    val command = MainFunctionDetector.generateCommand(mainFunc, actualProjectRoot)

                                    // Create config name exactly like bundled editor
                                    val configName = mainFunc.toShortNameWithProject(actualProjectRoot)

                                    // Map language string to LanguageData (matches bundled detected.language)
                                    val langData = mainFunc.toLanguageData()

                                    // Create run configuration exactly like bundled executeDetectedMainFunction
                                    val config = ai.rever.boss.plugin.api.RunConfigurationData(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = configName,
                                        type = ai.rever.boss.plugin.api.RunConfigurationTypeData.MAIN_FUNCTION,
                                        filePath = mainFunc.filePath,
                                        lineNumber = mainFunc.lineNumber,
                                        language = langData,
                                        command = command,
                                        workingDirectory = actualProjectRoot,
                                        isAutoDetected = true
                                    )

                                    provider.execute(config, windowId)
                                }
                            }
                        },
                        modifier = Modifier
                            .width(28.dp)
                            .fillMaxHeight()
                            .background(editorTheme.colors.gutterBackground)
                    )
                }

                // Editor content (hidden when a markdown file is in Preview-only mode)
                if (!(isMarkdown && viewMode == MarkdownViewMode.PREVIEW)) {
                    // "Open Diff" on right-click of the editor surface (IDE batch P1.4): the
                    // host's diff tab shows this file's working-tree diff. The menu only
                    // appears when a git data provider exists; for untracked files the
                    // diff tab itself reports "no diff".
                    val editorSurfaceModifier =
                        context.contextMenuProvider?.applyContextMenu(
                            Modifier,
                            if (filePath.isNotEmpty() && context.gitDataProvider != null) {
                                listOf(
                                    ContextMenuItemData(
                                        label = "Open Diff",
                                        onClick = { context.gitDataProvider?.openDiff(filePath, context.windowId ?: "", staged = false) },
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        ) ?: Modifier
                    // Uncommitted changes for the left gutter. Empty for an untitled
                    // document, which has no buffer and so no file to compare.
                    val gitMarks: Map<Int, LineDiff.Mark> =
                        editorBuffer?.gitMarks?.collectAsState()?.value ?: emptyMap()
                    val displayedGutterIcons = remember(gitMarks, editorBuffer, aiReviewDocument != null) {
                        if (aiReviewDocument == null) {
                            reserveGitGutter(gitMarks, reserveWhenEmpty = editorBuffer != null)
                        } else {
                            emptyList()
                        }
                    }
                    // A settings change must replace the resolver in already-open tabs. It also
                    // forces LspSettingsManager's synchronous load before consulting the registry.
                    val lspConfig by LspSettingsManager.instance.configuration.collectAsState()
                    // One guard for all three LSP call sites (warm-up, navigation, hover) so
                    // they cannot drift apart: a large file never starts a server, a disabled
                    // feature answers nothing, and only files whose language has a registered
                    // server (PSI-only .kt/.kts do not) can be served. Remembered on the
                    // same keys as the warm-up below, so the registry is consulted only
                    // when one of them changes - not on every recomposition (caret moves,
                    // gutter updates).
                    val lspServesThisFile = remember(filePath, lspConfig, isLargeFile) {
                        !isLargeFile && lspConfig.enabled && LspNavigation.usesLsp(filePath)
                    }
                    // Warm the language server on open (and on every disk reload) so the
                    // first hover or Cmd+Click answers from a warm server instead of
                    // paying spawn + initialize + settle on the user's first gesture.
                    // lspConfig is a key - not just read in the body - so enabling LSP in
                    // settings with tabs already open re-arms the warm-up; without it the
                    // first gesture still pays the cold start, the case this exists to fix.
                    // No-op for files nothing can serve; edits keep in sync because
                    // every later lookup re-syncs the document.
                    LaunchedEffect(filePath, contentVersion, lspConfig) {
                        if (!lspServesThisFile) {
                            return@LaunchedEffect
                        }
                        LspNavigation.shared.warmUp(initialContent, filePath, projectPath)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(editorSurfaceModifier)
                            .onSizeChanged { editorSurfaceSize = it },
                    ) {
                    // Main editor (matches bundled BossEditorIntegration exactly)
                    BossEditor(
                    state = displayedEditorState,
                    modifier = Modifier.fillMaxSize(),
                    theme = editorTheme,
                    fontFamily = composeFontFamily,
                    fontSize = settings.fontSize,
                    lineSpacing = settings.lineSpacing,
                    showLineNumbers = settings.showLineNumbers,
                    highlightCurrentLine = settings.highlightCurrentLine,
                    readOnly = isLargeFile || aiReviewDocument != null,
                    filePath = filePath,
                    projectPath = projectPath,
                    showMinimap = settings.showMinimap,
                    minimapWidth = settings.minimapWidth,
                    minimapUseEditorColors = settings.minimapUseEditorColors,
                    minimapBackgroundColor = minimapBgColor,
                    minimapForegroundColor = minimapFgColor,
                    // The live TokenCache is bound to the live document, so its offsets are not
                    // valid for the red/green shadow document. Keep review text deliberately
                    // unhighlighted rather than applying plausible colors at wrong positions.
                    tokenProvider = if (aiReviewDocument == null) tokenProvider else emptyTokenProvider,
                    // Transparent, purely to make the editor reserve its gutter
                    // icon strip; GitGutterMarks draws the glyphs into it,
                    // because drawGutterIconForLine can paint a shape but never
                    // text.
                    gutterIcons = displayedGutterIcons,
                    searchMatches = if (aiReviewDocument == null) searchMatches else emptyList(),
                    currentSearchMatchIndex = if (aiReviewDocument == null) currentSearchMatchIndex else -1,
                    lineDecorations = reviewLineDecorations,
                    inlineSuggestion = editorInlineSuggestion,
                    // Per FILE, not per editor. `navigationResolver` REPLACES the
                    // internal PSI NavigationManager rather than layering over it, so
                    // this is the only way to keep both: Kotlin stays on PSI, which is
                    // the sole path that can answer ShowUsages (the resolver contract
                    // is Found/NotFound and has no variant for it), and every other
                    // language with a registered server goes to LSP. Unsupported files
                    // keep the built-in Unavailable result instead of claiming a lookup failed.
                    navigationResolver = remember<(suspend (String, String, Int) -> NavigationResolveResult)?>(
                        filePath,
                        projectPath,
                        lspConfig,
                        isLargeFile,
                    ) {
                        if (!lspServesThisFile) {
                            null
                        } else {
                            { content, path, offset ->
                                LspNavigation.shared.resolveDefinition(content, path, offset, projectPath)
                            }
                        }
                    },
                    // The pointer counterpart of navigation: rest over a symbol and the
                    // server's signature/docs appear in a tooltip. Same routing as the
                    // resolver - PSI has no hover path at all, so only files whose
                    // language has a registered server can answer, and the same guards
                    // apply (a large file does not start a server just for the pointer).
                    hoverProvider = remember<(suspend (String, String, Int) -> EditorHover?)?>(
                        filePath,
                        projectPath,
                        lspConfig,
                        isLargeFile,
                    ) {
                        if (!lspServesThisFile) {
                            null
                        } else {
                            { content, path, offset ->
                                LspNavigation.shared.resolveHover(content, path, offset, projectPath)
                            }
                        }
                    },
                    onTextChanged = {
                        noteLiveTextChanged()
                    },
                    onCaretPositionChanged = { position ->
                        // Convert to 1-based line/column for compatibility
                        cursorLine = position.line + 1
                        cursorColumn = position.column + 1
                        aiCompletion?.onCaretMoved(position)
                        // Caret activity is the "focused document" heuristic
                        // for focusedDocument(): the user is here.
                        editorBuffer?.let { EditorBufferRegistry.markFocused(it) }
                    },
                    onSelectionChanged = { _ ->
                        // Selection changed - could integrate with mark occurrences
                    },
                    onNavigate = { navFilePath, line, column ->
                        // Use openFileAtPosition for ALL navigation (same as bundled editor)
                        // This ensures consistent behavior through FileEventBus → NavigationTargetBus
                        val fileName = navFilePath.substringAfterLast('/')
                        context.splitViewOperations?.openFileAtPosition(navFilePath, fileName, line, column)
                    },
                    onShowUsages = { references, definition, clickPosition ->
                        usagesPopupState = UsagesPopupState(
                            isVisible = true,
                            references = references,
                            definition = definition,
                            anchorOffset = IntOffset(clickPosition.x.toInt(), clickPosition.y.toInt())
                        )
                    },
                    onNavigationFailed = { reason, clickPosition ->
                        navigationFeedbackState = NavigationFeedbackState.Visible(
                            reason = reason,
                            anchorOffset = IntOffset(clickPosition.x.toInt(), clickPosition.y.toInt())
                        )
                    },
                    onRename = {
                        // Get current position and extract word at cursor for rename
                        val position = editorState.caretPosition.value
                        renamePosition = position

                        // Only support Kotlin files for now
                        if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                            // Extract word at cursor position
                            val lineStart = editorState.document.getLineStartOffset(position.line)
                            val lineEnd = editorState.document.getLineEndOffset(position.line)
                            val lineText = editorState.document.getText(lineStart, lineEnd)
                            val wordAtCursor = extractWordAtPosition(lineText, position.column)

                            if (wordAtCursor.isNotEmpty()) {
                                renameSymbolName = wordAtCursor
                                renameSymbolKind = null // We don't know the kind without PSI

                                // Calculate dialog position near the caret
                                val viewport = editorState.visibleViewport.value
                                val lineHeight = viewport.lineHeight.takeIf { it > 0 } ?: 20f
                                val charWidth = viewport.charWidth.takeIf { it > 0 } ?: 8f
                                val firstVisibleLine = viewport.firstVisibleLine
                                val gutterWidth = 60
                                val visualY = ((position.line - firstVisibleLine) * lineHeight).toInt()
                                val visualX = (position.column * charWidth).toInt() + gutterWidth
                                renameDialogOffset = IntOffset(visualX, visualY)
                                showRenameDialog = true
                            }
                        }
                    },
                    onExtractVariable = {
                        // Get current selection for extract variable
                        val selection = editorState.selection.value
                        if (selection == null || selection.isEmpty) {
                            return@BossEditor
                        }

                        val selectedText = editorState.document.getText(
                            editorState.document.positionToOffset(selection.start.line, selection.start.column),
                            editorState.document.positionToOffset(selection.end.line, selection.end.column)
                        )

                        extractVariableSelection = selection
                        extractVariableExpression = selectedText

                        // Launch coroutine to get suggested name
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                                    val context = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )
                                    val suggestedName = extractVariableRefactoring.suggestVariableName(context)

                                    withContext(Dispatchers.Main) {
                                        extractVariableSuggestedName = suggestedName
                                        showExtractVariableDialog = true
                                    }
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Extract variable failed: ${e.message}")
                            }
                        }
                    },
                    onExtractMethod = {
                        // Check for selection and execute extract method
                        scope.launch(Dispatchers.IO) {
                            try {
                                val selection = editorState.selection.value
                                if (selection == null || selection.isEmpty) {
                                    return@launch
                                }

                                // Store the selection and selected code
                                extractMethodSelection = selection

                                // Get the selected text from the document
                                val document = editorState.document
                                val startOffset = document.positionToOffset(selection.start)
                                val endOffset = document.positionToOffset(selection.end)
                                val selectedText = document.getText(startOffset, endOffset)
                                extractMethodCode = selectedText

                                // Only support Kotlin files for now
                                if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                                    val context = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )
                                    val suggestedName = extractMethodRefactoring.suggestMethodName(context)

                                    withContext(Dispatchers.Main) {
                                        extractMethodSuggestedName = suggestedName
                                        showExtractMethodDialog = true
                                    }
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Extract method failed: ${e.message}")
                            }
                        }
                    },
                    onInline = {
                        // Execute inline refactoring at current position
                        scope.launch(Dispatchers.IO) {
                            try {
                                // Only support Kotlin files for now
                                if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                                    val position = editorState.caretPosition.value
                                    val context = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = position,
                                        selection = null
                                    )

                                    val result = inlineRefactoring.execute(context)
                                    when (result) {
                                        is RefactorResult.Success -> {
                                            // Apply the workspace edit
                                            val applier = WorkspaceEditApplier(
                                                documentProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.document else null
                                                },
                                                undoManagerProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.undoManager else null
                                                },
                                                onFileModified = { _ -> }
                                            )
                                            applier.apply(result.edit)
                                        }
                                        is RefactorResult.Error -> {
                                            // Could show error notification
                                        }
                                        else -> { }
                                    }
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Inline refactoring failed: ${e.message}")
                            }
                        }
                    }
                )

                // Uncommitted-change markers in the editor's own gutter strip.
                // The diff tab draws its own gutter and never reaches this
                // path, so the two cannot fight.
                if (aiReviewDocument == null) {
                    GitGutterMarks(gitMarks, editorState)
                }

                // Usages popup overlay (exactly like bundled editor)
                if (usagesPopupState.isVisible && usagesPopupState.definition != null) {
                    UsagesPopup(
                        references = usagesPopupState.references,
                        definition = usagesPopupState.definition!!,
                        anchorOffset = usagesPopupState.anchorOffset,
                        onNavigate = { navFilePath, navLine, navColumn ->
                            // Use openFileAtPosition for ALL navigation (same as bundled editor)
                            // This ensures consistent behavior through FileEventBus → NavigationTargetBus
                            // The cursor positioning is handled by the LaunchedEffect listening to NavigationTargetProvider
                            val fileName = navFilePath.substringAfterLast('/')
                            context.splitViewOperations?.openFileAtPosition(navFilePath, fileName, navLine, navColumn)
                        },
                        onDismiss = {
                            usagesPopupState = UsagesPopupState.Hidden
                        },
                        theme = editorTheme
                    )
                }

                // Navigation feedback popup (when navigation fails)
                val feedbackState = navigationFeedbackState
                if (feedbackState is NavigationFeedbackState.Visible) {
                    NavigationFeedbackPopup(
                        reason = feedbackState.reason,
                        anchorOffset = feedbackState.anchorOffset,
                        onDismiss = {
                            navigationFeedbackState = NavigationFeedbackState.Hidden
                        },
                        theme = editorTheme
                    )
                }

                // Rename dialog
                if (showRenameDialog && renameSymbolName.isNotEmpty()) {
                    RenameDialog(
                        currentName = renameSymbolName,
                        symbolKind = renameSymbolKind,
                        anchorOffset = renameDialogOffset,
                        onRename = { newName ->
                            showRenameDialog = false
                            // Execute simple text-based rename (replace all occurrences)
                            try {
                                val currentContent = editorState.document.getText()
                                // Use word boundary matching to avoid partial replacements
                                val pattern = "\\b${Regex.escape(renameSymbolName)}\\b".toRegex()
                                val newContent = pattern.replace(currentContent, newName)
                                if (newContent != currentContent) {
                                    editorState.document.setText(newContent)
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Rename symbol failed: ${e.message}")
                            }
                        },
                        onCancel = {
                            showRenameDialog = false
                        },
                        onValidate = { newName ->
                            // Simple validation for identifier names
                            when {
                                newName.isBlank() -> "Name cannot be empty"
                                !newName.first().isLetter() && newName.first() != '_' -> "Name must start with a letter or underscore"
                                !newName.all { it.isLetterOrDigit() || it == '_' } -> "Name contains invalid characters"
                                newName == renameSymbolName -> "Name is unchanged"
                                else -> null
                            }
                        }
                    )
                }

                // AI inline edit (Cmd/Ctrl+I/K): one inline card over the editor,
                // Cursor-style prompt and compact accept/reject controls. Review code is the
                // red/green shadow EditorState rendered in the main editor surface above.
                if (aiEditSession != null) {
                    val inlineEditService = aiInlineEdit
                    AnchoredAiInlineEditBar(
                        session = aiEditSession,
                        editorState = displayedEditorState,
                        editorSurfaceSize = editorSurfaceSize,
                        fallbackLineHeight = lineHeightPx,
                        anchorLine = aiReviewDocument?.controlAnchorLine,
                        anchorCol = if (aiReviewDocument != null) 0 else null,
                        onPromptChange = inlineEditService::setPrompt,
                        onSubmit = inlineEditService::submit,
                        onAccept = {
                            reviewEditorState?.scrollOffset?.value?.let(editorState::setScrollOffset)
                            if (inlineEditService.applyAccepted()) {
                                // While reviewing, BossEditor observes the shadow state. Notify
                                // the live-buffer side effects explicitly for this one edit.
                                noteLiveTextChanged()
                            } else {
                                inlineEditService.markStale()
                            }
                        },
                        onCancel = {
                            reviewEditorState?.scrollOffset?.value?.let(editorState::setScrollOffset)
                            inlineEditService.cancel()
                        },
                    )
                }

                // Extract variable dialog
                if (showExtractVariableDialog && extractVariableExpression.isNotEmpty()) {
                    ExtractVariableDialog(
                        suggestedName = extractVariableSuggestedName,
                        selectedExpression = extractVariableExpression,
                        onExtract = { variableName, replaceAll, isVal ->
                            showExtractVariableDialog = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val selection = extractVariableSelection
                                    if (selection == null) return@launch

                                    val refactorContext = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )

                                    val params = ExtractVariableParams(
                                        variableName = variableName,
                                        replaceAll = replaceAll,
                                        isVal = isVal
                                    )

                                    val result = extractVariableRefactoring.execute(refactorContext, params)

                                    when (result) {
                                        is RefactorResult.Success -> {
                                            val applier = WorkspaceEditApplier(
                                                documentProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.document else null
                                                },
                                                undoManagerProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.undoManager else null
                                                },
                                                onFileModified = { _ -> }
                                            )
                                            applier.apply(result.edit)
                                        }
                                        is RefactorResult.Error -> { }
                                        else -> { }
                                    }
                                } catch (e: Exception) {
                                    System.err.println("[EditorTabComponent] Extract variable apply failed: ${e.message}")
                                }
                            }
                        },
                        onCancel = {
                            showExtractVariableDialog = false
                        },
                        onValidate = { variableName ->
                            when {
                                variableName.isBlank() -> "Variable name cannot be empty"
                                !variableName.first().isLetter() && variableName.first() != '_' -> "Variable name must start with a letter or underscore"
                                !variableName.all { it.isLetterOrDigit() || it == '_' } -> "Variable name contains invalid characters"
                                else -> null
                            }
                        }
                    )
                }

                // Extract method dialog
                if (showExtractMethodDialog && extractMethodCode.isNotEmpty()) {
                    ExtractMethodDialog(
                        suggestedName = extractMethodSuggestedName,
                        selectedCode = extractMethodCode,
                        onExtract = { methodName, visibility, makeStatic ->
                            showExtractMethodDialog = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val selection = extractMethodSelection
                                    if (selection == null) return@launch

                                    val refactorContext = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )

                                    val params = ExtractMethodParams(
                                        methodName = methodName,
                                        visibility = visibility,
                                        makeStatic = makeStatic
                                    )

                                    val result = extractMethodRefactoring.execute(refactorContext, params)
                                    when (result) {
                                        is RefactorResult.Success -> {
                                            val applier = WorkspaceEditApplier(
                                                documentProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.document else null
                                                },
                                                undoManagerProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.undoManager else null
                                                },
                                                onFileModified = { _ -> }
                                            )
                                            applier.apply(result.edit)
                                        }
                                        is RefactorResult.Error -> { }
                                        else -> { }
                                    }
                                } catch (e: Exception) {
                                    System.err.println("[EditorTabComponent] Extract method apply failed: ${e.message}")
                                }
                            }
                        },
                        onCancel = {
                            showExtractMethodDialog = false
                        },
                        onValidate = { methodName ->
                            when {
                                methodName.isBlank() -> "Method name cannot be empty"
                                !methodName.first().isLetter() && methodName.first() != '_' -> "Method name must start with a letter or underscore"
                                !methodName.all { it.isLetterOrDigit() || it == '_' } -> "Method name contains invalid characters"
                                else -> null
                            }
                        }
                    )
                }

                // Go to line dialog
                if (showGoToLineDialog) {
                    GoToLineDialog(
                        currentLine = cursorLine,
                        totalLines = editorState.document.lineCount,
                        currentColumn = cursorColumn,
                        onGoTo = { line, column ->
                            // Convert to 0-based
                            val targetLine = (line - 1).coerceAtLeast(0)
                            val targetColumn = (column - 1).coerceAtLeast(0)
                            editorState.moveCaret(EditorPosition(targetLine, targetColumn))
                            scrollLineIntoView(targetLine)
                            showGoToLineDialog = false
                        },
                        onDismiss = {
                            showGoToLineDialog = false
                        }
                    )
                }

                // Large file limitations dialog
                if (showLargeFileDialog && isLargeFile) {
                    LargeFileLimitationsDialog(
                        fileName = filePath.substringAfterLast('/'),
                        fileSize = formatSize(fileSizeBytes),
                        onDismiss = {
                            showLargeFileDialog = false
                        },
                        onOpenInEditor = {
                            // Already showing in editor (read-only mode)
                            showLargeFileDialog = false
                        }
                    )
                }

            }  // End Box
            }  // End editor visibility if

                // Markdown preview pane (Split or Preview mode), rendered via BrowserService
                if (isMarkdown && viewMode != MarkdownViewMode.EDIT) {
                    MarkdownPreviewPane(
                        browserService = context.browserService,
                        markdown = markdownText,
                        baseDir = filePath.substringBeforeLast('/', projectPath),
                        darkTheme = editorTheme.isDark,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        allowedRoot = projectPath,
                        // A link to a neighbouring file opens in BOSS rather than being
                        // handed to the OS, so nothing the link names can be launched.
                        // The host picks the surface by filename: a browser tab for
                        // images and PDFs, the notebook editor for .ipynb, this editor
                        // for everything else — including another .md, which then gets
                        // its own preview.
                        onOpenLocalFile = { path ->
                            context.splitViewOperations?.openFileInActivePanel(
                                path,
                                // File().name, not substringAfterLast('/'): these paths
                                // are canonical, so on Windows they are backslash-
                                // separated and splitting on '/' would make the whole
                                // path the tab title.
                                java.io.File(path).name,
                            )
                        }
                    )
                }
            }  // End Row

            // Status bar (matches bundled editor)
            EditorStatusBar(
                filePath = filePath,
                language = language,
                line = cursorLine,
                column = cursorColumn,
                isModified = isModified,
                isSaving = isSaving,
                error = saveError,
                diskNotice = diskNotice,
                viewMode = if (isMarkdown) viewMode else null,
                onViewModeChange = { newMode ->
                    viewMode = newMode
                    markdownSettingsManager.recordSelectedView(newMode)
                }
            )
        }
    }

    companion object {
        /**
         * Extracts the word (identifier) at the given column position in a line.
         */
        private fun extractWordAtPosition(line: String, column: Int): String {
            if (line.isEmpty() || column < 0 || column > line.length) return ""

            // Find the start of the word
            var start = column
            while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) {
                start--
            }

            // Find the end of the word
            var end = column
            while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) {
                end++
            }

            return if (start < end) line.substring(start, end) else ""
        }

        /**
         * Detects the language for a file path.
         *
         * Delegates to [LanguageDetection], which lives outside this Compose
         * component so the mapping can be unit-tested without loading
         * `ComponentContext` (a `compileOnly` dependency absent from tests).
         */
        internal fun detectLanguage(filePath: String): String = LanguageDetection.detect(filePath)

        /**
         * A read-only BossEditor wired exactly as the editor tab wires its own:
         * same lexer, same [TokenCache] for multi-line state, same theme and
         * settings, minimap on.
         *
         * Shared rather than reimplemented so the diff cannot drift from the
         * editor - a second highlighter is precisely what moving the diff tab
         * into this plugin exists to avoid.
         */
        @Composable
        internal fun DiffEditorSurface(
            state: EditorState,
            filePath: String,
            context: PluginContext,
            showMinimap: Boolean,
            readOnly: Boolean = true,
            showLineNumbers: Boolean = true,
        ) {
            val settings by PluginEditorSettings.settings.collectAsState()
            val language = remember(filePath) { detectLanguage(filePath) }
            val lexer = remember(language) { getLexerForLanguage(language) }
            val tokenCache = remember(lexer, state.document) {
                lexer?.let { TokenCache(state.document, it) }
            }
            DisposableEffect(tokenCache) { onDispose { tokenCache?.dispose() } }

            val tokenProvider: (Int) -> List<EditorToken> = remember(tokenCache) {
                { lineNumber -> EditorToken.fromTokens(tokenCache?.getLineTokens(lineNumber) ?: emptyList()) }
            }
            val composeFontFamily = FontFamily.Monospace
            val hostTheme = rememberHostEditorTheme()
            val editorTheme = remember(settings.followHostTheme, settings.themeName, hostTheme) {
                resolveEditorTheme(settings.followHostTheme, settings.themeName, hostTheme)
            }

            BossEditor(
                state = state,
                modifier = Modifier.fillMaxSize(),
                theme = editorTheme,
                fontFamily = composeFontFamily,
                fontSize = settings.fontSize,
                lineSpacing = settings.lineSpacing,
                showLineNumbers = settings.showLineNumbers && showLineNumbers,
                highlightCurrentLine = !readOnly,
                readOnly = readOnly,
                filePath = filePath,
                projectPath = context.projectPath,
                // One minimap for the pair, on the right: two rulers of the
                // same file is noise, and the right side is the "after" state
                // a reader is navigating.
                showMinimap = showMinimap && settings.showMinimap,
                minimapWidth = settings.minimapWidth,
                minimapUseEditorColors = settings.minimapUseEditorColors,
                tokenProvider = tokenProvider,
            )
        }

        /** Lexer for [language], or null for plain text. See [LanguageDetection]. */
        internal fun getLexerForLanguage(language: String): BaseLexer? = LanguageDetection.lexerFor(language)
    }
}

@Composable
private fun DialogAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = LocalEditorTheme.current.colors
    // The same blue LargeFileLimitationsDialog uses, so the two editor dialogs agree.
    val accent = Color(0xFF3B82F6)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (primary) accent else colors.gutterBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (primary) Color.White else colors.text,
            fontSize = 13.sp
        )
    }
}

/**
 * Status bar for the editor showing file info, cursor position, and save status.
 */
@Composable
private fun EditorStatusBar(
    filePath: String,
    language: String,
    line: Int,
    column: Int,
    isModified: Boolean,
    isSaving: Boolean,
    error: String?,
    diskNotice: String? = null,
    viewMode: MarkdownViewMode? = null,
    onViewModeChange: (MarkdownViewMode) -> Unit = {}
) {
    // Not a fixed strip any more: this used to be VS Code's #007ACC blue with white
    // text, the loudest thing in an amber or a light window. It follows the host
    // while the editor does, and the editor theme once a fixed theme is chosen -
    // otherwise a Dracula canvas in a light window would get a light bar welded to
    // it, which is the same seam this bridge exists to remove, just inverted.
    val bar = statusBarColors(hostChromeTokens(), LocalEditorTheme.current)
    // The hairline is drawn inside the 24.dp rather than stacked above it, so the bar
    // occupies exactly the height it always did.
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(bar.fill)
                .drawBehind {
                    drawRect(
                        color = bar.border,
                        size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
                    )
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: File info
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File name with modification indicator
                val fileName = filePath.substringAfterLast('/').ifEmpty { "Untitled" }
                Text(
                    text = if (isModified) "$fileName *" else fileName,
                    color = bar.primary,
                    fontSize = 12.sp
                )

                // Language
                Text(
                    text = language.uppercase(),
                    color = bar.secondary,
                    fontSize = 11.sp
                )

                // Something happened to the file that the tab could not just absorb: it was
                // deleted, or it is too large to swap in place.
                if (diskNotice != null) {
                    Text(
                        text = diskNotice,
                        color = bar.accent,
                        fontSize = 11.sp
                    )
                }
            }

            // Right: Cursor position and status
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Markdown view-mode toggle (only shown for markdown files)
                if (viewMode != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MarkdownViewMode.entries.forEach { mode ->
                            val active = mode == viewMode
                            Text(
                                text = mode.displayName,
                                color = if (active) bar.accent else bar.muted,
                                fontSize = 11.sp,
                                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold
                                             else androidx.compose.ui.text.font.FontWeight.Normal,
                                modifier = Modifier.clickable { onViewModeChange(mode) }
                            )
                        }
                    }
                }

                // Error message
                if (error != null) {
                    Text(
                        text = error,
                        color = bar.error,
                        fontSize = 11.sp
                    )
                }

                // Saving indicator
                if (isSaving) {
                    Text(
                        text = "Saving...",
                        color = bar.secondary,
                        fontSize = 11.sp
                    )
                }

                // Cursor position
                Text(
                    text = "Ln $line, Col $column",
                    color = bar.secondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Keeps the inline-edit card attached to the active edge of the captured selection while the
 * prompt owns focus. EditorCanvas and this overlay use the same visual-line and scroll metrics.
 */
@Composable
private fun AnchoredAiInlineEditBar(
    session: AiInlineEditService.Session,
    editorState: EditorState,
    editorSurfaceSize: IntSize,
    fallbackLineHeight: Float,
    anchorLine: Int? = null,
    anchorCol: Int? = null,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    val viewport by editorState.visibleViewport.collectAsState()
    val scrollOffset by editorState.scrollOffset.collectAsState()
    val visualLineMapper by editorState.visualLineMapper.collectAsState()
    val resolvedAnchorLine = anchorLine ?: session.anchorLine
    val resolvedAnchorCol = anchorCol ?: session.anchorCol
    var popupSize by remember(resolvedAnchorLine, resolvedAnchorCol) { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val marginPx = with(density) { AI_INLINE_MARGIN.roundToPx() }
    val gapPx = with(density) { AI_INLINE_GAP.roundToPx() }

    val lineHeight = viewport.lineHeight.takeIf { it > 0f } ?: fallbackLineHeight
    val charWidth = viewport.charWidth.takeIf { it > 0f } ?: 8f
    val gutterWidth = viewport.gutterWidth.takeIf { it > 0f } ?: 60f
    val visualLine = visualLineMapper.documentToVisual(resolvedAnchorLine)
    val anchorX = gutterWidth + resolvedAnchorCol * charWidth - scrollOffset.x
    val anchorY = if (visualLine >= 0) visualLine * lineHeight - scrollOffset.y else marginPx.toFloat()
    val placement = placeAiInlineEdit(
        containerWidth = editorSurfaceSize.width,
        containerHeight = editorSurfaceSize.height,
        popupWidth = popupSize.width,
        popupHeight = popupSize.height,
        anchorX = anchorX,
        anchorY = anchorY,
        lineHeight = lineHeight,
        margin = marginPx,
        gap = gapPx,
    )

    AiInlineEditBar(
        session = session,
        onPromptChange = onPromptChange,
        onSubmit = onSubmit,
        onAccept = onAccept,
        onCancel = onCancel,
        modifier = Modifier
            .offset { IntOffset(placement.x, placement.y) }
            .onSizeChanged { popupSize = it },
    )
}

private val AI_INLINE_MARGIN = 8.dp
private val AI_INLINE_GAP = 4.dp

// ========== Settings ==========

/**
 * Settings data class matching the bosseditor EditorSettings format exactly, so
 * both halves read and write one editor-settings.json under the BOSS data root.
 */
@Serializable
data class PluginEditorSettingsData(
    // Visual Settings
    val fontFamily: String? = null,
    val fontSize: Float = 14f,
    val lineSpacing: Float = 1.2f,
    val themeName: String = "Dark",
    // Whether to take colors from the host theme instead of [themeName]. On by
    // default, and absent from any settings file written before it existed, so an
    // existing install starts following the host rather than staying on the "Dark"
    // its file records. Mirrors bosseditor's EditorSettings.followHostTheme - both
    // read the same editor-settings.json, so the defaults must agree.
    val followHostTheme: Boolean = true,
    val showLineNumbers: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    // Behavior Settings
    val scrollSpeed: Float = 1.5f,
    val tabSize: Int = 4,
    val useSpacesForTabs: Boolean = true,
    val wordWrap: Boolean = false,
    // Feature Settings
    val foldingEnabled: Boolean = true,
    val rainbowBracketsEnabled: Boolean = true,
    val indentGuidesEnabled: Boolean = true,
    val bracketMatchingEnabled: Boolean = true,
    val markOccurrencesEnabled: Boolean = true,
    // Caret Settings
    val caretBlinkRate: Int = 530,
    val caretStyle: String = "line",
    // Minimap Settings
    // false, matching bosseditor's own default: with true here the settings panel
    // showed the toggle off while a tab rendered a minimap anyway. Found by the test
    // that compares this mirror against EditorSettings property by property.
    val showMinimap: Boolean = false,
    val minimapWidth: Int = 80,
    val minimapUseEditorColors: Boolean = true,
    val minimapBackgroundColor: String? = null,
    val minimapForegroundColor: String? = null
)

/**
 * Reactive settings manager that reads editor-settings.json from the BOSS data
 * root (the same file the bundled bosseditor library writes).
 *
 * Provides a StateFlow that updates when settings change, matching the
 * bundled editor's EditorSettingsManager behavior.
 */
/**
 * Where the settings live: the same file the bundled bosseditor writes, resolved the
 * same way rather than hardcoded to `~/.boss`. A dev host keeps its data under
 * `~/.boss_debug`, so the settings panel was writing to a file no editor tab watched.
 *
 * [resolve] is a parameter so both branches are testable. The guard matters because
 * this runs during `object` init, where a throw becomes an
 * `ExceptionInInitializerError` that poisons every later read, not just the first -
 * and the fallback is loud because it reinstates exactly the split above.
 */
internal fun resolveSettingsFile(
    resolve: (String) -> File = { BossDirectories.resolve(it) },
): File = runCatching { resolve("editor-settings.json") }
    .getOrElse { error ->
        System.err.println("editor-tab: BOSS data root unavailable ($error), using ~/.boss")
        File(System.getProperty("user.home"), ".boss/editor-settings.json")
    }

object PluginEditorSettings {
    private val settingsFile = resolveSettingsFile()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _settings = kotlinx.coroutines.flow.MutableStateFlow(loadFromFile())
    val settings: kotlinx.coroutines.flow.StateFlow<PluginEditorSettingsData> = _settings

    private var lastModified: Long = settingsFile.lastModified()

    private var watcherJob: kotlinx.coroutines.Job? = null

    /**
     * The 500ms file poll, on a scope the plugin owns.
     *
     * It used to run on GlobalScope, where nothing could ever cancel it - the
     * loop held this plugin's classloader for the life of the JVM after
     * unload. The plugin starts it in register() and stops it in dispose().
     */
    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        watcherJob?.cancel()
        watcherJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(500) // Check every 500ms
                try {
                    val currentModified = settingsFile.lastModified()
                    if (currentModified != lastModified) {
                        lastModified = currentModified
                        val newSettings = loadFromFile()
                        _settings.value = newSettings
                    }
                } catch (e: Exception) {
                    // Ignore errors during file watch
                }
            }
        }
    }

    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
    }

    private fun loadFromFile(): PluginEditorSettingsData {
        return try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                json.decodeFromString<PluginEditorSettingsData>(content)
            } else {
                PluginEditorSettingsData()
            }
        } catch (e: Exception) {
            PluginEditorSettingsData()
        }
    }
}

// ========== Color Parsing Helper ==========

/**
 * Parses a hex color string (ARGB format like "FF1E1F22") to a Compose Color.
 * Returns null if the string is invalid.
 * Matches bundled BossEditorIntegration exactly.
 */
private fun parseHexColor(hex: String): Color? {
    return try {
        val cleanHex = hex.removePrefix("#").removePrefix("0x")
        when (cleanHex.length) {
            6 -> {
                // RGB format - add full alpha
                val color = cleanHex.toLong(16)
                Color(
                    red = ((color shr 16) and 0xFF).toInt() / 255f,
                    green = ((color shr 8) and 0xFF).toInt() / 255f,
                    blue = (color and 0xFF).toInt() / 255f,
                    alpha = 1f
                )
            }
            8 -> {
                // ARGB format
                val color = cleanHex.toLong(16)
                Color(
                    alpha = ((color shr 24) and 0xFF).toInt() / 255f,
                    red = ((color shr 16) and 0xFF).toInt() / 255f,
                    green = ((color shr 8) and 0xFF).toInt() / 255f,
                    blue = (color and 0xFF).toInt() / 255f
                )
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

// ========== Semantic Analysis ==========

/**
 * Plugin-owned semantic analysis. Writes directly into the bundled BossEditor's
 * SemanticCache — the same singleton the editor's tokenProvider reads — so no
 * host bridge is needed (the host no longer has a PSI stack to bridge from).
 */
private val semanticTokens = PluginSemanticTokenProvider()

// ========== Semantic Highlighting Helpers ==========
// These functions are copied from BossEditorIntegration.kt to provide
// the same PSI-based semantic highlighting as the bundled editor.

/**
 * Gets semantic tokens for a specific line from the PSI-based SemanticCache.
 *
 * @param document The editor document
 * @param filePath The current file path
 * @param lineNumber The line number (0-based)
 * @return List of semantic tokens for the line, empty if not available
 */
private fun getSemanticTokensForLine(
    document: ai.rever.bosseditor.core.EditorDocument,
    filePath: String,
    lineNumber: Int
): List<Token> {
    // Only Kotlin files have semantic highlighting
    if (!filePath.endsWith(".kt") && !filePath.endsWith(".kts")) {
        return emptyList()
    }

    // Get all semantic elements for this file from the cache
    val allElements = SemanticCache.get(filePath) ?: return emptyList()
    if (allElements.isEmpty()) return emptyList()

    // Get the line range in the document
    if (lineNumber < 0 || lineNumber >= document.lineCount) {
        return emptyList()
    }
    val lineStart = document.getLineStartOffset(lineNumber)
    val lineEnd = document.getLineEndOffset(lineNumber)

    // Filter elements that fall within this line
    val lineElements = allElements.filter { element ->
        element.startOffset >= lineStart && element.endOffset <= lineEnd
    }

    if (lineElements.isEmpty()) return emptyList()

    // Convert SemanticElements to Tokens with line-relative offsets
    return lineElements.map { element ->
        Token(
            startOffset = element.startOffset - lineStart,
            endOffset = element.endOffset - lineStart,
            type = mapSemanticType(element.type)
        )
    }.sortedBy { it.startOffset }
}

/**
 * Maps PSI SemanticType to BossEditor TokenType.
 */
private fun mapSemanticType(type: SemanticType): TokenType = when (type) {
    SemanticType.FUNCTION_CALL -> TokenType.FUNCTION_CALL
    SemanticType.PROPERTY_ACCESS -> TokenType.PROPERTY
    SemanticType.CLASS_REFERENCE -> TokenType.TYPE
    SemanticType.OBJECT_REFERENCE -> TokenType.VARIABLE
    SemanticType.PARAMETER -> TokenType.PARAMETER
    SemanticType.LOCAL_VARIABLE -> TokenType.LOCAL_VARIABLE
    SemanticType.ANNOTATION -> TokenType.ANNOTATION
    SemanticType.LABEL -> TokenType.LABEL
    SemanticType.TYPE_PARAMETER -> TokenType.TYPE_PARAMETER
}

/**
 * Merges lexer tokens with semantic tokens, where semantic tokens take precedence.
 *
 * @param base Lexer-based tokens (always available)
 * @param overlay Semantic tokens from PSI analysis
 * @return Merged token list
 */
private fun mergeTokens(base: List<Token>, overlay: List<Token>): List<Token> {
    if (base.isEmpty()) return overlay
    if (overlay.isEmpty()) return base

    val result = mutableListOf<Token>()
    var baseIndex = 0
    var overlayIndex = 0

    while (baseIndex < base.size || overlayIndex < overlay.size) {
        // If no more overlay tokens, add remaining base tokens
        if (overlayIndex >= overlay.size) {
            result.addAll(base.subList(baseIndex, base.size))
            break
        }

        // If no more base tokens, add remaining overlay tokens
        if (baseIndex >= base.size) {
            result.addAll(overlay.subList(overlayIndex, overlay.size))
            break
        }

        val baseToken = base[baseIndex]
        val overlayToken = overlay[overlayIndex]

        when {
            // Base token comes completely before overlay - keep it
            baseToken.endOffset <= overlayToken.startOffset -> {
                result.add(baseToken)
                baseIndex++
            }

            // Overlay token comes completely before base - add it
            overlayToken.endOffset <= baseToken.startOffset -> {
                result.add(overlayToken)
                overlayIndex++
            }

            // Tokens overlap - overlay takes precedence
            else -> {
                // Add part of base before overlay (if any)
                if (baseToken.startOffset < overlayToken.startOffset) {
                    result.add(
                        Token(
                            baseToken.startOffset,
                            overlayToken.startOffset,
                            baseToken.type,
                            baseToken.modifiers
                        )
                    )
                }

                // Add overlay token
                result.add(overlayToken)

                // Handle remaining part of base token
                if (baseToken.endOffset > overlayToken.endOffset) {
                    // Create remaining part after overlay
                    val remaining = Token(
                        overlayToken.endOffset,
                        baseToken.endOffset,
                        baseToken.type,
                        baseToken.modifiers
                    )
                    overlayIndex++
                    // Check if remaining part overlaps with next overlay
                    if (overlayIndex < overlay.size &&
                        remaining.startOffset < overlay[overlayIndex].startOffset
                    ) {
                        val nextOverlay = overlay[overlayIndex]
                        if (remaining.endOffset <= nextOverlay.startOffset) {
                            result.add(remaining)
                        } else {
                            result.add(
                                Token(
                                    remaining.startOffset,
                                    nextOverlay.startOffset,
                                    remaining.type,
                                    remaining.modifiers
                                )
                            )
                        }
                    } else if (overlayIndex >= overlay.size) {
                        result.add(remaining)
                    }
                    baseIndex++
                } else {
                    // Base token completely covered by overlay
                    baseIndex++
                    if (overlayToken.endOffset >= base.getOrNull(baseIndex)?.startOffset ?: Int.MAX_VALUE) {
                        // Overlay covers next base token too
                    } else {
                        overlayIndex++
                    }
                }
            }
        }
    }

    return result.sortedBy { it.startOffset }
}

// ========== Run Gutter ==========

/**
 * Represents a detected main function in the code.
 * Matches the bundled DetectedMainFunction structure exactly.
 */
data class DetectedMainFunction(
    val lineNumber: Int,        // 0-based line number
    val functionName: String,   // Name of the function (e.g., "main")
    val filePath: String,       // Path to the file
    val language: String,       // Programming language
    val packageName: String? = null,  // Package name (for Kotlin/Java/Go)
    val className: String? = null     // Class name (for Java)
) {
    /**
     * Creates a display name for this detected function.
     */
    fun toDisplayName(): String {
        return when {
            className != null && packageName != null -> "$packageName.$className.$functionName"
            className != null -> "$className.$functionName"
            packageName != null -> "$packageName.$functionName"
            else -> functionName
        }
    }

    /**
     * Creates a short display name for UI.
     * Matches bundled DetectedMainFunction.toShortName() exactly.
     */
    fun toShortName(): String {
        val fileName = filePath.substringAfterLast('/')
        return when {
            className != null -> "$className.$functionName ($fileName)"
            else -> "$functionName ($fileName)"
        }
    }

    /**
     * Creates a short name with project info.
     * Matches bundled DetectedMainFunction.toShortNameWithProject() exactly.
     */
    fun toShortNameWithProject(projectRoot: String?): String {
        val fileName = filePath.substringAfterLast('/')
        val projectName = projectRoot?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

        val nameWithFile = when {
            className != null -> "$className.$functionName"
            else -> functionName
        }

        return if (projectName != null) {
            "$nameWithFile ($fileName [$projectName])"
        } else {
            "$nameWithFile ($fileName)"
        }
    }

    /**
     * Converts the language string to LanguageData enum.
     * Matches bundled DetectedMainFunction.language (Language enum).
     */
    fun toLanguageData(): ai.rever.boss.plugin.api.LanguageData {
        return when (language.lowercase()) {
            "kotlin", "kt", "kts" -> ai.rever.boss.plugin.api.LanguageData.KOTLIN
            "java" -> ai.rever.boss.plugin.api.LanguageData.JAVA
            "python", "py" -> ai.rever.boss.plugin.api.LanguageData.PYTHON
            "javascript", "js", "jsx", "mjs" -> ai.rever.boss.plugin.api.LanguageData.JAVASCRIPT
            "typescript", "ts", "tsx" -> ai.rever.boss.plugin.api.LanguageData.TYPESCRIPT
            "go", "golang" -> ai.rever.boss.plugin.api.LanguageData.GO
            "rust", "rs" -> ai.rever.boss.plugin.api.LanguageData.RUST
            else -> ai.rever.boss.plugin.api.LanguageData.UNKNOWN
        }
    }
}

/**
 * Main function detector and command generator.
 * Matches the bundled DesktopMainFunctionDetector logic exactly.
 */
object MainFunctionDetector {

    /**
     * Whether we're running on Windows.
     */
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Command separator for chaining commands.
     * - Unix/macOS/Linux: && (run second command only if first succeeds)
     * - Windows PowerShell: ; (sequential execution)
     */
    private val commandSeparator: String = if (isWindows) "; " else " && "

    /**
     * Escape a string for safe use in shell commands.
     * Uses single quotes and escapes embedded single quotes with '\''
     * This prevents command injection attacks from malicious file paths.
     * Matches bundled DesktopMainFunctionDetector.shellEscape exactly.
     */
    private fun shellEscape(str: String): String {
        // Single quotes prevent all shell expansion except for single quotes themselves
        // To include a single quote: end the string, add escaped quote, start new string
        // e.g., "it's" becomes 'it'\''s'
        return "'" + str.replace("'", "'\\''") + "'"
    }

    /**
     * Chain multiple commands together using platform-appropriate separator.
     * Matches bundled ShellUtils.chainCommands exactly.
     */
    private fun chainCommands(vararg commands: String): String {
        return commands.joinToString(commandSeparator)
    }

    /**
     * Public interface - finds project root from a file path.
     * Matches bundled DesktopMainFunctionDetector.findProjectRoot exactly.
     */
    fun findProjectRoot(filePath: String): String {
        val fileDir = File(filePath).parentFile
        return findProjectRootInternal(fileDir)?.absolutePath ?: fileDir?.absolutePath ?: filePath
    }

    /**
     * Find the project root by walking up the directory tree looking for project markers.
     * Matches bundled DesktopMainFunctionDetector.findProjectRootInternal exactly.
     * Markers: gradlew, build.gradle.kts, pom.xml, Cargo.toml, package.json, .git
     */
    private fun findProjectRootInternal(startDir: File?): File? {
        var current = startDir
        while (current != null && current.exists()) {
            // Check for Gradle project (prioritize this)
            if (File(current, "gradlew").exists() || File(current, "gradlew.bat").exists()) {
                return current
            }
            // Check for standalone Gradle build file
            if (File(current, "build.gradle.kts").exists() || File(current, "build.gradle").exists()) {
                // Only use this if no gradlew found above - might be a submodule
                if (current.parentFile?.let { findProjectRootInternal(it) } == null) {
                    return current
                }
            }
            // Check for Maven project
            if (File(current, "pom.xml").exists()) {
                return current
            }
            // Check for Cargo project
            if (File(current, "Cargo.toml").exists()) {
                return current
            }
            // Check for Node.js project
            if (File(current, "package.json").exists()) {
                return current
            }
            // Check for Git root (last resort)
            if (File(current, ".git").exists()) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    /**
     * Generate run command for detected main function.
     * Matches bundled DesktopMainFunctionDetector.generateCommand exactly.
     */
    fun generateCommand(detected: DetectedMainFunction, projectPath: String): String {
        val projectDir = File(findProjectRoot(detected.filePath))

        return when (detected.language.lowercase()) {
            "kotlin", "kt", "kts" -> generateKotlinCommand(detected, projectDir)
            "java" -> generateJavaCommand(detected, projectDir)
            "python", "py" -> "python3 ${shellEscape(detected.filePath)}"
            "go", "golang" -> "go run ${shellEscape(detected.filePath)}"
            "rust", "rs" -> generateRustCommand(detected, projectDir)
            "javascript", "js" -> "node ${shellEscape(detected.filePath)}"
            "typescript", "ts" -> "npx ts-node ${shellEscape(detected.filePath)}"
            else -> "echo 'Unknown language: ${detected.language}'"
        }
    }

    /**
     * Check if project has Gradle wrapper.
     * Matches bundled DesktopMainFunctionDetector.hasGradleWrapper exactly.
     */
    private fun hasGradleWrapper(projectDir: File): Boolean {
        return File(projectDir, "gradlew").exists() ||
               File(projectDir, "gradlew.bat").exists()
    }

    private fun generateKotlinCommand(detected: DetectedMainFunction, projectDir: File): String {
        val filePath = detected.filePath

        // For .kts scripts, use kotlinc -script
        if (filePath.endsWith(".kts")) {
            return "kotlinc -script ${shellEscape(filePath)}"
        }

        // For Gradle projects, use ./gradlew :moduleName:run
        if (hasGradleWrapper(projectDir)) {
            val moduleName = detectModuleName(filePath, projectDir)
            if (moduleName != null) {
                return "./gradlew :$moduleName:run"
            }
            // Root project run task as fallback
            return "./gradlew run"
        }

        // Fallback: compile and run with kotlinc (for simple standalone files)
        val jarName = File(filePath).nameWithoutExtension.replace("'", "_")
        val compileCmd = "kotlinc ${shellEscape(filePath)} -include-runtime -d ${shellEscape("/tmp/$jarName.jar")}"
        val runCmd = "java -jar ${shellEscape("/tmp/$jarName.jar")}"
        return chainCommands(compileCmd, runCmd)
    }

    private fun generateJavaCommand(detected: DetectedMainFunction, projectDir: File): String {
        val filePath = detected.filePath

        // For Gradle projects, use ./gradlew :moduleName:run
        if (hasGradleWrapper(projectDir)) {
            val moduleName = detectModuleName(filePath, projectDir)
            if (moduleName != null) {
                return "./gradlew :$moduleName:run"
            }
            // Root project run task as fallback
            return "./gradlew run"
        }

        // For Maven projects, use mvn exec:java
        if (File(projectDir, "pom.xml").exists()) {
            val className = buildClassName(detected)
            // Class names are validated by compiler, so they should be safe
            return "mvn exec:java -Dexec.mainClass=${shellEscape(className)}"
        }

        // Fallback: Java 11+ single-file source-code execution
        return "java ${shellEscape(filePath)}"
    }

    private fun generateRustCommand(detected: DetectedMainFunction, projectDir: File): String {
        val filePath = detected.filePath

        // For Cargo projects
        if (File(projectDir, "Cargo.toml").exists()) {
            // Check if it's in a workspace member
            val moduleName = detectCargoModule(filePath, projectDir)
            if (moduleName != null) {
                return "cargo run -p ${shellEscape(moduleName)}"
            }
            return "cargo run"
        }

        // Fallback: Compile and run the specific Rust file directly
        val outputName = File(filePath).nameWithoutExtension.replace("'", "_")
        val compileCmd = "rustc ${shellEscape(filePath)} -o ${shellEscape("/tmp/$outputName")}"
        val runCmd = shellEscape("/tmp/$outputName")
        return chainCommands(compileCmd, runCmd)
    }

    /**
     * Build the fully qualified class name from detected function info.
     */
    private fun buildClassName(detected: DetectedMainFunction): String {
        val pkg = detected.packageName
        val cls = detected.className
        return when {
            pkg != null && cls != null -> "$pkg.$cls"
            cls != null -> cls
            else -> "Main"
        }
    }

    private fun detectModuleName(filePath: String, projectDir: File): String? {
        // Use File API to properly handle path separators on all platforms
        val file = File(filePath)
        val projectPath = projectDir.absolutePath

        // Get relative path using File API (handles both / and \ properly)
        val relativePath = file.absolutePath.removePrefix(projectPath)
            .removePrefix(File.separator)
            .removePrefix("/") // Remove Unix separator if present
            .removePrefix("\\") // Remove Windows separator if present

        // Pattern: moduleName/src/... (split by platform separator)
        val parts = relativePath.split(File.separator, "/", "\\")
            .filter { it.isNotEmpty() } // Remove empty parts

        if (parts.size >= 2 && parts[1] == "src") {
            val potentialModule = parts[0]
            // Verify it's a valid module by checking for build.gradle(.kts)
            val moduleDir = File(projectDir, potentialModule)
            if (moduleDir.isDirectory &&
                (File(moduleDir, "build.gradle.kts").exists() || File(moduleDir, "build.gradle").exists())) {
                return potentialModule
            }
        }

        return null
    }

    /**
     * Detect Cargo workspace member name from file path.
     */
    private fun detectCargoModule(filePath: String, projectDir: File): String? {
        // Use File API to properly handle path separators on all platforms
        val file = File(filePath)
        val projectPath = projectDir.absolutePath

        // Get relative path using File API (handles both / and \ properly)
        val relativePath = file.absolutePath.removePrefix(projectPath)
            .removePrefix(File.separator)
            .removePrefix("/") // Remove Unix separator if present
            .removePrefix("\\") // Remove Windows separator if present

        // Pattern: crate-name/src/... (split by platform separator)
        val parts = relativePath.split(File.separator, "/", "\\")
            .filter { it.isNotEmpty() } // Remove empty parts

        if (parts.size >= 2 && parts[1] == "src") {
            val potentialCrate = parts[0]
            // Verify it's a valid crate by checking for Cargo.toml
            val crateDir = File(projectDir, potentialCrate)
            if (crateDir.isDirectory && File(crateDir, "Cargo.toml").exists()) {
                return potentialCrate
            }
        }

        return null
    }

    // Regex patterns matching bundled DesktopMainFunctionDetector exactly
    private val KOTLIN_MAIN_PATTERN = Regex(
        """^\s*(?:@JvmStatic\s+)?fun\s+main\s*\(""",
        RegexOption.MULTILINE
    )
    private val KOTLIN_PACKAGE_PATTERN = Regex(
        """^\s*package\s+([\w.]+)""",
        RegexOption.MULTILINE
    )

    private val JAVA_MAIN_PATTERN = Regex(
        """^\s*public\s+static\s+void\s+main\s*\(\s*String\s*\[?\s*\]?\s*\w*\s*\)""",
        RegexOption.MULTILINE
    )
    private val JAVA_CLASS_PATTERN = Regex(
        """^\s*(?:public\s+)?class\s+(\w+)""",
        RegexOption.MULTILINE
    )
    private val JAVA_PACKAGE_PATTERN = Regex(
        """^\s*package\s+([\w.]+)\s*;""",
        RegexOption.MULTILINE
    )

    private val PYTHON_MAIN_PATTERN = Regex(
        """^if\s+__name__\s*==\s*['""]__main__['""]""",
        RegexOption.MULTILINE
    )

    private val GO_MAIN_PATTERN = Regex(
        """^\s*func\s+main\s*\(\s*\)""",
        RegexOption.MULTILINE
    )
    private val GO_PACKAGE_MAIN_PATTERN = Regex(
        """^\s*package\s+main\b""",
        RegexOption.MULTILINE
    )

    private val RUST_MAIN_PATTERN = Regex(
        """^\s*fn\s+main\s*\(\s*\)""",
        RegexOption.MULTILINE
    )

    private val C_MAIN_PATTERN = Regex(
        """^\s*(int|void)\s+main\s*\(""",
        RegexOption.MULTILINE
    )

    fun detectInContent(content: String, language: String, filePath: String): List<DetectedMainFunction> {
        return when (language.lowercase()) {
            "kotlin", "kt", "kts" -> detectKotlinMain(filePath, content)
            "java" -> detectJavaMain(filePath, content)
            "python", "py" -> detectPythonMain(filePath, content)
            "go", "golang" -> detectGoMain(filePath, content)
            "rust", "rs" -> detectRustMain(filePath, content)
            "c", "cpp", "c++" -> detectCMain(filePath, content, language)
            else -> emptyList()
        }
    }

    private fun detectKotlinMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        // Extract package name
        val packageMatch = KOTLIN_PACKAGE_PATTERN.find(content)
        val packageName = packageMatch?.groupValues?.get(1)

        lines.forEachIndexed { index, line ->
            if (KOTLIN_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "kotlin",
                    packageName = packageName,
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectJavaMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        // Extract package and class names
        val packageMatch = JAVA_PACKAGE_PATTERN.find(content)
        val packageName = packageMatch?.groupValues?.get(1)

        val classMatch = JAVA_CLASS_PATTERN.find(content)
        val className = classMatch?.groupValues?.get(1)

        lines.forEachIndexed { index, line ->
            if (JAVA_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "java",
                    packageName = packageName,
                    className = className
                ))
            }
        }

        return results
    }

    private fun detectPythonMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (PYTHON_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "__main__",
                    filePath = filePath,
                    language = "python",
                    packageName = null,
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectGoMain(filePath: String, content: String): List<DetectedMainFunction> {
        // Go requires both 'package main' and 'func main()'
        if (!GO_PACKAGE_MAIN_PATTERN.containsMatchIn(content)) {
            return emptyList()
        }

        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (GO_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "go",
                    packageName = "main",
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectRustMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (RUST_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "rust",
                    packageName = null,
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectCMain(filePath: String, content: String, language: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (C_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = language,
                    packageName = null,
                    className = null
                ))
            }
        }

        return results
    }
}

/**
 * Run gutter component that displays play buttons next to main functions.
 * Matches the bundled BossEditorRunGutter behavior exactly.
 */
@Composable
private fun EditorRunGutter(
    detectedMainFunctions: List<DetectedMainFunction>,
    editorState: EditorState,
    fontSize: Float,
    fontFamily: FontFamily,
    lineSpacing: Float,
    onRun: (DetectedMainFunction) -> Unit,
    modifier: Modifier = Modifier
) {
    // Collect scroll offset from editor state
    val scrollOffset by editorState.scrollOffset.collectAsState()
    // Collect visual line mapper for folding support
    val visualLineMapper by editorState.visualLineMapper.collectAsState()
    // The canvas measures itself and publishes the result here; prefer it over the estimate below
    val viewport by editorState.visibleViewport.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Fallback line height, for the frames before the canvas has measured itself
    val textMeasurer = rememberTextMeasurer()
    val estimatedLineHeightPx = remember(fontSize, fontFamily, lineSpacing) {
        val style = TextStyle(
            fontFamily = fontFamily,
            fontSize = fontSize.sp
        )
        textMeasurer.measure("M", style).size.height.toFloat() * lineSpacing
    }

    val metrics = scrollMetrics(viewport, estimatedLineHeightPx)

    // Convert pixel height to dp for sizing
    val lineHeightDp = with(density) { metrics.lineHeight.toDp() }

    Box(modifier = modifier) {
        // Render run icons for detected main functions that are actually on screen.
        //
        // There is no document-line pre-filter here on purpose. The old one windowed on a
        // hardcoded 50 lines - fewer than a tall pane shows, so icons near the bottom were
        // dropped - and it compared a *visual* first-visible-line against *document* line
        // numbers, which diverge as soon as a fold above them is collapsed. The offset
        // calculation below is the honest test, and this list holds a handful of entries.
        detectedMainFunctions.forEach { detected ->
            // lineNumber from detector is 0-based document line; the mapper converts it to
            // the visual line actually painted (or -1 when a collapsed fold hides it)
            val visualLine = visualLineMapper.documentToVisual(detected.lineNumber)
            val yOffsetPx = gutterIconOffsetPx(visualLine, metrics, scrollOffset.y) ?: return@forEach

            Box(
                modifier = Modifier
                    // Use pixel-based offset to match EditorCanvas rendering
                    .offset { IntOffset(0, yOffsetPx.toInt()) }
                    .height(lineHeightDp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                GutterRunIcon(
                    detected = detected,
                    onRun = onRun,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** IntelliJ's run green, which every curated editor theme is drawn around. */
private val CURATED_RUN_GREEN = Color(0xFF59A869)

/**
 * Run icon with hover effect matching IntelliJ style.
 */
@Composable
private fun GutterRunIcon(
    detected: DetectedMainFunction,
    onRun: (DetectedMainFunction) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Same rule as the status bar, read off the resolved theme: the host's ok token
    // while the editor follows the host, the curated green once a fixed theme is
    // chosen. Taking the host token unconditionally painted a green tuned for a light
    // window onto a Dracula gutter.
    val theme = LocalEditorTheme.current
    val floor = theme.colors.gutterBackground
    val base = if (followsHostTheme(theme)) BossThemeColors.SuccessColor else CURATED_RUN_GREEN
    // Remembered: gutter icons recompose on every scroll frame, and only the hover
    // lift below has to follow the pointer.
    val tint = remember(base, floor, theme.colors.text) {
        runIconTint(base, floor, theme.colors.text)
    }
    val iconColor = if (isHovered) mix(tint, theme.colors.text, 0.25f) else tint

    Icon(
        imageVector = FeatherIcons.Play,
        contentDescription = "Run ${detected.functionName}",
        tint = iconColor,
        modifier = modifier
            .size(20.dp)
            .hoverable(interactionSource)
            .clickable { onRun(detected) }
    )
}

/**
 * What a key press on the editor does, decided by [editorShortcutFor].
 *
 * Pure data: the composable matches on this and executes, so the ORDERING of
 * the dispatch (which shortcut wins on which key) lives in one tested
 * function instead of being spread across a `when` inside the UI - where a
 * branch condition that matches too broadly silently makes every later
 * branch dead code.
 */
internal sealed interface EditorKeyAction {
    /** Delete [range] (the Mac line/word deletion shortcuts). */
    data class EditRange(val range: IntRange) : EditorKeyAction

    /** Move the caret to document offset [offset]. */
    data class MoveCaret(val offset: Int) : EditorKeyAction

    data object ShowFind : EditorKeyAction
    data object ShowFindReplace : EditorKeyAction
    data object GoToLine : EditorKeyAction
    data object Redo : EditorKeyAction
    data object InlineAiEdit : EditorKeyAction
    data object Save : EditorKeyAction
    data object FindNext : EditorKeyAction
    data object FindPrevious : EditorKeyAction
    data object CloseSearch : EditorKeyAction
}

/**
 * The editor's key dispatch, pure and testable.
 *
 * [editRange] and [caretTarget] are the pre-computed results of the two
 * document-copying helpers (each evaluated at most once per key by the
 * caller); the rest is decided from the key and modifiers alone.
 *
 * The two editing-shortcut results MUST stay the first branches: they are
 * the only ones that must win over the platform shortcuts on their keys.
 * Every later branch is a platform shortcut the bundled editor does not
 * implement (find, go-to-line, redo, inline AI edit, save, F3, escape).
 */
internal fun editorShortcutFor(
    key: Key,
    isMeta: Boolean,
    isInlineAiModifier: Boolean = isMeta,
    isShift: Boolean,
    isLargeFile: Boolean,
    showSearchBar: Boolean,
    editRange: IntRange?,
    caretTarget: Int?,
): EditorKeyAction? =
    when {
        editRange != null -> EditorKeyAction.EditRange(editRange)
        caretTarget != null -> EditorKeyAction.MoveCaret(caretTarget)
        isMeta && key == Key.F -> EditorKeyAction.ShowFind
        isMeta && key == Key.H -> EditorKeyAction.ShowFindReplace
        isMeta && (key == Key.G || key == Key.L) -> EditorKeyAction.GoToLine
        isMeta && key == Key.Y -> EditorKeyAction.Redo
        isInlineAiModifier && (key == Key.I || key == Key.K) && !isLargeFile -> EditorKeyAction.InlineAiEdit
        isMeta && key == Key.S && !isLargeFile -> EditorKeyAction.Save
        key == Key.F3 && !isShift -> EditorKeyAction.FindNext
        key == Key.F3 && isShift -> EditorKeyAction.FindPrevious
        key == Key.Escape && showSearchBar -> EditorKeyAction.CloseSearch
        else -> null
    }

/** macOS reserves Ctrl+I/K for Emacs-style editing; inline AI is Cmd there, Ctrl elsewhere. */
internal fun isMacPlatform(): Boolean =
    System.getProperty("os.name").contains("mac", ignoreCase = true)

/**
 * The range a deletion shortcut removes, or null when this key is not one.
 *
 * ⌘⌫ / ⌘⌦ act on the line, ⌥⌫ / ⌥⌦ on the word. Returning null for a no-op
 * (caret already at the boundary) matters: an empty edit would still cost an
 * undo step.
 *
 * The key/modifier is checked BEFORE copying the document: these run on
 * every key press, and the copy is the expensive part.
 */
private fun editingShortcutRange(
    event: androidx.compose.ui.input.key.KeyEvent,
    isCmd: Boolean,
    state: EditorState,
): IntRange? {
    val isDeleteKey = event.key == Key.Backspace || event.key == Key.Delete
    if (!(isCmd && isDeleteKey) && !event.isAltPressed) return null
    val text = state.document.getText()
    val caret = state.caretOffset
    return when {
        isCmd && event.key == Key.Backspace -> MacEditingShortcuts.deleteToLineStart(text, caret)
        isCmd && event.key == Key.Delete -> MacEditingShortcuts.deleteToLineEnd(text, caret)
        event.isAltPressed && event.key == Key.Backspace -> MacEditingShortcuts.deletePreviousWord(text, caret)
        event.isAltPressed && event.key == Key.Delete -> MacEditingShortcuts.deleteNextWord(text, caret)
        else -> null
    }
}

/**
 * The offset a caret-movement shortcut targets, or null when not one.
 *
 * Like [editingShortcutRange], the key is checked before the document copy.
 */
private fun caretShortcutTarget(
    event: androidx.compose.ui.input.key.KeyEvent,
    isCmd: Boolean,
    state: EditorState,
): Int? {
    if (event.isShiftPressed) return null // selection variants stay with the editor
    // The four arrow keys, checked without allocating a set per key event.
    val isDirection =
        event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
            event.key == Key.DirectionUp || event.key == Key.DirectionDown
    val isAltHorizontal =
        event.isAltPressed &&
            (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
    if (!(isCmd && isDirection) && !isAltHorizontal) return null
    val text = state.document.getText()
    val caret = state.caretOffset
    return when {
        isCmd && event.key == Key.DirectionLeft -> MacEditingShortcuts.lineStart(text, caret)
        isCmd && event.key == Key.DirectionRight -> MacEditingShortcuts.lineEnd(text, caret)
        isCmd && event.key == Key.DirectionUp -> 0
        isCmd && event.key == Key.DirectionDown -> text.length
        event.isAltPressed && event.key == Key.DirectionLeft -> MacEditingShortcuts.previousWordStart(text, caret)
        event.isAltPressed && event.key == Key.DirectionRight -> MacEditingShortcuts.nextWordEnd(text, caret)
        else -> null
    }
}
