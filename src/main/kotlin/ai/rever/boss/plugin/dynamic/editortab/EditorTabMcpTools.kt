package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.EditorTabPluginAPI
import ai.rever.boss.plugin.api.FileReadResult
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PluginContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP tools contributed by the Code Editor Tab plugin: read files through the
 * host, protect disk writes locally, detect a file's language, and (1.0.87) drive
 * the live editor buffers - read a buffer, read the focused document plus its
 * selection, apply an undoable range edit, open a split view.
 *
 * Registered in [EditorTabDynamicPlugin.register]; removed automatically on
 * disable/unload. The buffer tools degrade to an error result when the API
 * impl could not be constructed (host predates the buffer model).
 */
internal class EditorTabMcpToolProvider(
    override val providerId: String,
    private val context: PluginContext,
    private val editor: EditorContentProvider?,
    private val editorApi: EditorTabPluginAPI?,
    private val composerAgent: ComposerAgent?,
    private val composerSessions: ComposerSessions?,
    private val writeToDisk: (String, String) -> Boolean = ::writeProtectedEditorFile,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "editor_read_file",
            description = "Read a file's contents via the BOSS editor (handles large-file limits).",
            inputSchema = pathSchema("File path to read."),
            handler = McpToolHandler { args ->
                val e = editor ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                when (val r = e.readFileContent(path)) {
                    is FileReadResult.Success -> McpToolResult(r.content)
                    is FileReadResult.FileTooLarge ->
                        McpToolResult("File too large: ${r.sizeBytes} bytes (max ${r.maxSizeBytes}).", isError = true)
                    is FileReadResult.Error -> McpToolResult("Read failed: ${r.message}", isError = true)
                    FileReadResult.FileNotFound -> McpToolResult("File not found: $path", isError = true)
                }
            },
        ),
        // editor.write, like editor_apply_edit: this one overwrites a whole
        // file on disk with no version guard at all, so of the two writers it
        // is the one that most wants the permission.
        McpToolDefinition.withRbac(
            name = "editor_write_file",
            description = "Write (create or overwrite) a file with protected atomic replacement.",
            inputSchema = WRITE_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf("editor.write"),
            handler = McpToolHandler { args ->
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                // Error rather than default to "" - a missing/null content must not
                // silently truncate the target file to empty.
                val content = args.string("content")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: content", isError = true)
                if (writeToolFile(path, content)) McpToolResult("Wrote ${content.length} chars to $path.")
                else McpToolResult("Write failed for $path.", isError = true)
            },
        ),
        McpToolDefinition(
            name = "editor_detect_language",
            description = "Detect the programming language the editor would use for a file path.",
            inputSchema = pathSchema("File path."),
            handler = McpToolHandler { args ->
                val e = editor ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                McpToolResult(e.detectLanguage(path))
            },
        ),
        McpToolDefinition(
            name = "editor_read_buffer",
            description = "Read the LIVE content of an open editor buffer (unsaved edits included). " +
                "Returns the buffer version on the first line - pass it as expected_version to " +
                "editor_apply_edit. Use editor_read_file for files that are not open.",
            inputSchema = pathSchema("Path of the open editor file (absolute or project-relative)."),
            handler = McpToolHandler { args ->
                val api = editorApi ?: return@McpToolHandler apiUnavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                val snapshot = api.readBuffer(path)
                    ?: return@McpToolHandler McpToolResult(
                        "No open editor buffer for: $path (open the file first, or use editor_read_file).",
                        isError = true,
                    )
                McpToolResult(
                    "buffer_version: ${snapshot.version}\nmodified: ${snapshot.isModified}\n---\n${snapshot.content}",
                )
            },
        ),
        McpToolDefinition(
            name = "editor_get_selection",
            description = "Read the focused editor's document: path, buffer version, language, the " +
                "user's current selection (1-based line:col, 'none' when the caret is not selecting) " +
                "and the full content. Typical use: edit exactly what the user selected.",
            inputSchema = """{"type":"object","properties":{}}""",
            handler = McpToolHandler { _ ->
                val api = editorApi ?: return@McpToolHandler apiUnavailable()
                val document = api.focusedDocument()
                    ?: return@McpToolHandler McpToolResult("No focused editor document.", isError = true)
                val sel =
                    if (document.selectionStartLine != null) {
                        "${document.selectionStartLine}:${document.selectionStartCol}-${document.selectionEndLine}:${document.selectionEndCol}"
                    } else {
                        "none"
                    }
                McpToolResult(
                    "path: ${document.path}\nbuffer_version: ${document.version}\nlanguage: ${document.language}\n" +
                        "selection: $sel\n---\n${document.content}",
                )
            },
        ),
        McpToolDefinition.withRbac(
            name = "editor_apply_edit",
            description = "Replace a range of an OPEN editor buffer. Lines and columns are 1-based; " +
                "the edit is applied through the editor's document (undoable by the user with one " +
                "Cmd+Z) and fails with a stale-version error if the buffer changed since you read it. " +
                "Open buffers only - use editor_write_file to write a file that is not open.",
            inputSchema = APPLY_EDIT_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf("editor.write"),
            handler = McpToolHandler { args ->
                val api = editorApi ?: return@McpToolHandler apiUnavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                val startLine = args.int("start_line")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: start_line", isError = true)
                val startCol = args.int("start_col")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: start_col", isError = true)
                val endLine = args.int("end_line")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: end_line", isError = true)
                val endCol = args.int("end_col")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: end_col", isError = true)
                // A double, not an int: versions are Long everywhere, and
                // args.int returns null beyond Int range, which would surface
                // as "Missing required argument". Doubles carry these small
                // integers exactly.
                val expectedVersion = args.double("expected_version")?.toLong()
                    ?: return@McpToolHandler McpToolResult("Missing required argument: expected_version", isError = true)
                val newVersion =
                    api.applyEdit(
                        path = path,
                        startLine = startLine,
                        startCol = startCol,
                        endLine = endLine,
                        endCol = endCol,
                        newText = args.string("new_text") ?: "",
                        expectedVersion = expectedVersion,
                    )
                if (newVersion.applied) {
                    McpToolResult("Applied edit to $path (buffer version ${newVersion.newVersion}).")
                } else {
                    McpToolResult("Edit not applied to $path: ${newVersion.reason ?: "unknown reason"}.", isError = true)
                }
            },
        ),
        McpToolDefinition.withRbac(
            name = "editor_inline_edit",
            description = "AI-rewrite a range of an OPEN editor buffer and return the proposed " +
                "replacement WITHOUT applying it (spends AI tokens - not a read). Apply it with " +
                "editor_apply_edit if it looks right. `path` optional: defaults to the focused " +
                "editor. Selection optional: defaults to the user's current selection, else the " +
                "caret's whole line.",
            inputSchema = INLINE_EDIT_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf("ai.edit"),
            handler = McpToolHandler { args ->
                val api = editorApi ?: return@McpToolHandler apiUnavailable()
                val prompt = args.string("prompt")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: prompt", isError = true)
                val gateway =
                    context.getPluginAPI(AiGatewayAPI::class.java)
                        ?: return@McpToolHandler McpToolResult("No AI gateway available (configure an AI provider).", isError = true)
                // Resolve the target buffer and the range to rewrite.
                val target: InlineEditTarget
                val path = args.string("path")
                if (path != null) {
                    val snap =
                        api.readBuffer(path)
                            ?: return@McpToolHandler McpToolResult("No open editor buffer for: $path", isError = true)
                    target =
                        InlineEditTarget(
                            path,
                            snap.content,
                            snap.version,
                            editor?.detectLanguage(path).orEmpty(),
                            null, null, null, null,
                        )
                } else {
                    val doc =
                        api.focusedDocument()
                            ?: return@McpToolHandler McpToolResult("No focused editor document; pass `path`.", isError = true)
                    target =
                        InlineEditTarget(
                            doc.path,
                            doc.content,
                            doc.version,
                            doc.language,
                            doc.selectionStartLine,
                            doc.selectionStartCol,
                            doc.selectionEndLine,
                            doc.selectionEndCol,
                        )
                }
                val lines = target.content.split("\n")
                val sL = args.int("start_line")
                val eL = args.int("end_line")
                var sL1 = 1
                var sC1 = 1
                var eL1 = 1
                var eC1 = 1
                if (sL != null && eL != null) {
                    sL1 = sL
                    sC1 = args.int("start_col") ?: 1
                    eL1 = eL
                    // End of line, like the whole-file branch below: a default
                    // of 1 would silently drop the last line from the range.
                    eC1 = args.int("end_col") ?: lineLength(lines, eL1)
                } else if (target.selStartLine != null && target.selEndLine != null) {
                    sL1 = target.selStartLine!!
                    sC1 = target.selStartCol!!
                    eL1 = target.selEndLine!!
                    eC1 = target.selEndCol!!
                } else {
                    // No explicit range and no selection: the whole file.
                    val last = lines.size
                    eL1 = last
                    eC1 = lineLength(lines, last)
                }
                val selectionText =
                    rangeText(lines, sL1, sC1, eL1, eC1)
                        ?: return@McpToolHandler McpToolResult("Selection range is out of bounds.", isError = true)
                val request = AiInlineEditService.buildRequest(prompt, selectionText, target.language)
                val reply =
                    try {
                        gateway.complete(request).getOrThrow()
                    } catch (e: Exception) {
                        return@McpToolHandler McpToolResult("AI request failed: ${e.message}", isError = true)
                    }
                val replacement = AiInlineEditService.stripFences(reply.text)
                if (replacement.isBlank()) {
                    return@McpToolHandler McpToolResult("The model returned no replacement.", isError = true)
                }
                McpToolResult(
                    "Proposed replacement for ${target.path} (buffer_version ${target.version}, " +
                        "lines $sL1:$sC1-$eL1:$eC1), NOT applied. Apply with editor_apply_edit:\n" +
                        "path: ${target.path}\nstart_line: $sL1\nstart_col: $sC1\nend_line: $eL1\nend_col: $eC1\n" +
                        "expected_version: ${target.version}\n---\n$replacement",
                )
            },
        ),
        McpToolDefinition(
            name = "editor_open_split",
            description = "Open a file in a new split pane beside the current editor. Read-only UI " +
                "action: no file content is changed.",
            inputSchema = pathSchema("Path of the file to open in a split."),
            handler = McpToolHandler { args ->
                val api = editorApi ?: return@McpToolHandler apiUnavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                if (api.openSplit(path)) McpToolResult("Opened $path in a split view.")
                else McpToolResult("Could not open a split for $path.", isError = true)
            },
        ),
        McpToolDefinition.withRbac(
            name = "ai_compose",
            description = "Start an AI composer session: the agent works the task in its own tab, " +
                "accumulating reviewable edit proposals (it never applies them itself). Returns the " +
                "session id. Poll ai_compose_status; accept with ai_compose_accept. Spends AI tokens.",
            inputSchema =
                """{"type":"object","properties":{"task":{"type":"string","description":"The task for the agent."},"session_id":{"type":"string","description":"Optional: continue an existing session with a new task instead of starting a new one."}},"required":["task"]}""",
            readOnly = false,
            requiredPermissions = listOf("ai.compose"),
            handler = McpToolHandler { args ->
                val agent = composerAgent ?: return@McpToolHandler composerUnavailable()
                if (agent.gateway() == null) {
                    return@McpToolHandler McpToolResult("No AI provider configured (Secret Manager panel, AI section).", isError = true)
                }
                val task = args.string("task")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: task", isError = true)
                val sessions = composerSessions ?: return@McpToolHandler composerUnavailable()
                val id = args.string("session_id") ?: newComposerSessionId()
                val existing = sessions.read(id)
                // The editor selection at the moment the task was asked for -
                // the same context the composer tab shows and works against.
                val selection = agent.captureSelection()
                sessions.put(
                    existing?.copy(
                        task = task,
                        status = "idle",
                        statusMessage = "",
                        pendingStart = false,
                        selection = selection ?: existing.selection,
                    ) ?: ComposerSessionData(id, task, composerTitleFor(task), selection = selection),
                )
                // Started HERE rather than left as a pendingStart flag for the
                // tab to notice: refocusing an already-open composer tab
                // creates no component, so a flag-driven start never fired.
                agent.start(id, task)
                context.splitViewOperations?.openTab(ComposerTabData(id, composerTitleFor(task)))
                McpToolResult(
                    "Composer session $id started for: $task\n" +
                        "Track with ai_compose_status(session_id=\"$id\"); " +
                        "accept results with ai_compose_accept.",
                )
            },
        ),
        McpToolDefinition(
            name = "ai_compose_status",
            description = "Read a composer session's progress: status, the agent's latest reply, and " +
                "every proposed edit with its path, range and state (pending/accepted/rejected/failed).",
            inputSchema =
                """{"type":"object","properties":{"session_id":{"type":"string","description":"Session id from ai_compose."}},"required":["session_id"]}""",
            readOnly = true,
            handler = McpToolHandler { args ->
                val id = args.string("session_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: session_id", isError = true)
                val sessions = composerSessions ?: return@McpToolHandler composerUnavailable()
                // The LIVE session, not the persisted copy: status is polled
                // while a run is in flight, and the persisted copy trails it.
                val data = sessions.read(id)
                    ?: return@McpToolHandler McpToolResult("No composer session: $id", isError = true)
                val counts =
                    data.proposals.groupingBy { it.status }.eachCount()
                val statusSuffix = if (data.statusMessage.isNotEmpty()) " - ${data.statusMessage}" else ""
                val last = data.messages.lastOrNull { it.role == "assistant" }?.text
                val proposalLines =
                    data.proposals.joinToString("\n") { p ->
                        "  ${p.id} [${p.status}] ${p.path} lines ${p.startLine}:${p.startCol}-${p.endLine}:${p.endCol}" +
                            (if (p.statusMessage.isNotEmpty()) " (${p.statusMessage})" else "")
                    }
                McpToolResult(
                    "status: ${data.status}$statusSuffix\n" +
                        "task: ${data.task}\n" +
                        "proposals: ${data.proposals.size} total; " +
                        counts.entries.joinToString(", ") { "${it.key}=${it.value}" } + "\n" +
                        (last?.let { "latest_reply:\n$it\n" } ?: "") +
                        "proposals_list:\n$proposalLines",
                )
            },
        ),
        McpToolDefinition.withRbac(
            name = "ai_compose_accept",
            description = "Apply a composer session's proposed edits through the version-guarded editor " +
                "buffer API (one undo step each). Accepts every pending proposal (plus any stuck " +
                "in 'applying' from an interrupted accept), or only those whose path is in the " +
                "comma-separated `paths`.",
            inputSchema =
                """{"type":"object","properties":{"session_id":{"type":"string","description":"Session id from ai_compose."},"paths":{"type":"string","description":"Optional comma-separated file paths to restrict the accept to."}},"required":["session_id"]}""",
            readOnly = false,
            requiredPermissions = listOf("ai.compose"),
            handler = McpToolHandler { args ->
                val agent = composerAgent ?: return@McpToolHandler composerUnavailable()
                val api = agent.editorApi
                val id = args.string("session_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: session_id", isError = true)
                val sessions = composerSessions ?: return@McpToolHandler composerUnavailable()
                val data = sessions.read(id)
                    ?: return@McpToolHandler McpToolResult("No composer session: $id", isError = true)
                val only =
                    args.string("paths")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                // "applying" is included: it is transient, set while an accept is in flight,
                // so a proposal interrupted mid-apply (app closed) must be
                // re-acceptable here rather than stranded.
                val pending = data.proposals.filter { it.status == "pending" || it.status == "applying" }
                val targets =
                    if (only == null) pending else pending.filter { it.path in only || it.path.substringAfterLast('/') in only }
                if (targets.isEmpty()) {
                    return@McpToolHandler McpToolResult("Nothing pending to accept in session $id.")
                }
                // Apply per file, in DESCENDING start-line order: an edit shifts every line
                // below it, so bottom-up keeps every recorded range valid.
                // The version is re-read before each apply, because the batch
                // itself moves it on every success - something the recorded
                // value cannot know. What the re-read must EQUAL is
                // applyEdit's own reported newVersion from the previous step
                // (and, before this batch has touched the file, the
                // proposal's recorded version). The earlier shape counted the
                // applies and assumed each bumped the document by exactly
                // one; the API already answers that question, and its answer
                // stays right whatever document.replace does to the version.
                // Anything else in the gap is an outside edit, and the rest of
                // that file's group fails rather than being applied over.
                val outcomes = HashMap<String, Pair<String, String>>()
                for ((path, fileTargets) in targets.groupBy { it.path }) {
                    val ordered =
                        fileTargets.sortedWith(
                            compareByDescending<ComposerProposal> { it.startLine }
                                .thenByDescending { it.startCol },
                        )
                    // The version this batch has moved the file to; null
                    // until it has applied anything.
                    var appliedTo: Long? = null
                    var aborted = ""
                    for (p in ordered) {
                        if (aborted.isNotEmpty()) {
                            outcomes[p.id] = "failed" to aborted
                            continue
                        }
                        val current = api.readBuffer(path)?.version
                        if (current == null) {
                            aborted = "no open buffer for this path"
                            outcomes[p.id] = "failed" to aborted
                            continue
                        }
                        if (current != (appliedTo ?: p.expectedVersion)) {
                            aborted = "buffer changed while this batch was applying - re-run the task"
                            outcomes[p.id] = "failed" to aborted
                            continue
                        }
                        val r =
                            api.applyEdit(
                                path = path,
                                startLine = p.startLine,
                                startCol = p.startCol,
                                endLine = p.endLine,
                                endCol = p.endCol,
                                newText = p.newText,
                                expectedVersion = current,
                            )
                        if (r.applied) {
                            outcomes[p.id] = "accepted" to "buffer version ${r.newVersion}"
                            // The api allows applyEdit to apply without
                            // reporting a version. Stop the group there rather
                            // than guessing one: the ranges below are still
                            // valid, but nothing can vouch for the state they
                            // would land on.
                            appliedTo = r.newVersion
                            if (appliedTo == null) {
                                aborted = "the editor applied an edit without reporting the new buffer version"
                            }
                        } else {
                            aborted = r.reason ?: "not applied"
                            outcomes[p.id] = "failed" to aborted
                        }
                    }
                }
                val updated =
                    data.proposals.map { p ->
                        outcomes[p.id]?.let { (status, message) ->
                            p.copy(status = status, statusMessage = message)
                        } ?: p
                    }
                // Merged into the live session by id: the run may have added
                // proposals while these were being applied.
                val byId = updated.associateBy { it.id }
                sessions.put(
                    (sessions.snapshot(id) ?: data).let { live ->
                        live.copy(proposals = live.proposals.map { byId[it.id] ?: it })
                    },
                )
                // Last action on an MCP-only session: nothing left to review
                // and no tab watching, so the session can go.
                sessions.maybeCloseIfIdle(id)
                val acceptedCount = targets.count { t -> updated.first { it.id == t.id }.status == "accepted" }
                McpToolResult(
                    "Accepted $acceptedCount of ${targets.size} proposal(s) in session $id. " +
                        "Failures: " +
                        (updated.filter { it.status == "failed" }.joinToString { "${it.path} (${it.statusMessage})" }.ifEmpty { "none" }),
                )
            },
        ),
        McpToolDefinition.withRbac(
            name = "ai_compose_stop",
            description = "Stop a running composer session. Proposals recorded so far remain reviewable.",
            inputSchema =
                """{"type":"object","properties":{"session_id":{"type":"string","description":"Session id from ai_compose."}},"required":["session_id"]}""",
            readOnly = false,
            requiredPermissions = listOf("ai.compose"),
            handler = McpToolHandler { args ->
                val agent = composerAgent ?: return@McpToolHandler composerUnavailable()
                val id = args.string("session_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: session_id", isError = true)
                agent.stop(id)
                McpToolResult("Composer session $id stop requested.")
            },
        ),
    )

    /** Disk-only overwrite: serialize all physical-target buffers without marking their edits saved. */
    private suspend fun writeToolFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        fun commit(): Boolean =
            try {
                writeToDisk(path, content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        val target = try {
            File(path).toPath().toAbsolutePath()
        } catch (_: Exception) {
            return@withContext false
        }
        // Registry keys are only absolute spellings. Resolve aliases on IO for
        // locking, while passing the original disk argument unchanged to the writer.
        val direct = EditorBufferRegistry.find(target.toString())
        val buffers = EditorBufferRegistry.all().filter { buffer ->
            buffer === direct || refersToWriteTarget(buffer.path, target)
        }.distinct().sortedBy { it.path }
        val locked = mutableListOf<EditorBuffer>()
        try {
            // Different spellings can produce several buffers for one physical
            // file. A stable order prevents overlapping tool writes deadlocking.
            for (buffer in buffers) {
                buffer.saveMutex.lock()
                locked += buffer
            }
            commit()
        } finally {
            for (buffer in locked.asReversed()) buffer.saveMutex.unlock()
        }
    }

    private fun refersToWriteTarget(bufferPath: String, target: Path): Boolean = try {
        val candidate = File(bufferPath).toPath().toAbsolutePath()
        val sameExistingFile = try {
            Files.isSameFile(candidate, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        sameExistingFile || realWriteLocation(target)?.let { it == realWriteLocation(candidate) } == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /** Use the writer's link resolution, including dangling final links and cycle checks. */
    private fun realWriteLocation(path: Path): Path? = try {
        val target = AtomicFileWrite.resolveLink(path)
        try {
            target.toRealPath()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            target.parent?.toRealPath()?.resolve(target.fileName)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun unavailable(): McpToolResult =
        McpToolResult("Editor content provider unavailable in this context.", isError = true)

    private fun apiUnavailable(): McpToolResult =
        McpToolResult("Editor buffer API unavailable in this context.", isError = true)

    private fun composerUnavailable(): McpToolResult =
        McpToolResult("Composer is unavailable on this host version.", isError = true)

    /** The buffer a `editor_inline_edit` call rewrites, plus the user's selection if any. */
    private data class InlineEditTarget(
        val path: String,
        val content: String,
        val version: Long,
        val language: String,
        val selStartLine: Int?,
        val selStartCol: Int?,
        val selEndLine: Int?,
        val selEndCol: Int?,
    )

    private fun pathSchema(desc: String): String =
        """{"type":"object","properties":{"path":{"type":"string","description":"$desc"}},"required":["path"]}"""

    private companion object {
        const val WRITE_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"File path to write."},"content":{"type":"string","description":"New file content."}},"required":["path","content"]}"""

        const val INLINE_EDIT_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"Open editor file (absolute or project-relative). Optional: defaults to the focused editor."},"prompt":{"type":"string","description":"What to do to the selected code."},"start_line":{"type":"integer","description":"1-based range start line. Optional."},"start_col":{"type":"integer","description":"1-based range start column. Optional."},"end_line":{"type":"integer","description":"1-based range end line. Optional."},"end_col":{"type":"integer","description":"1-based range end column (exclusive). Optional."}},"required":["prompt"]}"""

        const val APPLY_EDIT_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"Path of the open editor file (absolute or project-relative)."},"start_line":{"type":"integer","description":"1-based start line."},"start_col":{"type":"integer","description":"1-based start column."},"end_line":{"type":"integer","description":"1-based end line."},"end_col":{"type":"integer","description":"1-based end column (exclusive end of the replaced range)."},"new_text":{"type":"string","description":"Replacement text."},"expected_version":{"type":"integer","description":"buffer_version from editor_read_buffer/editor_get_selection; the edit is rejected when the buffer moved on."}},"required":["path","start_line","start_col","end_line","end_col","new_text","expected_version"]}"""
    }
}

/**
 * The exclusive end column of 1-based line [line]: its length plus 1, so a
 * range ending there covers the WHOLE line. (The old `line in lines.indices`
 * test was off by one - 1-based lines against 0-based indices - so the last
 * line, the only one ever asked for, fell through to 1 and dropped itself
 * from the range.)
 */
internal fun lineLength(lines: List<String>, line: Int): Int =
    if (line in 1..lines.size) lines[line - 1].length + 1 else 1

/**
 * 1-based (line, col) inclusive-ish range to text; the editor's end
 * semantics. Top-level and pure so the off-by-ones have a home to be pinned.
 */
internal fun rangeText(
    lines: List<String>,
    startLine: Int,
    startCol: Int,
    endLine: Int,
    endCol: Int,
): String? {
    if (startLine !in 1..lines.size || endLine !in 1..lines.size || startLine > endLine) return null
    val start = (startCol - 1).coerceIn(0, lines[startLine - 1].length)
    val end = (endCol - 1).coerceIn(0, lines[endLine - 1].length)
    val sb = StringBuilder()
    for (l in startLine..endLine) {
        val lo = if (l == startLine) start else 0
        val hi = if (l == endLine) end else lines[l - 1].length
        if (hi > lo) sb.append(lines[l - 1].substring(lo, hi))
        if (l != endLine) sb.append('\n')
    }
    return sb.toString()
}
