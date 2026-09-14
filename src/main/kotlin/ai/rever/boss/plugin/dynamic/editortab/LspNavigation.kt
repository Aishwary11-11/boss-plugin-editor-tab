package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.compose.NavigationResolveResult
import ai.rever.bosseditor.lsp.client.LspClient
import ai.rever.bosseditor.lsp.client.LspMethods
import ai.rever.bosseditor.lsp.config.LspConfiguration
import ai.rever.bosseditor.lsp.config.LspSettingsManager
import ai.rever.bosseditor.lsp.protocol.Position
import ai.rever.bosseditor.lsp.providers.LspNavigationProvider
import ai.rever.bosseditor.lsp.server.LanguageServerConfig
import ai.rever.bosseditor.lsp.server.LanguageServerManager
import ai.rever.bosseditor.lsp.server.LanguageServerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.net.URI

/**
 * Go-to-definition over LSP, for the languages PSI does not cover.
 *
 * The bosseditor library ships a complete LSP stack - transport, client,
 * [LanguageServerManager], [LspNavigationProvider] - and until now nothing
 * constructed any of it. `LanguageServerManager` had no call site at all, so no
 * server was ever spawned and no `initialize` was ever sent; the only code that
 * touched LSP was the settings screen, which PATH-probes the registered servers
 * and shows them as "installed". That is why the feature looked implemented.
 *
 * Cmd+Click resolution itself went to the internal PSI `NavigationManager`,
 * which returns `Unavailable` for anything that is not `.kt`/`.kts`. This class
 * is the other half: the editor keeps PSI for Kotlin (PSI is the only path that
 * can answer ShowUsages, and `navigationResolver` cannot express it - see
 * [NavigationResolveResult]) and routes every other language here.
 *
 * Servers are started lazily, on the first navigation in a file whose language
 * has a registered server, and are reused across files and panels for the life
 * of the plugin.
 */
class LspNavigation {

    /** One manager per language/root keeps one server's initialize budget from blocking another. */
    private val managers = ConcurrentHashMap<ServerKey, LanguageServerManager>()
    @Volatile
    private var disposed = false

    /**
     * Documents announced with didOpen, keyed by the client they were announced to.
     * Each map is concurrent because different language/root locks may reach the same client.
     *
     * Keyed by the client and not only the URI, because a server that dies is
     * replaced: `LanguageServerManager.getOrStartServer` hands back a FRESH
     * process, which has never heard of the document. Sending it a didChange
     * for a document it never opened leaves that file answering NotFound for
     * the rest of the session, and nothing is logged. Comparing identity means
     * a replacement is re-announced instead.
     */
    private val opened = ConcurrentHashMap<LspClient, ConcurrentHashMap<String, ContentFingerprint>>()

    /** The current client for each server; replacing it evicts the dead client's document state. */
    private val clients = ConcurrentHashMap<ServerKey, LspClient>()

    /**
     * Monotonic document version, shared across documents.
     *
     * Per-document monotonicity is all the protocol asks for, and one counter
     * satisfies it for every document at once. It has to be a counter: a clock
     * truncated to Int wraps, and spends half of each cycle negative - below the
     * version didOpen sent - so a server that drops non-increasing changes would
     * quietly keep answering from the snapshot taken when the file was opened.
     */
    private val documentVersion = AtomicInteger(0)

    /** Serialises start + sync per language/root without blocking unrelated servers. */
    private val mutexes = ConcurrentHashMap<ServerKey, Mutex>()

    /** A broken installed server gets one expensive start attempt per cooldown, not one per click. */
    private val startupFailures = FailureCooldown<ServerKey, StartupFingerprint>(START_FAILURE_COOLDOWN_MS)

    private data class ServerKey(val languageId: String, val root: String)

    private data class StartupFingerprint(
        val config: LanguageServerConfig,
        val settings: LspConfiguration,
    )

    private data class ContentFingerprint(val length: Int, val hash: Int)

    /**
     * Resolve the definition of the symbol at [offset], or [NavigationResolveResult.NotFound].
     *
     * Runs on [Dispatchers.IO]. The editor calls its resolver from a
     * `rememberCoroutineScope()`, whose dispatcher is the composition's - the UI
     * thread - and a cold first call here spawns a login shell, stats every PATH
     * entry, then spawns and initializes a server. On the UI thread that is a
     * visible freeze measured in seconds.
     *
     * Operational and linkage failures return NotFound, while cancellation and
     * VM-fatal errors deliberately propagate. A missing or failed server is logged
     * to stderr instead of being silently indistinguishable from an absent result.
     */
    suspend fun resolveDefinition(
        content: String,
        filePath: String,
        offset: Int,
        projectPath: String?,
    ): NavigationResolveResult = withContext(Dispatchers.IO) {
        if (disposed) return@withContext NavigationResolveResult.NotFound
        try {
            resolve(content, filePath, offset, projectPath)
        } catch (cancellation: CancellationException) {
            // Closing the tab mid-resolution is not a failed lookup. Swallowing it
            // would report NotFound to a caller that is already gone, and hide the
            // cancellation from the scope that raised it.
            throw cancellation
        } catch (error: LinkageError) {
            // BossEditor is bundled, but a parent-first host copy can still expose an older API.
            // Treat that compatibility failure like an unavailable resolver; fatal VM Errors pass.
            System.err.println(
                "[LspNavigation] ${error::class.simpleName} resolving '$filePath': ${error.message}",
            )
            NavigationResolveResult.NotFound
        } catch (error: Exception) {
            System.err.println(
                "[LspNavigation] ${error::class.simpleName} resolving '$filePath': ${error.message}",
            )
            NavigationResolveResult.NotFound
        }
    }

    private suspend fun resolve(
        content: String,
        filePath: String,
        offset: Int,
        projectPath: String?,
    ): NavigationResolveResult {
        val settings = LspSettingsManager.instance.configuration.value
        if (!settings.enabled) return NavigationResolveResult.NotFound
        // LspSettingsManager applies both built-in enablement and enabled custom servers to
        // the registry. Trust the selected, enabled config here; a separate disabled-language
        // check would incorrectly reject a custom replacement for a disabled built-in server.
        val config = LanguageServerRegistry.getConfigForFile(filePath) ?: return NavigationResolveResult.NotFound
        // A server rooted at the wrong directory resolves nothing outside the file
        // itself, so prefer the project and fall back to the file's own folder.
        val root = projectPath?.takeIf { it.isNotBlank() }
            ?: File(filePath).parentFile?.absolutePath
            ?: return NavigationResolveResult.NotFound

        // The server binary has to exist before we try to run it. Without this a
        // missing `pylsp`/`typescript-language-server` costs a failed spawn and
        // a 60s initialize timeout on every single click. Relative configured paths
        // resolve from the project root rather than the Dock-launched JVM's CWD.
        //
        // Resolved against the USER's PATH, not this process's - see [launchPath].
        val launchable = launchConfig(config, workingDirectory = root)
            ?: return NavigationResolveResult.NotFound

        val key = ServerKey(config.languageId, canonicalRoot(root))
        val uri = fileUri(filePath)
        val fingerprint = StartupFingerprint(launchable, settings)
        if (startupFailures.isCoolingDown(key, fingerprint)) return NavigationResolveResult.NotFound

        val manager = managers.computeIfAbsent(key) { LanguageServerManager() }
        // Both the manager and this outer lock are per language/root, so a hanging Python
        // initialize cannot consume TypeScript's budget or make it stop the wrong server.
        val client = mutexes.computeIfAbsent(key) { Mutex() }.withLock {
            if (disposed) {
                managers.remove(key, manager)
                runCatching { manager.dispose() }
                return@withLock null
            }
            // Calls that arrived during the original attempt passed the fast check above before
            // it failed; re-check after taking the lock so they do not each retry in sequence.
            if (startupFailures.isCoolingDown(key, fingerprint)) return@withLock null
            val initializeTimeout = settings.initializeTimeoutMs.coerceAtLeast(1L)
            val started = try {
                ownTimeout(initializeTimeout) {
                    manager.getOrStartServer(launchable, key.root)
                }
            } catch (cancellation: CancellationException) {
                // withTimeoutOrNull consumes only its OWN timeout. Cancellation from a tab close
                // reaches this branch and must not blacklist a healthy server.
                throw cancellation
            } catch (error: Exception) {
                startupFailures.record(key, fingerprint)
                // LanguageServerManager installs the client in its map before initialize. Remove
                // that half-started process now; otherwise every cooldown retains one orphan.
                stopFailedServer(manager, config.languageId)
                throw error
            }
            val c = when (started) {
                is TimedResult.Value -> started.value
                TimedResult.TimedOut -> {
                    startupFailures.record(key, fingerprint, START_TIMEOUT_COOLDOWN_MS)
                    stopFailedServer(manager, config.languageId)
                    throw IllegalStateException(
                        "${config.displayName} did not initialize within ${initializeTimeout}ms",
                    )
                }
            }
            if (!c.isInitialized) {
                startupFailures.record(key, fingerprint, START_TIMEOUT_COOLDOWN_MS)
                stopFailedServer(manager, config.languageId)
                throw IllegalStateException("${config.displayName} stopped before document sync")
            }
            // dispose() may have raced the suspend above after clearing the manager map.
            // Dispose this retained manager too so a late start cannot orphan a process.
            if (disposed) {
                managers.remove(key, manager)
                runCatching { manager.dispose() }
                return@withLock null
            }
            startupFailures.remove(key)
            val fresh = trackClient(key, c)
            syncDocument(c, uri, config.languageId, content)
            // Keep the cold-start settle inside the per-server lock. Otherwise a queued click
            // can observe `fresh = false` and send its request before the server is actually ready.
            if (fresh) delay(COLD_START_SETTLE_MS)
            c
        } ?: return NavigationResolveResult.NotFound

        val position = offsetToPosition(content, offset)
        val requested = try {
            ownTimeout(settings.defaultRequestTimeoutMs) {
                LspNavigationProvider(client)
                    .goToDefinition(uri, position)
                    .firstOrNull()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // A transport failure marks the client dead; a decode/provider error on a still-live
            // shared server must not tear it out from under every other tab in this workspace.
            if (!client.isInitialized) stopClientIfCurrent(key, client, manager, config.languageId)
            throw error
        }
        val location = when (requested) {
            is TimedResult.Value -> requested.value ?: return NavigationResolveResult.NotFound
            TimedResult.TimedOut -> {
                // A slow project is not a dead process. Keep an initialized shared server warm so
                // the next click can benefit from the work it has already done.
                System.err.println(
                    "[LspNavigation] definition timed out after ${settings.defaultRequestTimeoutMs}ms for '$filePath'",
                )
                if (!client.isInitialized) stopClientIfCurrent(key, client, manager, config.languageId)
                return NavigationResolveResult.NotFound
            }
        }

        val targetPath = uriToPath(location.uri)
            ?.let { restoreRootAlias(it, key.root, root) }
            ?: return NavigationResolveResult.NotFound
        // LSP positions are 0-based; NavigationTarget - and so openFileAtPosition,
        // which both this and the PSI path feed - is 1-based on both axes.
        return NavigationResolveResult.Found(
            filePath = targetPath,
            line = location.range.start.line + 1,
            column = location.range.start.character + 1,
        )
    }

    private suspend fun stopFailedServer(manager: LanguageServerManager, languageId: String) {
        // The caller may be handling our timeout, whose coroutine is already cancelled. Cleanup
        // still has to reach the manager so the half-initialized child process is not retained.
        withContext(NonCancellable) {
            runCatching {
                withTimeoutOrNull(STOP_FAILED_SERVER_TIMEOUT_MS) {
                    manager.stopServer(languageId)
                }
            }
        }
    }

    /** Stop only the dead client observed by this request, never a replacement another tab started. */
    private suspend fun stopClientIfCurrent(
        key: ServerKey,
        client: LspClient,
        manager: LanguageServerManager,
        languageId: String,
    ) {
        mutexes.computeIfAbsent(key) { Mutex() }.withLock {
            if (clients[key] !== client) return@withLock
            stopFailedServer(manager, languageId)
            clients.remove(key, client)
            opened.remove(client)
        }
    }

    internal sealed interface TimedResult<out T> {
        data class Value<T>(val value: T) : TimedResult<T>
        data object TimedOut : TimedResult<Nothing>
    }

    /** Distinguishes this operation's timeout from cancellation by an ancestor scope. */
    internal suspend fun <T> ownTimeout(timeoutMs: Long, block: suspend () -> T): TimedResult<T> =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            TimedResult.Value(block())
        } ?: TimedResult.TimedOut

    /**
     * Give the server the buffer as it stands right now.
     *
     * Full-text sync when the buffer changes rather than incremental didChange:
     * this is a user-gesture path, not a keystroke path, and sending the whole
     * buffer is what makes the answer reflect UNSAVED edits. Byte-identical repeat
     * clicks send nothing. Resolving against whatever is on disk is the failure
     * mode worth paying a few KB to avoid.
     *
     * Sent directly on [LspClient.notify] instead of through LspDocumentSyncManager,
     * which would drag in a semantic-token provider this path has no use for.
     */
    internal fun syncDocument(client: LspClient, uri: String, languageId: String, content: String) {
        // Keep the document open for the lifetime of this shared client. Editor-tab does not
        // own a global document reference count, so didClose from one tab could invalidate the
        // same URI while another split/tab is still using it.
        val known = opened.computeIfAbsent(client) { ConcurrentHashMap() }
        // Keep constant-size state rather than retaining every full editor buffer. Length plus
        // String's content hash makes an accidental collision vanishingly unlikely here.
        val current = ContentFingerprint(content.length, content.hashCode())
        val previous = known[uri]
        if (previous == null) {
            client.notify(LspMethods.DID_OPEN, didOpen(uri, languageId, content))
            known[uri] = current
        } else if (previous != current) {
            client.notify(LspMethods.DID_CHANGE, didChange(uri, content))
            known[uri] = current
        }
    }

    private fun trackClient(key: ServerKey, client: LspClient): Boolean {
        val previous = clients.put(key, client)
        previous?.takeIf { it !== client }?.let(opened::remove)
        return previous !== client
    }

    /** Test seam for the restart eviction invariant without constructing a real manager. */
    internal fun trackClient(languageId: String, root: String, client: LspClient): Boolean =
        trackClient(ServerKey(languageId, root), client)

    internal fun didOpen(uri: String, languageId: String, content: String): JsonElement =
        buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri)
                put("languageId", languageId)
                put("version", documentVersion.incrementAndGet())
                put("text", content)
            })
        }

    internal fun didChange(uri: String, content: String): JsonElement =
        buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri)
                put("version", documentVersion.incrementAndGet())
            })
            putJsonArray("contentChanges") {
                // No `range` key: this is a whole-document replacement.
                addJsonObject { put("text", content) }
            }
        }

    /**
     * Rewrite a registry config so it can actually be launched, or null when the
     * server is not installed anywhere the user can see.
     *
     * Two separate problems, both invisible until you look at the app's environment:
     *
     * 1. BOSS is launched from the Dock, so it inherits launchd's PATH -
     *    `/usr/bin:/bin:/usr/sbin:/sbin`. Homebrew, npm-global, pyenv shims and
     *    ~/.local/bin are all absent, and the registry's command is a bare name.
     *    A perfectly installed server reads as "not found", so the command has to
     *    become an absolute path.
     * 2. The server process inherits that same stunted PATH. An absolute path
     *    settles the exec, but most of these servers are node scripts whose
     *    `#!/usr/bin/env node` line then fails to find node - which is not on the
     *    app's PATH either. The environment carries the real PATH for that.
     */
    internal fun launchConfig(
        config: LanguageServerConfig,
        path: String = launchPath,
        isWindows: Boolean = isWindowsPlatform(),
        pathExtensions: String = System.getenv("PATHEXT").orEmpty(),
        commandInterpreter: String = windowsCommandInterpreter(),
        workingDirectory: String? = null,
    ): LanguageServerConfig? {
        val name = config.command.firstOrNull() ?: return null
        val pathSeparator = if (isWindows) ';' else File.pathSeparatorChar
        val effectivePath = mergePaths(config.environment["PATH"], path, emptyList(), pathSeparator)
        val exe = findOnPath(name, effectivePath, isWindows, pathExtensions, workingDirectory) ?: return null
        val arguments = config.command.drop(1)
        val command =
            if (isWindows && (exe.endsWith(".cmd", ignoreCase = true) || exe.endsWith(".bat", ignoreCase = true))) {
                listOf(commandInterpreter, "/d", "/s", "/c", windowsBatchCommand(exe, arguments))
            } else {
                listOf(exe) + arguments
            }
        return config.copy(
            command = command,
            environment = config.environment + mapOf("PATH" to effectivePath),
        )
    }

    /** Free every server process. Called when the plugin is disposed. */
    fun dispose() {
        disposed = true
        // BossEditor's dispose is non-blocking: it cancels its scope and launches stopAll on IO.
        managers.values.forEach { manager -> runCatching { manager.dispose() } }
        managers.clear()
        mutexes.clear()
        opened.clear()
        clients.clear()
        startupFailures.clear()
    }

    companion object {
        /**
         * How long a freshly started server gets before the first question.
         *
         * Servers answer as soon as they are initialized, but several keep
         * loading the project behind that - and tsserver in particular answers a
         * definition request made in the gap from the open file alone. The answer
         * is not an error and does not look like one: clicking a call resolves to
         * the local `import` binding instead of the exported declaration, which is
         * a real location in a real file, just the wrong one.
         *
         * Because servers start lazily on the click, the FIRST navigation in a
         * language always lands in that gap without this. Measured against
         * typescript-language-server, the shallow answer persists for under a
         * second; the margin is for a colder machine or a larger project. Paid
         * once per language per session, and only when this call is the one that
         * started the server.
         */
        private const val COLD_START_SETTLE_MS = 1_500L
        private const val STOP_FAILED_SERVER_TIMEOUT_MS = 2_000L
        private const val START_TIMEOUT_COOLDOWN_MS = 10_000L
        private const val START_FAILURE_COOLDOWN_MS = 5 * 60 * 1_000L

        /**
         * One instance for the whole plugin.
         *
         * [EditorTabComponent] is per TAB, and a manager per tab would spawn one
         * language-server process per open file - a `LanguageServerManager` already
         * keys its servers by language and reuses them, which is only worth anything
         * if every tab shares the same one. Released by [disposeShared] on plugin
         * unload.
         */
        @Volatile
        private var sharedInstance: LspNavigation? = null

        val shared: LspNavigation
            get() = sharedInstance ?: synchronized(this) {
                sharedInstance ?: LspNavigation().also { sharedInstance = it }
            }

        /** Stop every language server. Plugin unload only. */
        fun disposeShared() {
            synchronized(this) {
                sharedInstance?.dispose()
                // Keep the disposed instance installed. A stale tab coroutine reaching `shared`
                // during unload then gets a fast NotFound instead of creating an orphan manager.
            }
        }

        /** Re-arm the singleton when the host registers this plugin again on the same classloader. */
        fun resetShared() {
            synchronized(this) {
                sharedInstance?.dispose()
                sharedInstance = null
            }
        }

        /**
         * PATH as the user's shell sees it.
         *
         * Asks a non-interactive login shell rather than an interactive one. This reads login
         * profiles while avoiding prompts and terminal setup from `.zshrc`; common tool folders
         * are appended as a floor for tools configured only by interactive profiles.
         */
        // Process-wide by design. Installing into a brand-new PATH directory takes a BOSS restart;
        // settings can still point directly at an executable and take effect on recomposition.
        private val launchPath: String by lazy { buildLaunchPath() }

        private fun buildLaunchPath(): String {
            val fromShell = if (isWindowsPlatform()) null else runCatching {
                val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/sh"
                val output = kotlin.io.path.createTempFile("boss-lsp-path", ".txt").toFile()
                try {
                    val p = ProcessBuilder(shell, "-lc", "printf '${PATH_SENTINEL}%s' \"\$PATH\"")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(output)
                        .start()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        null
                    } else {
                        output.readText()
                            .substringAfterLast(PATH_SENTINEL, missingDelimiterValue = "")
                            .trim()
                            .takeIf { it.isNotBlank() }
                    }
                } finally {
                    output.delete()
                }
            }.getOrNull()

            val fallbacks = if (isWindowsPlatform()) {
                emptyList()
            } else {
                listOf(
                    "/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin",
                    System.getProperty("user.home") + "/.local/bin",
                    System.getProperty("user.home") + "/.bun/bin",
                    System.getProperty("user.home") + "/.cargo/bin",
                    System.getProperty("user.home") + "/go/bin",
                )
            }
            val current = System.getenv("PATH").orEmpty()
            return mergePaths(fromShell, current, fallbacks)
        }

        internal fun mergePaths(
            fromShell: String?,
            current: String,
            fallbacks: List<String>,
            separator: Char = File.pathSeparatorChar,
        ): String =
            (listOfNotNull(fromShell, current) + fallbacks)
                .flatMap { it.split(separator) }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(separator.toString())

        /** First executable named [command] across [path], including PATHEXT launchers on Windows. */
        internal fun findOnPath(
            command: String,
            path: String,
            isWindows: Boolean = isWindowsPlatform(),
            pathExtensions: String = System.getenv("PATHEXT").orEmpty(),
            workingDirectory: String? = null,
        ): String? {
            val direct = command.contains('/') || command.contains('\\')
            val extensions =
                if (isWindows && File(command).extension.isEmpty()) {
                    pathExtensions.ifBlank { ".COM;.EXE;.BAT;.CMD" }
                        .split(';')
                        .filter { it.isNotBlank() }
                        .flatMap { listOf(it.lowercase(), it) }
                        .distinct()
                } else {
                    emptyList()
                }
            if (direct) {
                val commandFile = File(command).let { file ->
                    if (file.isAbsolute || workingDirectory == null) file else File(workingDirectory, command)
                }
                return (sequenceOf(commandFile) + extensions.asSequence().map { File(commandFile.path + it) })
                    .firstOrNull { it.isFile && (isWindows || it.canExecute()) }
                    ?.toPath()
                    ?.normalize()
                    ?.toFile()
                    ?.absolutePath
            }

            val names = sequenceOf(command) + extensions.asSequence().map { command + it }
            return path.split(if (isWindows) ';' else File.pathSeparatorChar).asSequence()
                .filter { it.isNotBlank() }
                .flatMap { directory -> names.map { name -> File(directory, name) } }
                .firstOrNull { it.isFile && (isWindows || it.canExecute()) }
                ?.absolutePath
        }

        internal fun windowsBatchCommand(executable: String, arguments: List<String>): String =
            (listOf("call", quoteForCmd(executable)) + arguments.map(::quoteForCmd)).joinToString(" ")

        private fun quoteForCmd(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

        private fun isWindowsPlatform(): Boolean =
            System.getProperty("os.name").contains("windows", ignoreCase = true)

        private fun windowsCommandInterpreter(): String =
            System.getenv("ComSpec")
                ?.takeIf { it.isNotBlank() && File(it).isFile }
                ?: System.getenv("SystemRoot")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(it, "System32/cmd.exe").absolutePath }
                ?: "cmd.exe"

        private fun canonicalRoot(root: String): String =
            runCatching { File(root).canonicalPath }.getOrElse { File(root).absolutePath }

        /** Preserve the user's symlinked project spelling when opening a server-returned target. */
        internal fun restoreRootAlias(targetPath: String, canonicalRoot: String, requestedRoot: String): String {
            val target = File(targetPath).toPath().normalize()
            val canonical = File(canonicalRoot).toPath().normalize()
            if (!target.startsWith(canonical)) return targetPath
            return File(requestedRoot).absoluteFile.toPath()
                .resolve(canonical.relativize(target))
                .normalize()
                .toString()
        }

        private const val PATH_SENTINEL = "__BOSS_PATH__"

        /**
         * Kotlin stays on PSI.
         *
         * `navigationResolver` REPLACES the internal NavigationManager rather than
         * layering over it, and it can only answer Found/NotFound - there is no
         * ShowUsages variant. Handing Kotlin to LSP would therefore trade a working
         * find-usages popup for a definition jump. Deciding per file keeps both.
         */
        fun usesPsi(filePath: String): Boolean =
            filePath.isBlank() || filePath.endsWith(".kt") || filePath.endsWith(".kts")

        /** Install the replacing resolver only where a server can actually answer. */
        fun usesLsp(filePath: String): Boolean =
            shouldUseLsp(filePath, LanguageServerRegistry.getConfigForFile(filePath) != null)

        internal fun shouldUseLsp(filePath: String, serverRegistered: Boolean): Boolean =
            !usesPsi(filePath) && serverRegistered

        /**
         * Offset -> zero-based line/character.
         *
         * Counts UTF-16 code units, which is what a Kotlin String index already is
         * and what LSP defaults to, so a line containing an emoji or any astral-plane
         * character still lands on the right column.
         */
        internal fun offsetToPosition(content: String, offset: Int): Position {
            val end = offset.coerceIn(0, content.length)
            var line = 0
            var lineStart = 0
            var i = 0
            while (i < end) {
                if (content[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
                i++
            }
            return Position(line = line, character = end - lineStart)
        }

        /** `file:///a/b.ts` -> `/a/b.ts`, with percent-escapes resolved. */
        internal fun uriToPath(uri: String): String? =
            runCatching { File(URI(uri)).absolutePath }.getOrNull()

        /** Canonical, escaped file URI with the empty authority LSP clients conventionally use. */
        internal fun fileUri(path: String): String =
            "file://${runCatching { File(path).canonicalFile }.getOrElse { File(path).absoluteFile }.toURI().rawPath}"

    }

    /** Small, testable negative cache whose fingerprint makes every settings change a retry. */
    internal class FailureCooldown<K : Any, F : Any>(
        cooldownMs: Long,
        private val nowNanos: () -> Long = System::nanoTime,
    ) {
        private data class Entry<F>(val fingerprint: F, val retryAtNanos: Long)

        private val defaultCooldownMs = cooldownMs
        private val entries = ConcurrentHashMap<K, Entry<F>>()

        fun isCoolingDown(key: K, fingerprint: F): Boolean {
            val entry = entries[key] ?: return false
            if (entry.fingerprint == fingerprint && nowNanos() - entry.retryAtNanos < 0L) return true
            entries.remove(key, entry)
            return false
        }

        fun record(key: K, fingerprint: F, cooldownMs: Long = defaultCooldownMs) {
            entries[key] = Entry(fingerprint, nowNanos() + cooldownMs * 1_000_000L)
        }

        fun remove(key: K) {
            entries.remove(key)
        }

        fun clear() {
            entries.clear()
        }
    }
}
