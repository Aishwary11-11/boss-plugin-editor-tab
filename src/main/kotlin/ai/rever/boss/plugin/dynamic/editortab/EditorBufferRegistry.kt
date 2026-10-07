package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.BufferChange
import ai.rever.bosseditor.core.DocumentChange
import ai.rever.bosseditor.core.DocumentListener
import ai.rever.bosseditor.core.EditorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File

/**
 * One live editor buffer, shared by every tab/split viewport on the same
 * path (decision D3, boss-plugin-api 1.0.87).
 *
 * Invariants:
 * - ONE BUFFER PER PATH: two tabs on the same file are two viewports over
 *   this one [EditorState], never copies.
 * - MONOTONIC VERSION: [version] is the BossEditor document version, bumped
 *   on every document change; applyEdit-style writes fail stale when it has
 *   moved on.
 * - Changes are observable: [observe] emits one [BufferChange] (version only,
 *   per the api contract) per document change and completes when the last
 *   viewport releases the buffer.
 */
class EditorBuffer(
    val path: String,
    val editorState: EditorState,
    val language: String,
) {
    internal val saveMutex = Mutex()

    private val _changes =
        MutableSharedFlow<BufferChange>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val changes: SharedFlow<BufferChange> = _changes.asSharedFlow()

    var refCount = 0
        private set

    /**
     * What the file looked like the last time we read or wrote it.
     *
     * Owned by the buffer rather than by a tab because ONE buffer can be shown
     * by several viewports - an editor tab and the diff tab's editable pane -
     * and they must not each keep their own idea of the file's state. The
     * editor tab used to track this privately, which is why a save made from
     * the diff tab left the editor tab believing the file had changed
     * underneath it.
     */
    @Volatile
    internal var knownSignature: ExternalChangePolicy.FileSignature =
        ExternalChangePolicy.FileSignature.MISSING

    /**
     * The committed version of this file, cached.
     *
     * Held per buffer so the gutter can diff the LIVE text against HEAD on
     * every recompute without shelling out to git each time - git only ever
     * sees the file on disk, so a git-derived marking would sit at the wrong
     * lines the moment the user types.
     */
    @Volatile
    internal var headText: String? = null

    /** Set when something happened that could have moved HEAD or the index. */
    @Volatile
    internal var headStale: Boolean = true

    /** The document version the current git marks were computed from. */
    @Volatile
    internal var gitMarksVersion: Long = -1L

    private val _gitMarks = MutableStateFlow<Map<Int, LineDiff.Mark>>(emptyMap())

    /** Uncommitted changes, by 0-based line, for the editor's gutter. */
    internal val gitMarks: StateFlow<Map<Int, LineDiff.Mark>> = _gitMarks.asStateFlow()

    internal fun setGitMarks(marks: Map<Int, LineDiff.Mark>) {
        if (_gitMarks.value != marks) _gitMarks.value = marks
    }

    private val _externalState = MutableStateFlow(ExternalState.IN_SYNC)

    /** Whether the file underneath has diverged, for the UI to surface. */
    val externalState: StateFlow<ExternalState> = _externalState.asStateFlow()

    fun setExternalState(state: ExternalState) {
        _externalState.value = state
    }

    /**
     * Record that WE just wrote this file, so the watcher does not report our
     * own save as an external change.
     *
     * Belt and braces: the policy already compares content, so a save is
     * invisible to it anyway. This just saves the watcher a needless read.
     */
    fun noteWrittenByUs() {
        knownSignature = signatureOf(File(path))
        _externalState.value = ExternalState.IN_SYNC
        // A save changes what git sees, so the cached HEAD comparison has to be
        // taken again rather than waiting for the periodic refresh.
        headStale = true
    }

    /** The BossEditor document version: monotonic, bumped on every change. */
    val version: Long
        get() = editorState.document.documentVersion

    val content: String
        get() = editorState.document.getText()

    init {
        editorState.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(change: DocumentChange) {
                    // Fire-and-forget (tryEmit): with the extra buffer and
                    // DROP_OLDEST, a collector that falls behind simply skips
                    // changes - the api contract (consumers re-read through
                    // readBuffer) makes that safe.
                    _changes.tryEmit(BufferChange(path = path, version = change.newVersion))
                }
            },
        )
    }

    /**
     * A hot stream of this buffer's changes. Does NOT complete on its own - it is a
     * [SharedFlow], so collect it inside a scope you cancel when done (a tab's scope),
     * rather than relying on completion to tear the collector down.
     */
    fun observe(): Flow<BufferChange> = changes

    /**
     * Take/drop a reference. INTERNAL, and only ever called from the
     * [EditorBufferRegistry] wrappers: [refCount] is a plain Int, so the
     * registry's `@Synchronized` is the only thing making these safe. Keeping
     * them off the public surface makes that a compiler-enforced fact rather
     * than a convention for the next caller to discover.
     */
    internal fun acquire() {
        refCount++
    }

    /** @return true when the buffer became unreferenced and was closed. */
    internal fun release(): Boolean {
        refCount--
        return refCount <= 0
    }
}

/**
 * The plugin-wide buffer table: one [EditorBuffer] per normalized absolute
 * path. Editor tabs acquire/release through this in their lifecycle, and the
 * plugin-API implementation (EditorTabPluginAPIImpl) resolves paths against
 * it for readBuffer/applyEdit/observeChanges/focusedDocument.
 */
/** How a buffer stands relative to the file behind it. */
enum class ExternalState {
    IN_SYNC,

    /** The file changed AND the buffer has unsaved edits: the user must choose. */
    CONFLICT,

    /** The file is gone from disk. */
    DELETED,
}

/** Stat a file into the shape the policy compares. */
internal fun signatureOf(file: File): ExternalChangePolicy.FileSignature =
    runCatching {
        if (file.exists()) {
            ExternalChangePolicy.FileSignature(true, file.lastModified(), file.length())
        } else {
            ExternalChangePolicy.FileSignature.MISSING
        }
    }.getOrDefault(ExternalChangePolicy.FileSignature.MISSING)

object EditorBufferRegistry {
    private val buffers = HashMap<String, EditorBuffer>()

    private var focused: EditorBuffer? = null

    /**
     * Normalized absolute path key (resolves ".", expands "~", canonicalizes
     * separators).
     *
     * `File.absolutePath` does NOT expand `~` - it yields `<cwd>/~/...` - yet
     * the plugin API deliberately passes `~/` paths through, so a file reached
     * via `~` and via its absolute path would otherwise be two buffers for
     * one file. Expand it explicitly.
     */
    fun keyFor(path: String): String {
        val expanded =
            if (path == "~") System.getProperty("user.home")
            else if (path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~")
            else path
        return runCatching { File(expanded).absolutePath }.getOrDefault(path)
    }

    /**
     * Get (or create, loading [initialContent]) the buffer for [path] and
     * take a reference on it. Creation happens at most once per path, so a
     * second tab on the same file sees the first tab's live state.
     */
    @Synchronized
    fun acquire(path: String, initialContent: String, language: String): EditorBuffer {
        val key = keyFor(path)
        val buffer =
            buffers.getOrPut(key) {
                EditorBuffer(key, EditorState(initialContent, key), language).also {
                    // The file as it was when we loaded it: the watcher's
                    // baseline, so an edit made before BOSS opened it is not
                    // reported as having happened while it was open.
                    it.knownSignature = signatureOf(File(key))
                }
            }
        buffer.acquire()
        return buffer
    }

    /** @return true when the buffer was removed from the table. */
    @Synchronized
    fun release(path: String): Boolean {
        val key = keyFor(path)
        val buffer = buffers[key] ?: return false
        return if (buffer.release()) {
            buffers.remove(key)
            if (focused === buffer) focused = null
            true
        } else {
            false
        }
    }

    /** Find the buffer for [path] without taking a reference. */
    @Synchronized
    fun find(path: String): EditorBuffer? = buffers[keyFor(path)]

    /** Every open buffer, for the watcher. Only these files are ever polled. */
    @Synchronized
    fun all(): List<EditorBuffer> = buffers.values.toList()

    /**
     * The buffer of the editor that was most recently focused (caret moved
     * there). Editors report through [markFocused]; this is a heuristic -
     * "the document the user is looking at" - not a platform focus event.
     */
    @Synchronized
    fun markFocused(buffer: EditorBuffer) {
        focused = buffer
    }

    @Synchronized
    fun focused(): EditorBuffer? = focused
}
