package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiChunk
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.bosseditor.core.EditorPosition
import ai.rever.bosseditor.core.EditorRange
import ai.rever.bosseditor.core.EditorState
import ai.rever.bosseditor.lsp.protocol.Position
import ai.rever.bosseditor.lsp.protocol.Range
import ai.rever.bosseditor.lsp.protocol.TextEdit
import ai.rever.bosseditor.lsp.protocol.WorkspaceEdit
import ai.rever.bosseditor.refactoring.FileChange
import ai.rever.bosseditor.refactoring.RefactorPreviewGenerator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cmd/Ctrl+I or Cmd/Ctrl+K inline AI edit (IDE batch P4.2).
 *
 * The selection (or the caret's line when nothing is selected) is sent to the
 * active AI provider with a "rewrite only this code" prompt. Review is rendered from a shadow
 * EditorState, so the live buffer, LSP, autosave and other splits do not observe a proposed edit.
 *
 * Opens even when AI is unavailable so the prompt can explain whether the
 * shared provider or gateway needs configuration.
 */
class AiInlineEditService(
    private val context: PluginContext,
    private val scope: CoroutineScope,
) {
    /** One inline-edit session: the prompt, its flight, and the captured selection. */
    data class Session(
        val prompt: String = "",
        val busy: Boolean = false,
        val streaming: String = "",
        val error: String? = null,
        val done: Boolean = false,
        val replacement: String = "",
        val preview: Preview? = null,
        val selectionText: String = "",
        val startLine: Int = 0,
        val startCol: Int = 0,
        val endLine: Int = 0,
        val endCol: Int = 0,
        val anchorLine: Int = startLine,
        val anchorCol: Int = startCol,
        val bufferVersion: Long = 0,
        val language: String = "",
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** The buffer the session's apply targets; bound by the active viewport. */
    var buffer: EditorBuffer? = null
        private set

    /** The editor state of the viewport that bound the buffer (preview source). */
    var editorState: EditorState? = null
        private set

    private var job: Job? = null

    /** The active viewport claims the session target. */
    fun bind(buffer: EditorBuffer?, editorState: EditorState) {
        this.buffer = buffer
        this.editorState = editorState
    }

    /** @return true when a session was started (so the key event is consumed). */
    fun start(editorState: EditorState, language: String): Boolean {
        // The shortcut tunnels through the whole tab, including the prompt itself. Refuse
        // re-entry so a second press cannot replace the captured offsets while the first
        // generation is still streaming and later graft its replacement onto a new selection.
        if (_session.value != null) return true
        // A missing gateway used to return false here, so the compose shortcut did nothing at
        // all - indistinguishable from a broken keybinding. Open the widget
        // either way and let it say which of the two things is actually wrong.
        val state = this.editorState ?: editorState
        val doc = state.document
        val selection = state.selection.value?.takeIf { state.hasSelection }
        val caret = state.caretPosition.value
        val start = selection?.start ?: caret
        val end = selection?.end ?: EditorPosition(start.line, doc.getLineLength(start.line))
        val startOffset = doc.positionToOffset(start)
        val endOffset = doc.positionToOffset(end)
        if (startOffset == endOffset) return true // caret on an empty line: consume, nothing to edit
        _session.value =
            Session(
                selectionText = doc.getText(startOffset, endOffset),
                startLine = start.line,
                startCol = start.column,
                endLine = end.line,
                endCol = end.column,
                // Anchor at the active edge of the selection, rather than assuming that its
                // normalized end is where the user finished selecting.
                anchorLine = caret.line,
                anchorCol = caret.column,
                bufferVersion = buffer?.version ?: doc.documentVersion,
                language = language,
                // Provider registration and credential loading are asynchronous.
                // Resolve when the user submits instead of showing a false error now.
                error = null,
            )
        return true
    }

    fun setPrompt(text: String) {
        _session.value = _session.value?.copy(prompt = text)
    }

    fun cancel() {
        job?.cancel()
        _session.value = null
    }

    fun submit() {
        val s = _session.value ?: return
        if (s.prompt.isBlank() || s.busy) return
        val state = editorState ?: return
        val doc = state.document
        job?.cancel()
        job =
            scope.launch {
                _session.value = s.copy(busy = true, error = null)
                val gateway = awaitEditorAiGateway(context)
                if (gateway == null) {
                    _session.value = _session.value?.copy(
                        busy = false,
                        error = describeReadiness(editorAiReadiness(context)) ?: "AI is unavailable",
                    )
                    return@launch
                }
                var text = ""
                try {
                    withContext(Dispatchers.IO) {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                            gateway.stream(buildRequest(s.prompt, s.selectionText, s.language)).collect { chunk ->
                                when (chunk) {
                                    is AiChunk.Text -> {
                                        text += chunk.text
                                        _session.value = _session.value?.copy(streaming = text)
                                    }

                                    is AiChunk.Failed -> throw chunk.error
                                    is AiChunk.Completed -> {
                                        text = chunk.reply.text
                                    }

                                    else -> Unit
                                }
                            }
                        }
                    } ?: throw java.util.concurrent.TimeoutException("AI request timed out")
                } catch (cancellation: CancellationException) {
                    // cancel() may be followed immediately by a new session. Do not let the old
                    // generation stamp its cancellation error onto that replacement session.
                    throw cancellation
                } catch (e: Exception) {
                    _session.value = _session.value?.copy(busy = false, error = e.message ?: "AI request failed")
                    return@launch
                }
                val replacement = stripFences(text)
                if (replacement.isBlank()) {
                    _session.value = _session.value?.copy(busy = false, error = "The model returned no replacement")
                    return@launch
                }
                // Build preview metadata on the buffer's content at response time. The UI also
                // builds a read-only shadow EditorState; the live document stays untouched until
                // Accept.
                val uri = buffer?.path ?: ""
                val edit =
                    TextEdit(
                        Range(
                            Position(s.startLine, s.startCol),
                            Position(s.endLine, s.endCol),
                        ),
                        replacement,
                    )
                val changes: List<FileChange> =
                    try {
                        RefactorPreviewGenerator.generatePreview(
                            WorkspaceEdit(mapOf(uri to listOf(edit))),
                            { _ -> state.document.getText() },
                        )
                    } catch (e: Exception) {
                        emptyList()
                    }
                // The session as it is NOW, not the one captured at submit: the prompt
                // can be edited while the stream runs, and setPrompt keeps
                // the live value - the submit-time copy would drop those
                // edits on accept. (Also safe after cancel(): it nulls the
                // session, and the elvis simply keeps it null.)
                _session.value =
                    _session.value?.copy(
                        busy = false,
                        streaming = "",
                        done = true,
                        replacement = replacement,
                        preview = Preview(uri, changes),
                    )
            }
    }

    /** The buffer moved while the preview was open: back to the prompt, keep the instruction. */
    fun markStale() {
        _session.value =
            _session.value?.copy(
                done = false,
                preview = null,
                replacement = "",
                busy = false,
                error = "The buffer changed while this edit was being reviewed. Regenerate.",
            )
    }

    /**
     * The version the session's offsets were computed against, as it stands
     * now - or null when there is no document to ask.
     *
     * Mirrors exactly what [start] captured: the shared buffer's version when
     * there is one, the viewport's own document version when there is not.
     * Consulting only the buffer left every buffer-less viewport (an untitled
     * document, or any viewport holding a private EditorState) with NO
     * staleness check at all, so an inline rewrite accepted after the user had
     * typed applied at pre-typing offsets.
     */
    private fun currentVersion(): Long? = buffer?.version ?: editorState?.document?.documentVersion

    /** The captured buffer version must still match when the user applies. */
    fun isStale(): Boolean {
        val s = _session.value ?: return true
        val now = currentVersion() ?: return false
        return now != s.bufferVersion
    }

    /**
     * Apply the accepted replacement through the shared document (undoable).
     * @return false when the buffer moved on since the request - the caller
     * must re-prompt, never retry blind.
     */
    fun applyAccepted(): Boolean {
        val s = _session.value ?: return false
        val state = editorState ?: return false
        val doc = state.document
        if (isStale()) return false
        // Position math is only valid while the version held; it still does.
        val start = doc.positionToOffset(s.startLine, s.startCol)
        val end = doc.positionToOffset(s.endLine, s.endCol)
        if (start !in 0..doc.length || end !in 0..doc.length || start > end) return false
        applyAcceptedAiInlineEdit(
            state = state,
            start = EditorPosition(s.startLine, s.startCol),
            end = EditorPosition(s.endLine, s.endCol),
            replacement = s.replacement,
        )
        _session.value = null
        return true
    }

    /** Preview data for the RefactorPreviewDialog. */
    data class Preview(
        val uri: String,
        val changes: List<FileChange>,
    )

    /** Clear the error so the user can retype without reopening the widget. */
    fun clearError() {
        _session.value = _session.value?.copy(error = null)
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 60_000L

        /**
         * Why AI cannot run, or null when it can.
         *
         * The two causes need different fixes and a single "no AI provider"
         * string sent users to the wrong one: a Claude/Codex CLI login counts
         * as a provider (the gateway reports the engine from `activeModel()`),
         * so someone with a working login was being told to configure a
         * provider they had deliberately not configured. What they actually
         * have to do is *select* the engine.
         */
        internal fun describeReadiness(readiness: AiReadiness): String? =
            when (readiness) {
                AiReadiness.READY -> null
                AiReadiness.GATEWAY_MISSING ->
                    "The AI Gateway plugin is not loaded. Install or enable it in the Toolbox."
                AiReadiness.NO_PROVIDER ->
                    "No AI model selected. Pick a provider or a CLI engine (Claude, Codex) " +
                        "in the AI Gateway, then try again."
                else -> "AI is unavailable on this host."
            }

        private const val SYSTEM_PROMPT =
            "You are an inline code-editing engine. You rewrite ONLY the code the user " +
                "selected, per their instruction. Output the raw replacement code: no " +
                "markdown fences, no commentary, no diff markers, no repetition of " +
                "surrounding code."

        internal fun buildRequest(
            prompt: String,
            selectionText: String,
            language: String,
        ): AiRequest =
            AiRequest(
                system = SYSTEM_PROMPT,
                messages =
                    listOf(
                        AiMessage.user(
                            "Language: $language\n" +
                                "Instruction: $prompt\n" +
                                "<selected_code>\n$selectionText</selected_code>\n" +
                                "Output only the rewritten selected code.",
                        ),
                    ),
                maxTokens = 4096,
                timeoutMs = 45_000,
                temperature = 0f,
            )

        internal fun stripFences(text: String): String {
            var t = text
            if (t.trimStart().startsWith("```")) {
                t = t.trimStart().substringAfter('\n', "").substringBeforeLast("```")
            }
            return t.trimEnd()
        }
    }
}

/** Applies an accepted proposal as one undo step, isolated from the preceding typing group. */
internal fun applyAcceptedAiInlineEdit(
    state: EditorState,
    start: EditorPosition,
    end: EditorPosition,
    replacement: String,
) {
    state.undoManager.breakUndoGroup()
    state.setSelection(EditorRange(start, end))
    state.insertText(replacement)
    state.undoManager.breakUndoGroup()
}
