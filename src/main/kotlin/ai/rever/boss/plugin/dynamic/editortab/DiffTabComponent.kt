package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.DiffTabConfig
import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.bosseditor.core.EditorState
import ai.rever.bosseditor.core.ScrollOffset
import ai.rever.bosseditor.features.GutterIcon
import ai.rever.bosseditor.features.GutterIconShape
import ai.rever.bosseditor.features.GutterIconType
import ai.rever.bosseditor.features.MinimapConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A git diff, rendered with the plugin's own editor.
 *
 * This is the point of moving the diff out of the host: each side is a real
 * [ai.rever.bosseditor.BossEditor], so syntax highlighting, the language
 * server's semantic tokens and the overview ruler are the editor's own and
 * cannot drift from the normal editor tab. The host's renderer drew every line
 * as a plain `Text` because BossEditor is bundled privately in this plugin and
 * unreachable from there.
 *
 * Two modes, as the host's renderer had: side-by-side (two editors, scroll
 * locked) and unified (ONE editor holding git's own line order). Side-by-side
 * by default in a wide pane, unified in a narrow one; the toggle in the status
 * bar wins once the user touches it.
 *
 * For a working-tree diff the right pane is the FILE - the same shared
 * [EditorBufferRegistry] buffer an editor tab would open - and is editable.
 * See [WorkingTreePane] for why that is safe and when it declines.
 *
 * The host still creates and persists the tab's config; it arrives here as a
 * [DiffTabConfig] (api 1.0.87), which is the read side of that contract.
 */
class DiffTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val context: PluginContext,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = DiffTabType

    private val scope: DiffTabConfig? = config as? DiffTabConfig

    @Composable
    override fun Content() {
        val diffScope = scope
        if (diffScope == null) {
            DiffMessage("This tab's configuration is not a diff.")
            return
        }

        var diffs by remember { mutableStateOf<List<GitDiffData>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(diffScope.filePath, diffScope.staged, diffScope.fromRef, diffScope.toRef) {
            diffs = null
            error = null
            val git = context.gitDataProvider
            if (git == null) {
                error = "Git is unavailable on this host build."
                return@LaunchedEffect
            }
            val from = diffScope.fromRef
            val to = diffScope.toRef
            val path = diffScope.filePath.ifBlank { null }
            diffs =
                runCatching<List<GitDiffData>> {
                    when {
                        from != null && to != null -> git.diffBetween(from, to, path)
                        from != null -> git.diffRef(from, path)
                        path != null -> git.diffFile(path, diffScope.staged)
                        else -> emptyList<GitDiffData>()
                    }
                }.onFailure { error = it.message ?: "Failed to load the diff" }.getOrNull()
        }

        when {
            error != null -> DiffMessage(error.orEmpty())
            diffs == null -> DiffLoading()
            diffs!!.isEmpty() -> DiffMessage("No changes to show")
            else -> MultiFileDiff(diffs!!, diffScope)
        }
    }

    /**
     * A commit or ref-range touches as many files as it touches.
     *
     * This used to render `diffs.first()` - one file's changes, presented as the
     * whole commit, with nothing on screen to say the rest existed. A picker rather
     * than a stack of panes: [DiffBody] owns a scroll, a status bar, a side-by-side
     * toggle and an edit mode, all of which assume one file, and N of those stacked
     * would be a worse reading experience than choosing one.
     *
     * A single-file diff renders exactly as before - no picker.
     */
    @Composable
    private fun MultiFileDiff(
        diffs: List<GitDiffData>,
        diffScope: DiffTabConfig,
    ) {
        if (diffs.size == 1) {
            DiffBody(diffs.first(), diffScope)
            return
        }
        var selected by remember(diffs) { mutableStateOf(0) }
        val current = diffs.getOrElse(selected) { diffs.first() }
        Column(modifier = Modifier.fillMaxSize()) {
            DiffFileStrip(
                diffs = diffs,
                selectedIndex = selected,
                onSelect = { selected = it },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DiffBody(current, diffScope)
            }
        }
    }

    /** The changed-file row for a multi-file diff: name, per-file stats, selection. */
    @Composable
    private fun DiffFileStrip(
        diffs: List<GitDiffData>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.surface)
                .horizontalScroll(scroll)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${diffs.size} files",
                fontSize = 10.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(end = 4.dp),
            )
            diffs.forEachIndexed { index, d ->
                val active = index == selectedIndex
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (active) {
                                MaterialTheme.colors.primary.copy(alpha = 0.16f)
                            } else {
                                Color.Transparent
                            },
                        ).clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d.path.substringAfterLast('/'),
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = MaterialTheme.colors.onSurface.copy(alpha = if (active) 0.95f else 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "+${d.additions} \u2212${d.deletions}",
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }

    @Composable
    private fun DiffBody(diff: GitDiffData, diffScope: DiffTabConfig) {
        val path = diffScope.filePath.ifBlank { diff.path }
        // null = follow the pane width; set once the user picks a side.
        var chosenSideBySide by remember(diff) { mutableStateOf<Boolean?>(null) }
        var saveNote by remember(diff) { mutableStateOf<String?>(null) }
        // Aligned by default: matching code facing matching code is what a
        // split diff is for. The padded document that gives it is nobody's
        // file, so it is read-only; editing is one explicit click away and the
        // status bar names the mode.
        var editing by remember(diff) { mutableStateOf(false) }
        var canEdit by remember(diff) { mutableStateOf(false) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sideBySide = chosenSideBySide ?: (maxWidth >= NARROW_PANE_WIDTH)
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (diff.isBinary) {
                        DiffMessage("Binary file - no textual diff")
                    } else if (diff.hunks.isEmpty()) {
                        DiffMessage("No line changes (mode or metadata only)")
                    } else if (sideBySide) {
                        SideBySidePanes(
                            diff = diff,
                            path = path,
                            diffScope = diffScope,
                            editing = editing,
                            onEditableChanged = { canEdit = it },
                            onNote = { saveNote = it },
                        )
                    } else {
                        UnifiedPane(diff, path)
                    }
                }
                DiffStatusBar(
                    fileName = path.substringAfterLast('/'),
                    language = EditorTabComponent.detectLanguage(path),
                    scope = when {
                        diffScope.fromRef != null && diffScope.toRef != null ->
                            "${diffScope.fromRef}...${diffScope.toRef}"
                        diffScope.fromRef != null -> diffScope.fromRef.orEmpty()
                        diffScope.staged -> "index"
                        else -> "working tree"
                    },
                    stats = "+${diff.additions} −${diff.deletions}",
                    note = saveNote,
                    sideBySide = sideBySide,
                    editing = editing,
                    canEdit = canEdit && sideBySide,
                    onToggleLayout = { chosenSideBySide = !sideBySide },
                    onToggleEditing = {
                        editing = !editing
                        saveNote = null
                    },
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Side by side
    // ═══════════════════════════════════════════════════════════════════════

    @Composable
    private fun SideBySidePanes(
        diff: GitDiffData,
        path: String,
        diffScope: DiffTabConfig,
        editing: Boolean,
        onEditableChanged: (Boolean) -> Unit,
        onNote: (String?) -> Unit,
    ) {
        val sides = remember(diff) { DiffSides.of(diff) }

        if (!editing) {
            // ALIGNED: filler rows keep matching code on the same row. Both
            // panes read-only - the padded document is not the file, and
            // nothing may ever write it back.
            val oldState = remember(sides.alignedOld.text) { EditorState(sides.alignedOld.text, null) }
            val newState = remember(sides.alignedNew.text) { EditorState(sides.alignedNew.text, null) }
            // Equal row counts, so the raw pixel offset already corresponds;
            // an empty map means "no translation".
            SyncScroll(oldState, newState, IntArray(0), IntArray(0))
            RevealFirstChange(newState, sides.alignedNew.marks)
            EditableProbe(diffScope, path, sides.newText, onEditableChanged)
            DiffRow(
                path = path,
                left = sides.alignedOld,
                right = sides.alignedNew,
                leftState = oldState,
                rightState = newState,
                overviewMarks = sides.alignedOverview,
                rightReadOnly = true,
            )
            return
        }

        // EDITING: unpadded documents. The right pane is the file itself, so
        // the rows no longer line up and the correspondence maps carry the
        // alignment to the scroll lock instead.
        val oldState = remember(sides.oldText) { EditorState(sides.oldText, null) }
        WorkingTreePane(diffScope, path, sides.newText, onNote, onEditableChanged) { newState, editable ->
            SyncScroll(oldState, newState, sides.oldToNew, sides.newToOld)
            RevealFirstChange(newState, sides.newMarks)
            DiffRow(
                path = path,
                left = sides.old,
                right = sides.new,
                leftState = oldState,
                rightState = newState,
                overviewMarks = sides.overviewMarks,
                rightReadOnly = !editable,
            )
        }
    }

    @Composable
    private fun DiffRow(
        path: String,
        left: DiffSides.Pane,
        right: DiffSides.Pane,
        leftState: EditorState,
        rightState: EditorState,
        overviewMarks: List<DiffSides.OverviewMark?>,
        rightReadOnly: Boolean,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            DiffPane(
                pane = left,
                path = path,
                state = leftState,
                showMinimap = false,
                readOnly = true,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.10f)),
            )
            DiffPane(
                pane = right,
                path = path,
                state = rightState,
                showMinimap = true,
                readOnly = rightReadOnly,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // Every change in the file, painted onto the minimap.
                overviewMarks = overviewMarks,
            )
        }
    }

    /** Report whether editing would be available, without entering that mode. */
    @Composable
    private fun EditableProbe(
        diffScope: DiffTabConfig,
        path: String,
        unpaddedNewText: String,
        onEditableChanged: (Boolean) -> Unit,
    ) {
        val absolutePath = remember(path) { absolutePathOf(path) }
        // [canEdit] hops its own dispatchers - the disk read to IO, the live
        // document comparison to Main - so this effect only has to ask the
        // question, once per key change while the pane is read-only.
        LaunchedEffect(absolutePath, unpaddedNewText, diffScope.staged, diffScope.fromRef, diffScope.toRef) {
            onEditableChanged(canEdit(diffScope, absolutePath, unpaddedNewText))
        }
    }

    /**
     * Resolve the editable state for the right pane, or fall back to read-only.
     *
     * Editing is offered ONLY for a working-tree diff: the index and a ref
     * range have no file you can meaningfully write to.
     *
     * The safety argument is that the editable document is never a
     * reconstruction. When editing is on, the pane is handed the shared
     * [EditorBufferRegistry] buffer for the path - literally the same
     * [EditorState] an editor tab on that file uses, so the two cannot fight,
     * edits are one undo history, and Cmd+S writes back the file's own text.
     * The diff-derived text is used only to CHECK that the buffer still
     * matches what the diff described; when it does not (a fragmentary diff, a
     * file changed underneath, a line-ending quirk) the pane goes read-only on
     * the reconstruction rather than editing something whose line numbering we
     * cannot vouch for.
     */
    @Composable
    private fun WorkingTreePane(
        diffScope: DiffTabConfig,
        path: String,
        reconstructedNewText: String,
        onNote: (String?) -> Unit,
        onEditableChanged: (Boolean) -> Unit,
        content: @Composable (state: EditorState, editable: Boolean) -> Unit,
    ) {
        val absolutePath = remember(path) { absolutePathOf(path) }

        // Acquire and release are keyed IDENTICALLY, on the slot. The acquire
        // has to be asynchronous (a whole-file read must not run on the
        // composition thread), so the two halves cannot be one
        // DisposableEffect - and anything looser leaks. Keying the release on
        // the RESOLVED buffer, which is what this used to do, failed twice
        // over: a cancellation landing after the acquire but before the
        // assignment left a reference no effect was ever keyed on, and a
        // re-resolve to the same EditorBuffer instance (the class has no
        // equals) acquired a second time without re-running the release.
        // [BufferSlot] closes both - it releases into an already-disposed slot
        // at once, and it holds at most one reference at a time.
        val slot = remember(
            absolutePath, reconstructedNewText,
            diffScope.staged, diffScope.fromRef, diffScope.toRef,
        ) { BufferSlot() }
        var buffer by remember(slot) { mutableStateOf<EditorBuffer?>(null) }
        DisposableEffect(slot) { onDispose { slot.dispose() } }
        LaunchedEffect(slot) {
            // NonCancellable spans the resolve AND the publish: a cancellation
            // between them is precisely the orphaned reference above. The cost
            // is that a tab closed mid-read waits out one file read, which
            // happens off the UI thread anyway.
            buffer = withContext(NonCancellable) {
                slot.publish(editableBufferOrNull(diffScope, absolutePath, reconstructedNewText))
            }
        }
        LaunchedEffect(buffer) { onEditableChanged(buffer != null) }

        val fallback = remember(reconstructedNewText) { EditorState(reconstructedNewText, null) }
        val state = buffer?.editorState ?: fallback
        val editable = buffer != null

        if (editable && absolutePath != null) {
            val editableBuffer = buffer ?: return
            Column(modifier = Modifier.fillMaxSize()) {
                ExternalChangeBar(
                    buffer = editableBuffer,
                    onReload = {
                        ExternalChangeWatcher.current()?.resolveByReloading(editableBuffer)
                        onNote(null)
                    },
                    onKeepMine = {
                        ExternalChangeWatcher.current()?.resolveByKeepingMine(editableBuffer)
                        onNote(null)
                    },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SaveOnCommandS(state, absolutePath, onNote) { content(state, true) }
                }
            }
        } else {
            content(state, false)
        }
    }

    /**
     * The file on disk, when the diff's post-image still matches it - the
     * invariant that makes editing safe. A fragmentary diff, a file changed
     * underneath, or a line-ending quirk all read back unequal, and so does a
     * file that vanished in the meantime (runCatching -> null).
     */
    private fun diskMatchingReconstruction(
        absolutePath: String,
        reconstructedNewText: String,
    ): String? =
        runCatching { File(absolutePath).readText() }.getOrNull()
            ?.takeIf { it.lines() == reconstructedNewText.lines() }

    /** What a buffer for this pane would hold, and the language to open it with. */
    private class EditableSource(val seedText: String, val language: String)

    /**
     * Whether this diff can hand over an editable buffer, and what that buffer
     * would contain - resolved WITHOUT taking a registry reference.
     *
     * The scope gates come first (only a working-tree diff has a file you can
     * write to), then the content gate: [EditorBufferRegistry.acquire] may
     * hand back a buffer an editor tab already owns, and the pane edits
     * exactly that document, marks and gutter numbering included. When it has
     * diverged from the diff's post-image the pane must decline.
     *
     * Threading: the buffer's document is Compose-backed state the user may be
     * typing into, so every read of it happens on Main. Reading it from
     * Dispatchers.IO raced those keystrokes - the same mistake
     * [EditorTabPluginAPIImpl] calls out on the apply side. Only
     * `File.readText()` belongs on IO, and it runs at most once, only when no
     * buffer exists yet: with an editor tab open the live buffer text IS the
     * invariant to check, in memory.
     */
    private suspend fun editableSourceOrNull(
        diffScope: DiffTabConfig,
        absolutePath: String?,
        reconstructedNewText: String,
    ): EditableSource? {
        if (absolutePath == null) return null
        if (diffScope.staged || diffScope.fromRef != null || diffScope.toRef != null) return null
        if (diffScope.filePath.isBlank()) return null

        val live =
            withContext(Dispatchers.Main) {
                EditorBufferRegistry.find(absolutePath)?.let {
                    it.editorState.document.getText() to it.language
                }
            }
        if (live != null) {
            val (text, language) = live
            return if (text.lines() == reconstructedNewText.lines()) EditableSource(text, language) else null
        }

        return withContext(Dispatchers.IO) {
            diskMatchingReconstruction(absolutePath, reconstructedNewText)
                ?.let { EditableSource(it, EditorTabComponent.detectLanguage(absolutePath)) }
        }
    }

    /**
     * The read-only mode's gate: the SAME predicate the editable pane uses,
     * asked without acquiring anything.
     *
     * Two hand-rolled copies of "this diff can edit this file" could drift
     * apart (one checking disk, the other the buffer), which is exactly the
     * class of bug this file already shipped. The previous shape shared the
     * predicate but reached its Boolean by acquire-then-release, which for a
     * file nothing has open BUILT a full EditorBuffer over the whole file -
     * briefly visible to the change watcher - once per key change.
     */
    private suspend fun canEdit(
        diffScope: DiffTabConfig,
        absolutePath: String?,
        reconstructedNewText: String,
    ): Boolean = editableSourceOrNull(diffScope, absolutePath, reconstructedNewText) != null

    /**
     * The buffer the editable right pane writes into, or null when the pane
     * must fall back to the read-only reconstruction.
     *
     * The acquire re-checks the invariant on Main: another viewport may have
     * created - or dirtied - the buffer for this path since
     * [editableSourceOrNull] resolved, in which case the registry ignores the
     * seed text and hands back that document instead.
     */
    private suspend fun editableBufferOrNull(
        diffScope: DiffTabConfig,
        absolutePath: String?,
        reconstructedNewText: String,
    ): EditorBuffer? {
        val path = absolutePath ?: return null
        val source = editableSourceOrNull(diffScope, path, reconstructedNewText) ?: return null
        return withContext(Dispatchers.Main) {
            val buffer = EditorBufferRegistry.acquire(path, source.seedText, source.language)
            if (buffer.editorState.document.getText().lines() == reconstructedNewText.lines()) {
                buffer
            } else {
                EditorBufferRegistry.release(buffer.path)
                null
            }
        }
    }

    /** Cmd/Ctrl+S over the diff pane, writing the shared buffer back to disk. */
    @Composable
    private fun SaveOnCommandS(
        state: EditorState,
        absolutePath: String,
        onNote: (String?) -> Unit,
        content: @Composable () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    val meta = event.isMetaPressed || event.isCtrlPressed
                    if (event.type == KeyEventType.KeyDown && meta && event.key == Key.S) {
                        if (state.isModified.value) {
                            scope.launch {
                                val result =
                                    EditorBufferRegistry.find(absolutePath)?.let {
                                        saveEditorDocument(
                                            it,
                                            context.editorContentProvider?.let { provider -> provider::writeFileContent },
                                        )
                                    } ?: DocumentSaveResult.UNAVAILABLE
                                onNote(if (result == DocumentSaveResult.SAVED) "Saved" else result.message)
                            }
                        }
                        true
                    } else {
                        false
                    }
                },
        ) {
            content()
        }
    }

    private fun absolutePathOf(path: String): String? {
        if (path.isBlank()) return null
        // isAbsolute, not startsWith("/"): on Windows the drive-letter form
        // (C:\...) is absolute too.
        if (File(path).isAbsolute) return path
        val project = context.projectPath?.takeIf { it.isNotBlank() } ?: return null
        return "${project.trimEnd('/')}/$path"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Unified
    // ═══════════════════════════════════════════════════════════════════════

    @Composable
    private fun UnifiedPane(diff: GitDiffData, path: String) {
        val unified = remember(diff) { DiffUnified.of(diff) }
        // Always read-only: this document interleaves both versions, so it is
        // nobody's file.
        val state = remember(unified.text) { EditorState(unified.text, null) }
        RevealFirstChange(state, unified.marks)
        DiffPane(
            pane = DiffSides.Pane(
                text = unified.text,
                marks = unified.marks,
                inline = unified.inline,
                // Git's own numbers: the new side's, or the old one for a
                // removed line. The interleaved document's row index would be
                // neither file's line number.
                lineNumbers = unified.lineNumbers,
                filler = emptySet(),
            ),
            path = path,
            state = state,
            showMinimap = true,
            readOnly = true,
            modifier = Modifier.fillMaxSize(),
            overviewMarks = remember(unified.marks) { overviewOf(unified.marks) },
        )
    }

    /**
     * One editor with its change marking: a colour strip beside it, a
     * translucent band over each changed line, and - where there is a minimap -
     * the whole diff painted onto it.
     *
     * BossEditor exposes no per-line decoration parameter that can express
     * this. It does take `gutterIcons`/`gutterDecorationProviders`, but those
     * draw a small shape in the LINE-NUMBER gutter (see
     * `EditorCanvasRenderer.drawGutterIconForLine`) - one icon per line,
     * competing with the fold indicators, and nothing at all on the minimap.
     * `searchMatches` would paint in the find colour and fight a real search.
     * So the marking is an overlay: it sits AFTER the editor in the Box, so it
     * draws on top, and `Canvas` is a `Spacer` with a draw modifier that takes
     * no pointer input, so scrolling and selection still reach the editor.
     */
    @Composable
    private fun DiffPane(
        pane: DiffSides.Pane,
        path: String,
        state: EditorState,
        showMinimap: Boolean,
        readOnly: Boolean,
        modifier: Modifier,
        overviewMarks: List<DiffSides.OverviewMark?>? = null,
    ) {
        Row(modifier = modifier) {
            DiffLineNumbers(pane, state)
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                EditorTabComponent.DiffEditorSurface(
                    state = state,
                    filePath = path,
                    context = context,
                    showMinimap = showMinimap,
                    readOnly = readOnly,
                    // Our gutter, not the editor's: a filler row is not a line
                    // of any file and must not take a number, and the editor
                    // numbers every row it draws.
                    showLineNumbers = false,
                )
                DiffLineTint(pane, state)
                if (pane.filler.isNotEmpty()) DiffFiller(pane.filler, state)
                if (showMinimap && overviewMarks != null) {
                    DiffMinimapOverview(overviewMarks, state)
                }
            }
        }
    }

    /**
     * The line-number gutter, drawn by us rather than by the editor.
     *
     * Two things the editor's own gutter cannot do. It numbers every row it
     * draws, so the filler rows that make the panes line up would take numbers
     * of their own and shift every number after them - the aligned view would
     * show the wrong line numbers on both sides. And it cannot draw a `+`/`-`
     * glyph at all: `GutterIcon` carries a `customText` that
     * `EditorCanvasRenderer.drawGutterIconForLine` never reads, only
     * `customColor` and `customShape`.
     *
     * So the numbers come from git's own `oldLine`/`newLine` - correct even for
     * a fragmentary commit diff, where counting rows would not be - and a
     * filler row simply draws nothing.
     */
    @Composable
    private fun DiffLineNumbers(
        pane: DiffSides.Pane,
        state: EditorState,
    ) {
        val settings by PluginEditorSettings.settings.collectAsState()
        val viewport by state.visibleViewport.collectAsState()
        val scroll by state.scrollOffset.collectAsState()
        val mapper by state.visualLineMapper.collectAsState()
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current

        val style = remember(settings.fontSize) {
            TextStyle(fontSize = settings.fontSize.sp, fontFamily = FontFamily.Monospace)
        }
        val digits = remember(pane.lineNumbers) {
            (pane.lineNumbers.filterNotNull().maxOrNull() ?: 1).toString().length
        }
        // Measured, not estimated: the column has to be exactly wide enough for
        // the widest number plus the glyph, at whatever font size is set.
        val metrics = remember(digits, style) {
            val numberWidth = measurer.measure(AnnotatedString("0".repeat(digits)), style).size.width
            val glyphWidth = measurer.measure(AnnotatedString("+"), style).size.width
            Triple(
                numberWidth.toFloat(),
                glyphWidth.toFloat(),
                with(density) { (numberWidth + glyphWidth + GUTTER_PAD * 3).toDp() },
            )
        }
        val (numberWidth, glyphWidth, columnWidth) = metrics

        Canvas(
            modifier = Modifier
                .width(columnWidth)
                .fillMaxHeight()
                .background(DiffGutterBackground),
        ) {
            val lineHeight = viewport.lineHeight
            if (lineHeight <= 0f) return@Canvas
            val numberRight = GUTTER_PAD + numberWidth
            val glyphLeft = numberRight + GUTTER_PAD

            val first = (scroll.y / lineHeight).toInt().coerceAtLeast(0)
            val last = first + (size.height / lineHeight).toInt() + 1
            for (visual in first..last) {
                if (visual >= mapper.visibleLineCount) break
                val row = mapper.visualToDocument(visual)
                val top = visual * lineHeight - scroll.y
                val kind = pane.marks.getOrNull(row)

                pane.lineNumbers.getOrNull(row)?.let { number ->
                    val laid = measurer.measure(AnnotatedString(number.toString()), style)
                    drawText(
                        textLayoutResult = laid,
                        color = if (kind == null) DiffGutterNumber else DiffGutterNumberChanged,
                        // Right-aligned, the way the editor aligns its own.
                        topLeft = Offset(
                            numberRight - laid.size.width,
                            top + (lineHeight - laid.size.height) / 2f,
                        ),
                    )
                }

                if (kind != null) {
                    val laid = measurer.measure(
                        AnnotatedString(if (kind == DiffLineKind.ADDED) "+" else "\u2212"),
                        style,
                    )
                    drawText(
                        textLayoutResult = laid,
                        color = bandColor(kind).copy(alpha = DIFF_GLYPH_ALPHA),
                        topLeft = Offset(
                            glyphLeft + (glyphWidth - laid.size.width) / 2f,
                            top + (lineHeight - laid.size.height) / 2f,
                        ),
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scrolling
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lock the two panes onto the same code.
     *
     * The port mirrored `visibleViewport.firstVisibleLine`, an integer line:
     * sub-line scrolling never moved the other pane, and when the line did tick
     * over, `scrollToLine` snapped the follower to a line boundary, whose new
     * pixel offset then dragged the leader back - the "scroll down and it goes
     * up" that made the tab unusable.
     *
     * Mirroring the raw pixel offset fixes that but assumes the two documents
     * have the same lines, which stopped being true when the padding went. So
     * the offset is translated through the correspondence map: same remainder,
     * translated line.
     *
     * `EditorState.scrollOffset` is BossEditor's single scroll source of truth
     * (the wheel, the scrollbars and the minimap all write it, and
     * `EditorCanvas` derives the viewport from it), so writing it is exactly
     * what VS Code's diff widget does with `setScrollTop`.
     */
    @Composable
    private fun SyncScroll(
        left: EditorState,
        right: EditorState,
        leftToRight: IntArray,
        rightToLeft: IntArray,
    ) {
        // What this sync last WROTE into each pane. An emission equal to it is
        // our own echo, not a user scroll. That is VS Code's `_isUpdatingScroll`
        // guard, expressed by value rather than by a boolean held across the
        // write: the two collectors resume asynchronously, so a flag set around
        // `setScrollOffset` would already be back to false when the echo lands.
        // By value it also makes the translation's asymmetry harmless - the
        // follower's echo never re-enters, so nothing can round-trip.
        val echo = remember(left, right) { mutableMapOf<EditorState, ScrollOffset>() }

        suspend fun mirror(from: EditorState, to: EditorState, map: IntArray) {
            from.scrollOffset.collect { source ->
                if (echo.remove(from) == source) return@collect
                val lineHeight = from.visibleViewport.value.lineHeight
                    .takeIf { it > 0f } ?: to.visibleViewport.value.lineHeight
                val mirrored = ScrollOffset(
                    // x carries over untouched: same font, same columns.
                    source.x,
                    DiffGeometry.mirroredScrollY(source.y, lineHeight, map),
                )
                if (to.scrollOffset.value == mirrored) return@collect
                echo[to] = mirrored
                to.setScrollOffset(mirrored)
            }
        }

        LaunchedEffect(left, right) { mirror(left, right, leftToRight) }
        LaunchedEffect(left, right) { mirror(right, left, rightToLeft) }
    }

    /**
     * Scroll to the first changed line once the editor has been laid out.
     *
     * `scrollToLine`'s third argument is the VIEWPORT HEIGHT, not a duration:
     * it centres the line in that height. Both it and `lineHeight` are zero
     * until the canvas has measured, hence waiting on the first real viewport.
     */
    @Composable
    private fun RevealFirstChange(state: EditorState, marks: List<DiffLineKind?>) {
        LaunchedEffect(state, marks) {
            val target = DiffGeometry.firstChangedLine(marks) ?: return@LaunchedEffect
            val viewport = state.visibleViewport.first { it.lineHeight > 0f && it.viewportHeight > 0f }
            state.scrollToLine(target, viewport.lineHeight, viewport.viewportHeight)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Change marking
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * A translucent band over each changed line, inside the editor.
     *
     * The editor paints its own background, so the tint goes ON TOP at a low
     * alpha - enough to read the shape of the change at a glance without
     * washing out the syntax colours underneath. It starts at the editor's own
     * gutter width so the line numbers stay legible.
     */
    @Composable
    private fun DiffLineTint(
        pane: DiffSides.Pane,
        state: EditorState,
    ) {
        val viewport by state.visibleViewport.collectAsState()
        val scroll by state.scrollOffset.collectAsState()
        val mapper by state.visualLineMapper.collectAsState()
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = viewport.gutterWidth.coerceIn(0f, size.width)
            // Stop at the editor canvas, not at the Box: to the right of it sit
            // the scrollbar and the minimap, and a change band washing over
            // those reads as a rendering fault.
            // The full text area, the way the editor's own current-line
            // highlight runs: from the gutter to the end of the line region,
            // stopping at the minimap rather than washing over it.
            val right = DiffGeometry.textAreaRight(left, viewport.viewportWidth, size.width)
            val bands = DiffGeometry.bands(
                marks = pane.marks,
                scrollY = scroll.y.toFloat(),
                lineHeight = viewport.lineHeight,
                viewportHeight = size.height,
                visualLineCount = mapper.visibleLineCount,
                visualToDocument = { mapper.visualToDocument(it) },
            )
            for (band in bands) {
                drawRect(
                    color = bandColor(band.kind).copy(alpha = DIFF_TINT_ALPHA),
                    topLeft = Offset(left, band.topPx),
                    size = Size(right - left, band.heightPx),
                )
            }
            // The characters that actually changed, over the faint line band.
            // A flat line tint says "something here is different"; this says
            // what, which is the whole difference for a one-token edit.
            if (pane.inline.isEmpty() || viewport.lineHeight <= 0f) return@Canvas
            val firstVisual = (scroll.y / viewport.lineHeight).toInt().coerceAtLeast(0)
            val lastVisual = firstVisual + (size.height / viewport.lineHeight).toInt() + 1
            for (visual in firstVisual..lastVisual) {
                if (visual >= mapper.visibleLineCount) break
                val line = mapper.visualToDocument(visual)
                val ranges = pane.inline[line] ?: continue
                val kind = pane.marks.getOrNull(line) ?: continue
                val top = visual * viewport.lineHeight - scroll.y
                for (range in ranges) {
                    val rect = DiffGeometry.spanRect(
                        startColumn = range.first,
                        endColumn = range.last + 1,
                        charWidth = viewport.charWidth,
                        gutterWidth = left,
                        scrollX = scroll.x.toFloat(),
                        viewportWidth = right,
                    ) ?: continue
                    drawRect(
                        color = bandColor(kind).copy(alpha = DIFF_WORD_ALPHA),
                        topLeft = Offset(rect.first, top),
                        size = Size(rect.second, viewport.lineHeight),
                    )
                }
            }
        }
    }

    /**
     * Diagonal hatching over the filler rows of the aligned view.
     *
     * The rows exist only to keep the panes level; hatching is how every diff
     * viewer says "there is nothing here", and it has to look unlike an empty
     * line of real code, which is why it is not a flat wash.
     */
    @Composable
    private fun DiffFiller(
        filler: Set<Int>,
        state: EditorState,
    ) {
        val viewport by state.visibleViewport.collectAsState()
        val scroll by state.scrollOffset.collectAsState()
        val mapper by state.visualLineMapper.collectAsState()
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lineHeight = viewport.lineHeight
            if (lineHeight <= 0f) return@Canvas
            val left = viewport.gutterWidth.coerceIn(0f, size.width)
            val right = DiffGeometry.textAreaRight(left, viewport.viewportWidth, size.width)
            val first = (scroll.y / lineHeight).toInt().coerceAtLeast(0)
            val last = first + (size.height / lineHeight).toInt() + 1
            for (visual in first..last) {
                if (visual >= mapper.visibleLineCount) break
                if (mapper.visualToDocument(visual) !in filler) continue
                val top = visual * lineHeight - scroll.y
                drawRect(
                    color = DiffFillerBackground,
                    topLeft = Offset(left, top),
                    size = Size(right - left, lineHeight),
                )
                clipRect(left = left, top = top, right = right, bottom = top + lineHeight) {
                    var x = left - lineHeight
                    while (x < right) {
                        drawLine(
                            color = DiffFillerHatch,
                            start = Offset(x, top + lineHeight),
                            end = Offset(x + lineHeight, top),
                            strokeWidth = DIFF_HATCH_STROKE,
                            cap = StrokeCap.Butt,
                        )
                        x += DIFF_HATCH_SPACING
                    }
                }
            }
        }
    }

    /**
     * The whole diff, painted ON the minimap.
     *
     * The minimap already maps the entire document onto its height
     * (`Minimap.getViewportBounds` is `line * height / lineCount`), so the grey
     * code overview and these marks share one scale and the result reads as a
     * single map of the file.
     *
     * The width is COMPUTED, not assumed: BossEditor sizes its minimap
     * `coerceIn(longestLine * 1.5f, 50.dp, minimapWidth)`, so on a file with
     * short lines it is far narrower than the `minimapWidth` setting and a
     * fixed-width overlay would miss it. The result is then clamped to the
     * strip actually left of the pane's right edge (`box width - the editor
     * canvas's own width`), which is measured rather than derived - so if the
     * computation is ever wrong the overlay still lands on the minimap region
     * instead of over the code.
     */
    @Composable
    private fun BoxScope.DiffMinimapOverview(
        marks: List<DiffSides.OverviewMark?>,
        state: EditorState,
    ) {
        val settings by PluginEditorSettings.settings.collectAsState()
        val viewport by state.visibleViewport.collectAsState()
        val mapper by state.visualLineMapper.collectAsState()
        val document = state.document

        // Same scan MinimapRenderer.calculateOptimalWidth does.
        val longestLine = remember(document.documentVersion, mapper) {
            var longest = 0
            val scanned = minOf(mapper.visibleLineCount, DiffGeometry.MINIMAP_SCAN_LINES)
            for (visual in 0 until scanned) {
                val line = mapper.visualToDocument(visual)
                if (line < 0) continue
                val length = runCatching {
                    document.getLineEndOffset(line) - document.getLineStartOffset(line)
                }.getOrDefault(0)
                if (length > longest) longest = length
            }
            longest
        }
        val widthDp = DiffGeometry.minimapWidthDp(
            longestLineChars = longestLine,
            maxWidth = settings.minimapWidth.toFloat(),
        )

        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(widthDp.dp)
                .fillMaxHeight(),
        ) {
            // The minimap is the last thing in BossEditor's row, so it is
            // flush to this Box's right edge and this canvas sits exactly on
            // it.
            val lineCount = mapper.visibleLineCount
            for (band in DiffGeometry.overviewBands(
                marks = marks,
                height = size.height,
                // The minimap's own per-line height, not height/lineCount:
                // it only compresses once the file overflows the strip.
                lineHeight = DiffGeometry.minimapLineHeight(
                    height = size.height,
                    lineCount = lineCount,
                    configLineHeight = MinimapConfig().lineHeight,
                ),
                minBandPx = DIFF_OVERVIEW_MIN_BAND,
                lineCount = lineCount,
            )) {
                // A replacement is red AND green: split the band down the
                // middle rather than picking a winner, which is what dropped
                // the red from every replacement before.
                if (band.kind == DiffSides.OverviewMark.BOTH) {
                    val half = size.width / 2f
                    drawRect(
                        color = DiffRemoved.copy(alpha = DIFF_MINIMAP_ALPHA),
                        topLeft = Offset(0f, band.topPx),
                        size = Size(half, band.heightPx),
                    )
                    drawRect(
                        color = DiffAdded.copy(alpha = DIFF_MINIMAP_ALPHA),
                        topLeft = Offset(half, band.topPx),
                        size = Size(size.width - half, band.heightPx),
                    )
                } else {
                    drawRect(
                        color = overviewColor(band.kind).copy(alpha = DIFF_MINIMAP_ALPHA),
                        topLeft = Offset(0f, band.topPx),
                        size = Size(size.width, band.heightPx),
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Chrome
    // ═══════════════════════════════════════════════════════════════════════

    @Composable
    private fun DiffStatusBar(
        fileName: String,
        language: String,
        scope: String,
        stats: String,
        note: String?,
        sideBySide: Boolean,
        editing: Boolean,
        canEdit: Boolean,
        onToggleLayout: () -> Unit,
        onToggleEditing: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(Color(0xFF007ACC).copy(alpha = 0.8f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fileName, fontSize = 11.sp, color = Color.White, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(stats, fontSize = 11.sp, color = Color.White, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (note != null) {
                Text(note, fontSize = 11.sp, color = Color.White, maxLines = 1)
                Spacer(Modifier.width(10.dp))
            }
            if (canEdit) {
                // Named for what it costs: leaving "Aligned" is what gives up
                // the filler rows that keep the two sides level.
                Text(
                    text = if (editing) "Editing" else "Aligned",
                    fontSize = 11.sp,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.clickable { onToggleEditing() },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = if (sideBySide) "Split" else "Unified",
                fontSize = 11.sp,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.clickable { onToggleLayout() },
            )
            Spacer(Modifier.width(10.dp))
            Text(scope, fontSize = 11.sp, color = Color.White, maxLines = 1)
            if (language.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(language, fontSize = 11.sp, color = Color.White, maxLines = 1)
            }
        }
    }

    @Composable
    private fun DiffLoading() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.width(24.dp))
        }
    }

    @Composable
    private fun DiffMessage(text: String) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = text, fontSize = 13.sp, color = Color(0xFF9AA0A6))
        }
    }
}

/** Below this, two gutters and two code columns do not fit; unified instead. */
private val NARROW_PANE_WIDTH = 720.dp

/** `EditorCanvasRenderer` draws a gutter icon at this x, with this size. */
private const val GUTTER_PAD = 6

/** Muted: the marker is a margin note, not a badge. */
private const val DIFF_GLYPH_ALPHA = 0.8f

private const val DIFF_HATCH_SPACING = 7f
private const val DIFF_HATCH_STROKE = 1f


/** A one-line change in a long file must still be a visible mark. */
private const val DIFF_OVERVIEW_MIN_BAND = 2f

/** Low enough that syntax colours still read through the change band. */
private const val DIFF_TINT_ALPHA = 0.15f

/** Stronger than the line band: this is the part that actually changed. */
private const val DIFF_WORD_ALPHA = 0.30f


/** Higher than the line tint: the minimap's own content is faint. */
private const val DIFF_MINIMAP_ALPHA = 0.75f

private val DiffGutterBackground = Color.White.copy(alpha = 0.02f)
private val DiffGutterNumber = Color(0xFF6E7681)
private val DiffGutterNumberChanged = Color(0xFF9AA0A6)
private val DiffFillerBackground = Color.White.copy(alpha = 0.03f)
private val DiffFillerHatch = Color.White.copy(alpha = 0.10f)

private val DiffAdded = Color(0xFF3FB950)
private val DiffRemoved = Color(0xFFF85149)

private fun bandColor(kind: DiffLineKind): Color =
    if (kind == DiffLineKind.ADDED) DiffAdded else DiffRemoved

private fun overviewColor(kind: DiffSides.OverviewMark): Color =
    if (kind == DiffSides.OverviewMark.ADDED) DiffAdded else DiffRemoved

/**
 * The unified view's per-row marks as overview marks.
 *
 * No BOTH here: that document holds the removed lines and the added lines as
 * separate rows, so each row really is one or the other.
 */
private fun overviewOf(marks: List<DiffLineKind?>): List<DiffSides.OverviewMark?> =
    marks.map {
        when (it) {
            DiffLineKind.ADDED -> DiffSides.OverviewMark.ADDED
            DiffLineKind.REMOVED -> DiffSides.OverviewMark.REMOVED
            else -> null
        }
    }

/**
 * One buffer reference held by one diff pane, released exactly once.
 *
 * The pane acquires asynchronously - a whole-file read must not run on the
 * composition thread - but is disposed synchronously, so the acquire and the
 * dispose can land in either order. The slot makes both orders safe:
 * publishing into an already-disposed slot releases the reference on the
 * spot, and disposing releases whatever was published. Either way the
 * registry sees exactly one release per acquire, which is what keeps the
 * one-buffer-per-path refcount honest across a cancelled acquire.
 */
internal class BufferSlot {
    private var disposed = false
    private var held: EditorBuffer? = null

    /** True once [dispose] has run; every later [publish] is refused. */
    val isDisposed: Boolean
        @Synchronized get() = disposed

    /** The reference currently held, or null before a publish and after a dispose. */
    val current: EditorBuffer?
        @Synchronized get() = held

    /**
     * Take ownership of a freshly acquired [buffer].
     *
     * @return the buffer while the slot is live; null once it has been
     *   disposed - the reference is released here, so the caller is left
     *   holding nothing rather than something nobody will release.
     */
    @Synchronized
    fun publish(buffer: EditorBuffer?): EditorBuffer? {
        if (buffer == null) return null
        if (disposed) {
            EditorBufferRegistry.release(buffer.path)
            return null
        }
        // A second publish into a live slot would strand the first reference.
        // The pane re-keys the slot rather than reusing it, so this only
        // guards a future caller against getting that wrong.
        held?.let { EditorBufferRegistry.release(it.path) }
        held = buffer
        return buffer
    }

    /** Release the held reference, and refuse every later publish. */
    @Synchronized
    fun dispose() {
        disposed = true
        held?.let { EditorBufferRegistry.release(it.path) }
        held = null
    }
}
