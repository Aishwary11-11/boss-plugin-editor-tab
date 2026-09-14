package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiBudget
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.AiStopReason
import ai.rever.boss.plugin.api.AiToolCall
import ai.rever.boss.plugin.api.AiToolOutcome
import ai.rever.boss.plugin.api.AiToolSpec
import ai.rever.boss.plugin.api.EditorTabPluginAPI
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.ProjectSearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The composer's agent loop (IDE batch P4.3).
 *
 * One [runAgent] per task: the model gets four tools it executes locally in
 * this plugin - read_buffer, propose_edit, project_search, git_diff - and
 * answers with a summary. Proposals accumulate and are NEVER applied by the
 * loop; applying is the user's (or the ai_compose_accept MCP tool's) decision,
 * through the version-guarded buffer API.
 *
 * Gated on the gateway advertising CAPABILITY_TOOLS: without it the run
 * degrades to a single tool-less reply that says so, rather than pretending
 * the tools were considered.
 */
class ComposerAgent(
    private val context: PluginContext,
    val editorApi: EditorTabPluginAPI,
    private val scope: CoroutineScope,
    private val sessions: ComposerSessions,
) {
    private val json = Json { ignoreUnknownKeys = true }
    // Concurrent: start/stop come from the UI thread, the completion path
    // removes its own entry from the run's IO coroutine.
    private val runs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** @return the current ready gateway, including a selected Claude/Codex CLI engine. */
    fun gateway(): AiGatewayAPI? = resolveReadyEditorAiGateway(context)

    /**
     * The focused editor's selection, or null when nothing is selected.
     *
     * Whole-buffer content is deliberately NOT a fallback: the agent has
     * read_buffer for that, and a chip claiming 1-2000 selected would be a
     * lie about what the user pointed at.
     */
    suspend fun captureSelection(): ComposerSelection? {
        val doc =
            try {
                editorApi.focusedDocument()
            } catch (_: Exception) {
                null
            } ?: return null
        val startLine = doc.selectionStartLine ?: return null
        val endLine = doc.selectionEndLine ?: return null
        if (endLine < startLine) return null
        val lines = doc.content.lines()
        if (startLine !in 1..lines.size) return null
        val text =
            lines.subList(startLine - 1, endLine.coerceAtMost(lines.size)).joinToString("\n")
        if (text.isBlank()) return null
        return ComposerSelection(
            path = doc.path,
            startLine = startLine,
            endLine = endLine.coerceAtMost(lines.size),
            text = text.take(ComposerSelection.MAX_TEXT_CHARS),
            language = doc.language,
        )
    }

    /**
     * Start a task in a session. No-op (false) when one is already running,
     * or when there is no gateway to run it with.
     *
     * Progress goes to [ComposerSessions], never to a caller-held snapshot:
     * the run outlives whatever view started it.
     */
    fun start(sessionId: String, task: String): Boolean {
        if (runs[sessionId]?.isActive == true) return false
        val gateway = gateway() ?: return false
        val started =
            sessions.update(sessionId) {
                it.copy(
                    task = task,
                    title = composerTitleFor(it.task.ifEmpty { task }),
                    status = "running",
                    statusMessage = "Thinking...",
                    pendingStart = false,
                    messages = it.messages + ComposerMessage("user", task),
                )
            } ?: return false
        // Launch OUTSIDE the map: the CHM contract warns against map work from
        // inside a mapping function, and the old launch-inside-compute only
        // worked because the launched body ran on another thread and merely
        // blocked on the bin lock. The slot is then swapped in atomically;
        // if a newer active run wins the cell, this job is cancelled.
        val job =
            scope.launch(Dispatchers.IO) {
                val hasTools = AiGatewayAPI.CAPABILITY_TOOLS in gateway.capabilities()
                val result =
                    withTimeoutOrNull(20 * 60_000 + 10_000) {
                        gateway.runAgent(
                            AiRequest(
                                system = SYSTEM_PROMPT,
                                messages = listOf(AiMessage.user(promptFor(task, started.selection))),
                                timeoutMs = 90_000,
                            ),
                            tools = if (hasTools) TOOL_SPECS else emptyList(),
                            budget = AiBudget(maxSteps = 24, timeoutMs = 20 * 60_000),
                        ) { call ->
                            executeTool(sessionId, call)
                        }
                    }
                result
                    ?.fold(
                        onSuccess = { r ->
                            val msg =
                                r.text.ifBlank {
                                    "The agent stopped before answering (${r.stopReason.name.lowercase()})."
                                }
                            val stopNote =
                                if (r.stopReason == AiStopReason.COMPLETED) ""
                                else " [stopped: ${r.stopReason.name.lowercase()}]"
                            finish(sessionId, msg + stopNote, "done", "")
                        },
                        onFailure = { e ->
                            finish(sessionId, e.message ?: "The AI request failed.", "error", e.message ?: "failed")
                        },
                    ) ?: finish(sessionId, "The AI request timed out.", "error", "timeout")
            }
        // The two-arg remove in the completion handler is what keeps the slot
        // honest: it evicts ONLY this job, so a run that finishes immediately
        // clears the very entry it created (the old launch-then-assign
        // ordering re-inserted dead jobs), while a NEWER run in the slot is
        // never evicted by its predecessor's completion.
        job.invokeOnCompletion { runs.remove(sessionId, job) }
        val stored =
            runs.compute(sessionId) { _, current ->
                if (current?.isActive == true) current else job
            }
        if (stored !== job) job.cancel()
        return stored === job
    }

    /**
     * What the model is asked. The selection is named by file and line range
     * as well as quoted, so propose_edit lands on those lines rather than on
     * the agent's guess at where the quoted text lives.
     */
    private fun promptFor(task: String, selection: ComposerSelection?): String =
        if (selection == null) {
            task
        } else {
            buildString {
                append(task)
                append("\n\nThe user has selected lines ")
                append(selection.startLine).append('-').append(selection.endLine)
                append(" of ").append(selection.path)
                append(". Work on that range unless the task says otherwise.\n")
                append("```").append(selection.language).append('\n')
                append(selection.text)
                append("\n```")
            }
        }

    /**
     * Stop a run (UI or the ai_compose_stop tool). The session records the
     * stop itself, so every view of it - this tab, another window's, the MCP
     * status tool - reports the same thing without being told separately.
     */
    fun stop(sessionId: String) {
        runs.remove(sessionId)?.cancel()
        sessions.update(sessionId) {
            if (it.status == "running") it.copy(status = "stopped", statusMessage = "Stopped.") else it
        }
        // A stop with no tab left open and nothing pending to review is the
        // end of the session.
        sessions.maybeCloseIfIdle(sessionId)
    }

    /**
     * Finish a run. The run's SLOT is cleared by the job's
     * [invokeOnCompletion] handler in [start] (a two-arg remove, so only the
     * job that just ended is evicted) - clearing it here would never fire,
     * because finish runs from inside the job, which is still active.
     */
    private fun finish(
        sessionId: String,
        text: String,
        status: String,
        statusMessage: String,
    ) {
        sessions.update(sessionId) {
            it.copy(
                status = status,
                statusMessage = statusMessage,
                messages = it.messages + ComposerMessage("assistant", text),
            )
        }
        // The run is over: if no tab still shows the session and nothing is
        // left to accept, it can go (MCP-only runs have no tab at all).
        sessions.maybeCloseIfIdle(sessionId)
    }

    /** Execute one tool call locally; errors are fed back to the model, not thrown. */
    private suspend fun executeTool(
        sessionId: String,
        call: AiToolCall,
    ): AiToolOutcome {
        val args =
            runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }
                .getOrNull() ?: return AiToolOutcome(call.id, "Malformed arguments: ${call.argumentsJson}", isError = true)
        val log = { text: String ->
            sessions.update(sessionId) { it.copy(messages = it.messages + ComposerMessage("tool", text)) }
            Unit
        }
        return try {
            when (call.name) {
                "read_buffer" -> {
                    val path = args.get("path")?.jsonPrimitive?.content ?: ""
                    log("read_buffer($path)")
                    val snap = editorApi.readBuffer(path)
                    if (snap == null) {
                        AiToolOutcome(call.id, "No open buffer for $path. Proposals only work on files that are open in the editor.", isError = true)
                    } else {
                        AiToolOutcome(call.id, "buffer_version: ${snap.version}\n---\n${snap.content}")
                    }
                }

                "propose_edit" -> {
                    val path = args.get("path")?.jsonPrimitive?.content ?: ""
                    val sl = args.get("start_line")?.jsonPrimitive?.int ?: 0
                    val sc = args.get("start_col")?.jsonPrimitive?.int ?: 1
                    val el = args.get("end_line")?.jsonPrimitive?.int ?: 0
                    val ec = args.get("end_col")?.jsonPrimitive?.int ?: 1
                    val newText = args.get("new_text")?.jsonPrimitive?.content ?: ""
                    if (path.isEmpty() || sl == 0 || el == 0) {
                        AiToolOutcome(call.id, "propose_edit needs path, start_line, end_line (1-based).", isError = true)
                    } else {
                        val snap = editorApi.readBuffer(path)
                        val version = snap?.version ?: 0
                        // The id is derived INSIDE the update, from the very
                        // session the proposal is appended to. Reading the
                        // count outside it meant a snapshot miss (a session
                        // not loaded on this path) silently restarted
                        // numbering at p1 - and both ai_compose_accept and
                        // markProposal match proposals by id, so a collision
                        // applies or marks the wrong edit.
                        var id = ""
                        val updated =
                            sessions.update(sessionId) { s ->
                                id = "p${s.proposals.size + 1}"
                                s.copy(
                                    proposals = s.proposals +
                                        ComposerProposal(
                                            id = id,
                                            path = path,
                                            startLine = sl,
                                            startCol = sc,
                                            endLine = el,
                                            endCol = ec,
                                            newText = newText,
                                            expectedVersion = version,
                                        ),
                                )
                            }
                        if (updated == null) {
                            AiToolOutcome(
                                call.id,
                                "Composer session $sessionId is no longer open; the proposal was not recorded.",
                                isError = true,
                            )
                        } else {
                            log("propose_edit($path, lines $sl:$sc-${el}:$ec) -> proposal $id")
                            AiToolOutcome(call.id, "Recorded as proposal $id. It is NOT applied until the user accepts it.")
                        }
                    }
                }

                "project_search" -> {
                    val q = args.get("query")?.jsonPrimitive?.content ?: ""
                    log("project_search($q)")
                    val provider: ProjectSearchProvider? = context.projectSearchProvider
                    if (provider == null) {
                        AiToolOutcome(call.id, "Project search is unavailable in this host.", isError = true)
                    } else {
                        val matches =
                            withContext(Dispatchers.IO) {
                                provider.searchInProject(q, maxResults = 20)
                            }
                        if (matches.isEmpty()) {
                            AiToolOutcome(call.id, "No matches for: $q")
                        } else {
                            AiToolOutcome(
                                call.id,
                                matches.joinToString("\n") { m -> "${m.path}:${m.line}:${m.contextLine.trimEnd()}" },
                            )
                        }
                    }
                }

                "git_diff" -> {
                    val path = args.get("path")?.jsonPrimitive?.content
                    log("git_diff(${path ?: "working tree"})")
                    val git = context.gitDataProvider
                    if (git == null) {
                        AiToolOutcome(call.id, "No git data provider in this context.", isError = true)
                    } else {
                        val diff =
                            withContext(Dispatchers.IO) {
                                if (path.isNullOrEmpty()) {
                                    git.diffNames(staged = false)
                                        .joinToString("\n") { it.path }
                                        .ifBlank { "(working tree is clean)" }
                                } else {
                                    git.diffFile(path, staged = false).firstOrNull()?.rawUnified
                                        ?: "(no diff for $path)"
                                }
                            }
                        AiToolOutcome(call.id, diff)
                    }
                }

                else -> AiToolOutcome(call.id, "Unknown tool: ${call.name}", isError = true)
            }
        } catch (e: Exception) {
            AiToolOutcome(call.id, "Tool failed: ${e.message}", isError = true)
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a software engineer working inside the BOSS IDE. The user gives you a " +
                "task about the current project. You can read open editor buffers, search the " +
                "project, and read git diffs. To change code you MUST call propose_edit - " +
                "never assume an edit was made. Propose edits line-by-line: read the file " +
                "first, then propose the exact replacement for a small, precise range. " +
                "Proposals are applied only when the user accepts them, so make each one " +
                "independent and correct. When finished, summarize what you proposed and why."

        val TOOL_SPECS: List<AiToolSpec> =
            listOf(
                AiToolSpec(
                    name = "read_buffer",
                    description = "Read the live content of an OPEN editor buffer (unsaved edits included). Returns a buffer_version line then the content. Proposals can only target open buffers.",
                    inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"Absolute or project-relative path of the open file."}},"required":["path"]}""",
                ),
                AiToolSpec(
                    name = "propose_edit",
                    description = "Record a proposed replacement for a range of an open buffer. Lines/columns are 1-based; end is exclusive. NOT applied until the user accepts it.",
                    inputSchema =
                        """{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"start_col":{"type":"integer"},"end_line":{"type":"integer"},"end_col":{"type":"integer"},"new_text":{"type":"string","description":"Replacement text."}},"required":["path","start_line","end_line","new_text"]}""",
                ),
                AiToolSpec(
                    name = "project_search",
                    description = "Full-text search the project. Returns up to 20 matches as path:line:text.",
                    inputSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Search text (plain substring or regex, per the host search service)."}},"required":["query"]}""",
                ),
                AiToolSpec(
                    name = "git_diff",
                    description = "Read the git diff: the working-tree changes for one path, or the list of changed file names when path is omitted.",
                    inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"Optional path to restrict the diff to."}}}""",
                ),
            )
    }
}
