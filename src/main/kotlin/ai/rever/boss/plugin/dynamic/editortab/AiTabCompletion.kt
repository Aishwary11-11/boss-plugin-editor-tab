package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiChunk
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.bosseditor.core.EditorPosition
import ai.rever.bosseditor.core.EditorDocument
import ai.rever.bosseditor.core.EditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// ========== Settings ==========

/**
 * AI tab-completion settings live in their own file; ~/.boss/editor-settings.json is the
 * BossEditor library's format and adding keys to it is not ours to do.
 *
 * Older files may still contain `model`; `Json.ignoreUnknownKeys` deliberately ignores it.
 * Model selection is now owned by AI Gateway, so completion follows the same active model as
 * every other editor AI surface instead of carrying a second, silently divergent override.
 */
@Serializable
data class AiCompletionSettingsData(
    val enabled: Boolean = true,
    /**
     * How long the typing has to stop before a request goes out.
     *
     * 1200ms keeps the editor responsive without issuing a request for every
     * keystroke. The CLI route
     * measured 11.7-15.9s on a realistic 4KB completion prompt, and a request
     * abandoned by the next keystroke still runs to completion - cancelling the
     * coroutine cannot interrupt the blocking read, and it kills the watchdog
     * that would otherwise have killed the process. So the debounce, not the
     * timeout, is what bounds how many CLI processes a typing burst spawns.
     */
    val debounceMs: Long = 1_200,
    /**
     * Per-request budget, passed as [AiRequest.timeoutMs] - which the gateway
     * hands to the CLI session as its IDLE timeout.
     *
     * Was 2s, which no CLI turn has ever met: every request was force-killed
     * before its first output, so inline completion could not work at all on
     * that route. Set above the measured worst case with margin.
     */
    val timeoutMs: Long = 20_000,
    val maxTokens: Int = 96,
    /** Context sent before the caret. */
    val maxPrefixChars: Int = 1_600,
    /** Context sent after the caret. */
    val maxSuffixChars: Int = 400,
)

/** Reactive settings from ~/.boss/ai-completion-settings.json (PluginEditorSettings pattern). */
object AiCompletionSettings {
    private val settingsFile = BossPaths.settingsFile("ai-completion-settings.json")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _settings = MutableStateFlow(loadFromFile())
    val settings: StateFlow<AiCompletionSettingsData> = _settings

    private var lastModified: Long = settingsFile.lastModified()
    private var watcherJob: Job? = null

    /**
     * The 500ms file poll, on a scope the plugin owns.
     *
     * It used to run on GlobalScope, where nothing could ever cancel it - the
     * loop held this plugin's classloader for the life of the JVM after
     * unload. The plugin starts it in register() and stops it in dispose().
     */
    fun start(scope: CoroutineScope) {
        watcherJob?.cancel()
        watcherJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(500)
                try {
                    val currentModified = settingsFile.lastModified()
                    if (currentModified != lastModified) {
                        lastModified = currentModified
                        _settings.value = loadFromFile()
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

    private fun loadFromFile(): AiCompletionSettingsData {
        return try {
            if (settingsFile.exists()) {
                json.decodeFromString<AiCompletionSettingsData>(settingsFile.readText())
            } else {
                AiCompletionSettingsData()
            }
        } catch (e: Exception) {
            AiCompletionSettingsData()
        }
    }
}

// ========== Completion engine ==========

/** A completion waiting at the caret. Valid only while the buffer stays at [documentVersion]. */
data class GhostSuggestion(
    val text: String,
    val position: EditorPosition,
    val documentVersion: Long,
)

/** The code window sent to the model. */
data class CompletionContext(
    val prefix: String,
    val suffix: String,
    val language: String,
    val fileName: String,
)

/**
 * AI tab completion (ghost text), plugin-side.
 *
 * BossEditor renders the suggestion at the caret and reserves virtual lines for multiline
 * continuations. Tab/Esc are intercepted before the editor sees them (preview phase — the editor
 * consumes Tab for indent). The AI comes from the ai-gateway plugin's [AiGatewayAPI], resolved
 * lazily per request (load order across plugins is not guaranteed, so caching
 * a null at construction would disable the feature forever); every failure —
 * gateway absent, no provider, timeout, blank reply — is silence, never an
 * error surface.
 */
class AiTabCompletionService(
    private val context: PluginContext,
    private val scope: CoroutineScope,
) {
    private val _suggestion = MutableStateFlow<GhostSuggestion?>(null)
    val suggestion: StateFlow<GhostSuggestion?> = _suggestion

    /**
     * Why completions are not appearing, or null when nothing is wrong.
     *
     * Ghost text stays silent about routine misses - a timeout, a blank reply,
     * a stale caret - because nagging on those would be worse than nothing.
     * A *configuration* problem is different: it never resolves on its own,
     * and silence made "no provider selected" look identical to "the feature
     * is broken". This is the state the editor surfaces.
     */
    private val _unavailable = MutableStateFlow<String?>(null)
    val unavailable: StateFlow<String?> = _unavailable

    private var inFlight: Job? = null

    /**
     * Consecutive requests that ran out of budget.
     *
     * A provider slow enough to time out repeatedly is not going to become
     * usable for ghost text, and silently retrying forever is how this looked
     * broken rather than misconfigured.
     */
    private var timeouts = 0

    /** Debounced (re)request. Called on every text change; the previous request is cancelled. */
    fun schedule(
        editorState: EditorState,
        filePath: String,
        language: String,
        settings: AiCompletionSettingsData,
    ) {
        inFlight?.cancel()
        _suggestion.value = null
        if (!settings.enabled) {
            _unavailable.value = null
            return
        }
        inFlight = scope.launch {
            delay(settings.debounceMs)
            val gateway = awaitEditorAiGateway(context)
            if (gateway == null) {
                _unavailable.value = AiInlineEditService.describeReadiness(editorAiReadiness(context))
                return@launch
            }
            _unavailable.value = slowProviderNotice(timeouts)
            if (editorState.multiCaretModel.hasMultipleCarets) return@launch
            val document = editorState.document
            val version = document.documentVersion
            val caret = editorState.caretPosition.value
            val completionContext =
                buildContext(document.getText(), editorState.caretOffset, language, filePath, settings)
            val outcome = withContext(Dispatchers.IO) {
                // Streaming lets useful text appear as soon as the model emits
                // it instead of hiding the whole completion until generation
                // finishes. Providers without native streaming are handled by
                // the gateway's documented Text + Completed fallback.
                collectCompletionStream(gateway, buildRequest(completionContext, settings), settings.timeoutMs + 500) {
                    publishIfFresh(editorState, document, version, caret, completionContext, it)
                }
            }
            when (outcome) {
                CompletionStreamOutcome.Completed -> timeouts = 0
                CompletionStreamOutcome.TimedOut -> {
                    // A partial stream is not a completion: leaving it visible lets Tab insert
                    // truncated code. Clear it before reporting a repeatedly slow provider.
                    _suggestion.value = null
                    timeouts++
                    _unavailable.value = slowProviderNotice(timeouts)
                }
                CompletionStreamOutcome.Incomplete -> _suggestion.value = null
                is CompletionStreamOutcome.Failed -> {
                    _suggestion.value = null
                    System.err.println(
                        "[AiTabCompletion] ${outcome.error::class.simpleName}: ${outcome.error.message}",
                    )
                }
            }
        }
    }

    /** A caret move away from the anchored position invalidates the shown suggestion. */
    fun onCaretMoved(position: EditorPosition) {
        val shown = _suggestion.value ?: return
        if (shown.position != position) _suggestion.value = null
    }

    /** @return true when a suggestion was actually dismissed, so Esc is consumed. */
    fun dismiss(): Boolean {
        inFlight?.cancel()
        val had = _suggestion.value != null
        _suggestion.value = null
        return had
    }

    /**
     * Inserts the suggestion at the caret via [EditorState.insertText] — the
     * same undoable path as typing, one undo step. @return false when the
     * suggestion went stale, in which case Tab falls through to the editor.
     */
    fun accept(editorState: EditorState): Boolean {
        val shown = _suggestion.value ?: return false
        inFlight?.cancel()
        _suggestion.value = null
        if (editorState.document.documentVersion != shown.documentVersion ||
            editorState.caretPosition.value != shown.position
        ) {
            return false
        }
        editorState.insertText(shown.text)
        return true
    }

    private fun publishIfFresh(
        editorState: EditorState,
        document: EditorDocument,
        version: Long,
        caret: EditorPosition,
        completionContext: CompletionContext,
        raw: String,
    ) {
        val completion = postProcess(raw, completionContext) ?: return
        // The user may keep typing while tokens arrive. Never let an older
        // stream overwrite the text at a new caret/document version.
        if (document.documentVersion != version || editorState.caretPosition.value != caret) return
        _suggestion.value = GhostSuggestion(completion, caret, version)
    }

    companion object {
        private const val MAX_LINES = 8

        private val SYSTEM_PROMPT = """
            You are an inline code-completion engine. Insert-at-cursor completions only.
            Rules: output ONLY the raw code to insert at the cursor position — no markdown
            fences, no commentary, no repetition of the code before or after the cursor.
            Prefer completing the current statement or block. Stay under ~6 lines.
            If nothing useful can be completed, output nothing.
        """.trimIndent()

        internal fun buildContext(
            fullText: String,
            caretOffset: Int,
            language: String,
            filePath: String,
            settings: AiCompletionSettingsData,
        ): CompletionContext {
            val start = (caretOffset - settings.maxPrefixChars).coerceAtLeast(0)
            var prefix = fullText.substring(start, caretOffset)
            if (start > 0) {
                // A truncated prefix starts on a whole line
                val newline = prefix.indexOf('\n')
                if (newline >= 0) prefix = prefix.substring(newline + 1)
            }
            val suffix = fullText.substring(
                caretOffset,
                (caretOffset + settings.maxSuffixChars).coerceAtMost(fullText.length),
            )
            return CompletionContext(prefix, suffix, language, filePath.substringAfterLast('/'))
        }

        /**
         * Repeated timeouts are a configuration fact, not a transient failure.
         *
         * Ghost text is only useful if it arrives while the caret is still
         * where it was; a route that needs twelve seconds cannot do that
         * whatever the budget, so the honest thing is to name it.
         */
        internal fun slowProviderNotice(timeouts: Int): String? =
            if (timeouts >= SLOW_PROVIDER_STRIKES) SLOW_PROVIDER_NOTICE else null

        internal const val SLOW_PROVIDER_STRIKES = 2

        internal const val SLOW_PROVIDER_NOTICE =
            "Inline completion timed out repeatedly - this provider is too slow for ghost text. " +
                "Configure an API-key provider for inline completion."

        internal fun buildRequest(ctx: CompletionContext, settings: AiCompletionSettingsData): AiRequest =
            AiRequest(
                system = SYSTEM_PROMPT,
                messages = listOf(
                    AiMessage.user(
                        // No newline between the prefix tail / suffix head and their
                        // tags: the cursor sits exactly between the two blocks, and
                        // mid-word completions need that adjacency preserved.
                        "File: ${ctx.fileName} (${ctx.language})\n" +
                            "<code_before_cursor>\n${ctx.prefix}</code_before_cursor>\n" +
                            "<code_after_cursor>${ctx.suffix}\n</code_after_cursor>\n" +
                            "Output only the completion to insert at the cursor.",
                    ),
                ),
                maxTokens = settings.maxTokens,
                timeoutMs = settings.timeoutMs,
                temperature = 0f,
                // The absent override makes the gateway use the shared provider's
                // active/default model and its secret-manager-backed credential.
                extras = emptyMap(),
            )

        /** Cleans a raw model reply; null means "show nothing". */
        internal fun postProcess(raw: String, ctx: CompletionContext): String? {
            var text = raw
            // Strip a wrapping markdown fence despite the prompt forbidding it
            if (text.trimStart().startsWith("```")) {
                text = text.trimStart().substringAfter('\n', "").substringBeforeLast("```")
            }
            text = text.trimEnd()
            if (text.isBlank()) return null
            // Model echoed what already follows the caret
            val suffixTrimmed = ctx.suffix.trimStart()
            if (suffixTrimmed.isNotEmpty() && suffixTrimmed.startsWith(text.trimStart())) return null
            // Model repeated the tail of the current line before continuing:
            // strip the longest suffix of the caret line that prefixes the reply
            val lastLine = ctx.prefix.substringAfterLast('\n')
            if (lastLine.isNotEmpty()) {
                for (k in minOf(lastLine.length, text.length) downTo 1) {
                    if (text.startsWith(lastLine.takeLast(k))) {
                        text = text.substring(k)
                        break
                    }
                }
            }
            if (text.isBlank()) return null
            val lines = text.lines()
            return if (lines.size > MAX_LINES) lines.take(MAX_LINES).joinToString("\n") else text
        }
    }
}

/** Terminal state of one completion stream. Only [Completed] may remain accept-able. */
internal sealed interface CompletionStreamOutcome {
    data object Completed : CompletionStreamOutcome
    data object TimedOut : CompletionStreamOutcome
    data object Incomplete : CompletionStreamOutcome
    data class Failed(val error: Throwable) : CompletionStreamOutcome
}

/** Collect one gateway stream, publishing partial text while requiring an explicit completion. */
internal suspend fun collectCompletionStream(
    gateway: AiGatewayAPI,
    request: AiRequest,
    timeoutMs: Long,
    publish: (String) -> Unit,
): CompletionStreamOutcome {
    val streamed = StringBuilder()
    return withTimeoutOrNull(timeoutMs) {
        var outcome: CompletionStreamOutcome = CompletionStreamOutcome.Incomplete
        try {
            gateway.stream(request).takeWhile { chunk ->
                when (chunk) {
                    is AiChunk.Text -> {
                        streamed.append(chunk.text)
                        publish(streamed.toString())
                        true
                    }
                    is AiChunk.Completed -> {
                        publish(chunk.reply.text.ifBlank { streamed.toString() })
                        outcome = CompletionStreamOutcome.Completed
                        false
                    }
                    is AiChunk.Failed -> {
                        outcome = CompletionStreamOutcome.Failed(chunk.error)
                        false
                    }
                    else -> true
                }
            }.collect {}
            outcome
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            CompletionStreamOutcome.Failed(error)
        }
    } ?: CompletionStreamOutcome.TimedOut
}
