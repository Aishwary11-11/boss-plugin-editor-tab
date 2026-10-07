package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.compose.NavigationResolveResult
import ai.rever.bosseditor.lsp.client.LspClient
import ai.rever.bosseditor.lsp.client.LspClientState
import ai.rever.bosseditor.lsp.client.LspMethods
import ai.rever.bosseditor.lsp.config.LspSettingsManager
import ai.rever.bosseditor.lsp.protocol.InitializeParams
import ai.rever.bosseditor.lsp.protocol.InitializeResult
import ai.rever.bosseditor.lsp.protocol.ServerCapabilities
import ai.rever.bosseditor.lsp.server.LanguageServerConfig
import ai.rever.bosseditor.lsp.server.LanguageServerRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fake LSP stdio server for the warm-up / hover-failure tests above.
 *
 * Answers `initialize`, then behaves per the MODE FILE named by
 * FAKE_LSP_MODE_FILE (read at startup, so a test can rewrite it between
 * phases): `healthy` answers `textDocument/hover` with a fixed markdown
 * hover, `hang` swallows every later message (a slow server), `die` exits
 * right after the handshake (a server that crashes mid-session). Writes its
 * own PID to FAKE_LSP_PID_FILE so the tests can assert on the PROCESS, not
 * just on the nulls coming back.
 */
private val FAKE_LSP_SERVER_PYTHON = """
    #!/usr/bin/env python3
    import json, os, sys, time

    def read_message():
        headers = {}
        while True:
            line = sys.stdin.buffer.readline()
            if line in (b"\r\n", b"\n", b""):
                break
            if b":" in line:
                k, v = line.split(b":", 1)
                headers[k.strip().lower()] = v.strip()
        n = int(headers.get(b"content-length", b"0"))
        if n <= 0:
            return None
        return sys.stdin.buffer.read(n)

    def send(obj):
        data = json.dumps(obj).encode("utf-8")
        sys.stdout.buffer.write(b"Content-Length: %d\r\n\r\n" % len(data) + data)
        sys.stdout.buffer.flush()

    mode = "healthy"
    mode_file = os.environ.get("FAKE_LSP_MODE_FILE", "")
    if mode_file:
        try:
            with open(mode_file) as f:
                mode = f.read().strip() or "healthy"
        except Exception:
            pass
    pid_file = os.environ.get("FAKE_LSP_PID_FILE", "")
    if pid_file:
        with open(pid_file, "w") as f:
            f.write(str(os.getpid()))

    while True:
        body = read_message()
        if body is None:
            break
        try:
            msg = json.loads(body)
        except Exception:
            continue
        method = msg.get("method")
        msg_id = msg.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": msg_id,
                  "result": {"capabilities": {"hoverProvider": True,
                                               "definitionProvider": True}}})
            if mode == "die":
                # Let the initialized/didOpen notifications land first, then
                # crash: the client must have finished the handshake before
                # the process disappears.
                time.sleep(0.3)
                break
        elif method == "textDocument/hover" and mode == "healthy":
            send({"jsonrpc": "2.0", "id": msg_id,
                  "result": {"contents": {"kind": "markdown",
                                           "value": "def fake_fn(x: int) -> int"}}})
        # everything else (initialized/didOpen/didChange notifications, and
        # hover in hang mode): no answer
    """.trimIndent()

/**
 * The three things that decide whether a click reaches a running server at all:
 * how the command is rewritten, what version a sync claims, and whether a
 * replaced server is told about the document again.
 */
class LspNavigationLaunchTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("lspnav").toFile().also { it.deleteOnExit() }

    private fun serverOn(dir: File, name: String): File =
        File(dir, name).apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }

    private fun config(vararg command: String) = LanguageServerConfig(
        id = "test", displayName = "Test", languageId = "test",
        command = command.toList(), fileExtensions = listOf("test"),
    )

    // ---- launchConfig ---------------------------------------------------
    //
    // BOSS is Dock-launched, so its PATH is /usr/bin:/bin:/usr/sbin:/sbin and
    // the registry's bare command name resolves to nothing. Both halves matter:
    // the absolute path is what lets the server be exec'd, and the PATH in the
    // environment is what lets a `#!/usr/bin/env node` server find its own
    // interpreter once it is running.

    @Test
    fun `the bare command name becomes an absolute path`() {
        val dir = tempDir()
        val exe = serverOn(dir, "fake-language-server")
        val launched = LspNavigation().launchConfig(config("fake-language-server"), dir.absolutePath)
        assertEquals(listOf(exe.absolutePath), launched?.command)
    }

    @Test
    fun `arguments are preserved after the rewritten command`() {
        val dir = tempDir()
        val exe = serverOn(dir, "srv")
        val launched = LspNavigation().launchConfig(config("srv", "--stdio", "-v"), dir.absolutePath)
        assertEquals(listOf(exe.absolutePath, "--stdio", "-v"), launched?.command)
    }

    @Test
    fun `Windows resolves npm cmd launchers through PATHEXT`() {
        val dir = tempDir()
        val launcher = File(dir, "typescript-language-server.cmd").apply { writeText("@echo off\r\n") }
        val launched = LspNavigation().launchConfig(
            config("typescript-language-server", "--stdio"),
            path = dir.absolutePath,
            isWindows = true,
            pathExtensions = ".EXE;.CMD;.BAT",
            commandInterpreter = "C:\\Windows\\System32\\cmd.exe",
        )
        assertEquals(
            listOf(
                "C:\\Windows\\System32\\cmd.exe", "/d", "/s", "/c",
                LspNavigation.windowsBatchCommand(launcher.absolutePath, listOf("--stdio")),
            ),
            launched?.command,
        )
    }

    @Test
    fun `Windows batch payload quotes launcher paths and arguments containing spaces`() {
        assertEquals(
            "call \"C:\\Program Files\\node\\server.cmd\" \"--stdio\" \"a b\"",
            LspNavigation.windowsBatchCommand(
                "C:\\Program Files\\node\\server.cmd",
                listOf("--stdio", "a b"),
            ),
        )
    }

    @Test
    fun `an explicit executable path is accepted without searching PATH`() {
        val exe = serverOn(tempDir(), "custom-server")
        assertEquals(
            exe.absolutePath,
            LspNavigation.findOnPath(exe.absolutePath, path = "", isWindows = false),
        )
    }

    @Test
    fun `the resolved PATH is handed to the server process`() {
        val dir = tempDir()
        serverOn(dir, "srv")
        val launched = LspNavigation().launchConfig(config("srv"), dir.absolutePath)
        assertEquals(dir.absolutePath, launched?.environment?.get("PATH"))
    }

    @Test
    fun `an existing environment survives and its PATH keeps first precedence`() {
        val dir = tempDir()
        serverOn(dir, "srv")
        val configured = tempDir()
        val base = config("srv").copy(environment = mapOf("NODE_ENV" to "test", "PATH" to configured.absolutePath))
        val launched = LspNavigation().launchConfig(base, dir.absolutePath)
        assertEquals("test", launched?.environment?.get("NODE_ENV"))
        assertEquals(
            listOf(configured.absolutePath, dir.absolutePath).joinToString(File.pathSeparator),
            launched?.environment?.get("PATH"),
        )
    }

    @Test
    fun `a server that is not installed yields null rather than a spawn`() {
        // The guard that keeps a missing pylsp from costing a 60s initialize
        // timeout on every single click.
        assertNull(LspNavigation().launchConfig(config("definitely-not-installed-xyz"), tempDir().absolutePath))
    }

    @Test
    fun `an empty command yields null`() {
        assertNull(LspNavigation().launchConfig(config(), tempDir().absolutePath))
    }

    @Test
    fun `an explicit executable path does not require a PATH match`() {
        val exe = serverOn(tempDir(), "custom-server")
        val launched = LspNavigation().launchConfig(config(exe.absolutePath, "--stdio"), "/missing")
        assertEquals(listOf(exe.absolutePath, "--stdio"), launched?.command)
    }

    @Test
    fun `a relative configured executable resolves from the workspace`() {
        val root = tempDir()
        val exe = serverOn(File(root, "tools").apply { mkdirs() }, "custom-server")

        val launched = LspNavigation().launchConfig(
            config("./tools/custom-server", "--stdio"),
            path = "/missing",
            workingDirectory = root.path,
        )

        assertEquals(listOf(exe.absolutePath, "--stdio"), launched?.command)
    }

    @Test
    fun `windows PATHEXT resolves and wraps a command script`() {
        val missing = tempDir()
        val dir = tempDir()
        val script = File(dir, "typescript-language-server.cmd").apply { writeText("@echo off\n") }
        val launched = LspNavigation().launchConfig(
            config("typescript-language-server", "--stdio"),
            path = "${missing.absolutePath};${dir.absolutePath}",
            isWindows = true,
            pathExtensions = ".EXE;.CMD",
            commandInterpreter = "C:\\Windows\\System32\\cmd.exe",
        )

        assertEquals(
            listOf(
                "C:\\Windows\\System32\\cmd.exe",
                "/d",
                "/s",
                "/c",
                "call \"${script.absolutePath}\" \"--stdio\"",
            ),
            launched?.command,
        )
    }

    @Test
    fun `windows lookup checks every semicolon separated path entry`() {
        val first = tempDir()
        val second = tempDir()
        val script = File(second, "server.cmd").apply { writeText("@echo off\n") }

        assertEquals(
            script.absolutePath,
            LspNavigation.findOnPath(
                command = "server",
                path = "${first.absolutePath};${second.absolutePath}",
                isWindows = true,
                pathExtensions = ".CMD",
            ),
        )
    }

    @Test
    fun `windows batch quoting preserves spaces in executable and arguments`() {
        val dir = File(tempDir(), "server folder").apply { mkdirs() }
        val script = File(dir, "server.cmd").apply { writeText("@echo off\n") }
        val launched = LspNavigation().launchConfig(
            config(script.absolutePath, "--workspace", "C:\\My Project"),
            path = "",
            isWindows = true,
            commandInterpreter = "cmd.exe",
        )

        assertEquals(
            "call \"${script.absolutePath}\" \"--workspace\" \"C:\\My Project\"",
            launched?.command?.last(),
        )
    }

    @Test
    fun `cmd file is launched directly off Windows`() {
        val script = serverOn(tempDir(), "server.cmd")
        val launched = LspNavigation().launchConfig(
            config(script.absolutePath, "--stdio"),
            path = "",
            isWindows = false,
        )

        assertEquals(listOf(script.absolutePath, "--stdio"), launched?.command)
    }

    @Test
    fun `a failed shell PATH still merges process and fallback paths`() {
        val separator = File.pathSeparator
        assertEquals(
            listOf("/process/bin", "/fallback/bin").joinToString(separator),
            LspNavigation.mergePaths(null, "/process/bin", listOf("/fallback/bin")),
        )
    }

    @Test
    fun `disposed navigation cannot start more work`() = runBlocking {
        val navigation = LspNavigation()
        navigation.dispose()
        assertEquals(
            NavigationResolveResult.NotFound,
            navigation.resolveDefinition("const x = 1", "/tmp/a.ts", 0, "/tmp"),
        )
        assertNull(navigation.resolveHover("const x = 1", "/tmp/a.ts", 0, "/tmp"))
    }

    @Test
    fun `a hover over a file with no registered server returns null without starting anything`() =
        runBlocking {
            // Same guard rail as definition: the pointer idling over an unsupported
            // file must not spawn a server, and the null lets the editor skip the
            // tooltip instead of reporting a failed lookup.
            val navigation = LspNavigation()
            assertNull(navigation.resolveHover("x = 1", "/tmp/nothing.zzz", 0, "/tmp"))
        }

    @Test
    fun `fresh registration re-arms a disposed shared navigation`() {
        val before = LspNavigation.shared
        try {
            LspNavigation.disposeShared()
            assertSame(before, LspNavigation.shared)

            LspNavigation.resetShared()
            assertNotSame(before, LspNavigation.shared)
        } finally {
            LspNavigation.resetShared()
        }
    }

    @Test
    fun `startup failure cooldown expires and a settings change retries immediately`() {
        var now = 1_000L
        val failures = LspNavigation.FailureCooldown<String, String>(cooldownMs = 5) { now }

        failures.record("typescript@root", "settings-a")
        assertTrue(failures.isCoolingDown("typescript@root", "settings-a"))
        assertFalse(failures.isCoolingDown("typescript@root", "settings-b"))

        failures.record("typescript@root", "settings-b")
        now += 5_000_000L
        assertFalse(failures.isCoolingDown("typescript@root", "settings-b"))

        failures.record("typescript@root", "settings-c", cooldownMs = 1)
        now += 1_000_000L
        assertFalse(failures.isCoolingDown("typescript@root", "settings-c"))
    }

    @Test
    fun `an ancestor timeout is propagated rather than reported as the inner timeout`() {
        val navigation = LspNavigation()
        var innerReturned = false

        assertFailsWith<TimeoutCancellationException> {
            runBlocking {
                withTimeout(20) {
                    navigation.ownTimeout(1_000) {
                        delay(10_000)
                    }
                    innerReturned = true
                }
            }
        }
        assertFalse(innerReturned)
    }

    @Test
    fun `a cancelled cold start is not recorded as a startup failure`() = runBlocking {
        // The regression: a cold start interrupted by the caller (the pointer moved on,
        // the tab closed, the buffer was re-set) used to come back out as a wrapped
        // LanguageServerException, which this class read as "the server is broken" and
        // answered with a five-minute failure cooldown - silently disabling navigation
        // for the whole workspace after the very first cancelled hover.
        val config = LanguageServerConfig(
            id = "fake-hanging-server",
            displayName = "Fake Hanging Server",
            languageId = "fake-hanging",
            command = listOf("sh", "-c", "sleep 300"),
            fileExtensions = listOf("zzfakehanging"),
        )
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        val filePath = "/tmp/zzfakehanging-cancellation.zzfakehanging"
        try {
            // Pre-warm the lazy user-PATH probe so the cold start below is spawn +
            // initialize, not a login shell first.
            navigation.launchConfig(config, workingDirectory = "/tmp")

            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(Dispatchers.IO + scopeJob)
            val start = scope.launch {
                navigation.resolveDefinition("x", filePath, 0, "/tmp")
            }
            delay(1_500) // let the process spawn and the initialize handshake hang
            scopeJob.cancelAndJoin()
            // The start must complete AS CANCELLED, not as a swallowed NotFound.
            assertTrue(start.isCancelled)

            // And the next request must attempt a FRESH start. A recorded failure
            // cooldown would refuse it immediately with NotFound; a genuine retry
            // hangs in a new initialize, still in flight well past 1.5s.
            val retryScopeJob = SupervisorJob()
            val retryScope = CoroutineScope(Dispatchers.IO + retryScopeJob)
            val retry = retryScope.launch {
                navigation.resolveDefinition("x", filePath, 0, "/tmp")
            }
            delay(1_500)
            assertFalse(
                retry.isCompleted,
                "a cooled-down rejection returns NotFound immediately; a fresh start is still in flight",
            )
            retryScopeJob.cancelAndJoin()
        } finally {
            navigation.dispose()
            LanguageServerRegistry.unregister(config.languageId)
        }
    }

    // ---- warm-up and hover failure branches (fake stdio server) ----------
    //
    // warmUp is the only entry point that starts a server with NO user gesture,
    // and resolveHover is the newest failure path, so both get the fake-server
    // treatment: a python3 stdio process that answers `initialize` and then
    // behaves per a MODE FILE the test rewrites between phases, announcing its
    // own PID so the tests can assert on the process, not just on nulls.

    private fun fakeLspServerScript(dir: File): File =
        File(dir, "fake-lsp-server.py").apply {
            writeText(FAKE_LSP_SERVER_PYTHON)
            setExecutable(true)
        }

    private fun fakeLspConfig(
        dir: File,
        mode: String,
        id: String,
        extension: String,
        pidFile: File,
    ): LanguageServerConfig {
        val modeFile = File(dir, "$id-mode").apply { writeText(mode) }
        return LanguageServerConfig(
            id = id,
            displayName = id,
            languageId = id,
            command = listOf("python3", fakeLspServerScript(dir).absolutePath),
            fileExtensions = listOf(extension),
            environment = mapOf(
                "FAKE_LSP_MODE_FILE" to modeFile.absolutePath,
                "FAKE_LSP_PID_FILE" to pidFile.absolutePath,
            ),
        )
    }

    private fun pidOf(pidFile: File): Int = pidFile.readText().trim().toInt()

    private fun processIsAlive(pidFile: File): Boolean {
        if (!pidFile.exists()) return false
        return ProcessHandle.of(pidOf(pidFile).toLong()).map { it.isAlive }.orElse(false)
    }

    /**
     * The fake-server tests need python3. Every GitHub runner has it, and so
     * does this machine - but if it is ever missing, an ASSUMPTION failure
     * reports the test as *skipped with a reason* rather than as a green test
     * that asserts nothing. (Checked lazily per test-class instance; JUnit
     * builds a fresh instance per method, so this is once per test, not once
     * per class.)
     */
    private val PYTHON3_AVAILABLE: Boolean by lazy {
        try {
            val process = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val ok = process.waitFor() == 0
            if (!ok) System.err.println("[LspNavigationLaunchTest] python3 check failed: $output")
            ok
        } catch (error: Exception) {
            System.err.println("[LspNavigationLaunchTest] python3 not on PATH: ${error.message}")
            false
        }
    }

    /** Fails the test as a JUnit assumption when python3 is absent (reports skipped). */
    private fun requirePython3() {
        Assume.assumeTrue(
            "python3 not found on PATH (needed to run the fake LSP stdio server)",
            PYTHON3_AVAILABLE,
        )
    }

    /** Kill the fake server recorded in [pidFile] so a test never leaves a process behind. */
    private fun killIfAlive(pidFile: File) {
        if (!pidFile.exists()) return
        ProcessHandle.of(pidOf(pidFile).toLong()).ifPresent { it.destroyForcibly() }
    }

    @Test
    fun `warm-up starts the server for a file it can serve`() = runBlocking {
        requirePython3()
        // The positive control: opening a servable file spawns the server,
        // which is the whole point of warming (the first gesture must not pay
        // spawn + initialize + settle).
        val dir = tempDir()
        val pidFile = File(dir, "pid")
        val config = fakeLspConfig(dir, "healthy", "warm-ok", "wok", pidFile)
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        try {
            navigation.warmUp("x = 1", File(dir, "warm.wok").absolutePath, dir.absolutePath)
            assertTrue(pidFile.exists(), "warm-up must spawn the server for a file it can serve")
            assertTrue(processIsAlive(pidFile), "the warmed server must be left running")
        } finally {
            killIfAlive(pidFile)
            navigation.dispose()
            LanguageServerRegistry.unregister(config.languageId)
        }
    }

    @Test
    fun `warm-up after dispose is a no-op`() = runBlocking {
        val dir = tempDir()
        val pidFile = File(dir, "pid")
        val config = fakeLspConfig(dir, "healthy", "warm-disposed", "wfd", pidFile)
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        navigation.dispose()
        navigation.warmUp("x = 1", File(dir, "warm.wfd").absolutePath, dir.absolutePath)
        assertFalse(pidFile.exists(), "a disposed navigation must not spawn a server")
        LanguageServerRegistry.unregister(config.languageId)
    }

    @Test
    fun `warm-up for a file with no registered server spawns nothing`() = runBlocking {
        // A silent no-op: no process, no error, nothing recorded.
        val dir = tempDir()
        val pidFile = File(dir, "pid")
        val config = fakeLspConfig(dir, "healthy", "warm-unreg", "wur", pidFile)
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        try {
            // .zzz has no registered server (the config above covers .wur only)
            navigation.warmUp("x = 1", File(dir, "nothing.zzz").absolutePath, dir.absolutePath)
            assertFalse(pidFile.exists(), "an unservable file must not start a server")
            assertNull(LanguageServerRegistry.getConfigForFile(File(dir, "nothing.zzz").absolutePath))
        } finally {
            navigation.dispose()
            LanguageServerRegistry.unregister(config.languageId)
        }
    }

    @Test
    fun `warm-up with the feature disabled spawns nothing`() = runBlocking {
        val dir = tempDir()
        val pidFile = File(dir, "pid")
        val config = fakeLspConfig(dir, "healthy", "warm-disabled", "wds", pidFile)
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        val previousEnabled = LspSettingsManager.instance.configuration.value.enabled
        try {
            LspSettingsManager.instance.setEnabled(false)
            navigation.warmUp("x = 1", File(dir, "warm.wds").absolutePath, dir.absolutePath)
            assertFalse(pidFile.exists(), "a disabled feature must not spawn a server")
        } finally {
            LspSettingsManager.instance.setEnabled(previousEnabled)
            killIfAlive(pidFile)
            navigation.dispose()
            LanguageServerRegistry.unregister(config.languageId)
        }
    }

    @Test
    fun `a timed-out hover degrades to null and keeps the warm server`() = runBlocking {
        requirePython3()
        // The fake server answers initialize and then swallows hover forever.
        // A slow ANSWER is not a dead server: the result degrades to "no hover"
        // and the live, initialized process must be left running for the next
        // question (definition clicks and other tabs included).
        val dir = tempDir()
        val pidFile = File(dir, "pid")
        val config = fakeLspConfig(dir, "hang", "hover-hang", "hhg", pidFile)
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        val previousTimeout = LspSettingsManager.instance.configuration.value.defaultRequestTimeoutMs
        try {
            LspSettingsManager.instance.setRequestTimeout(750)
            val result = navigation.resolveHover(
                "def fake_fn(x: int) -> int",
                File(dir, "hover.hhg").absolutePath,
                4,
                dir.absolutePath,
            )
            assertNull(result, "a timed-out hover is 'no hover', not a hang or a crash")
            assertTrue(processIsAlive(pidFile), "a slow hover must not stop a live, initialized server")
        } finally {
            LspSettingsManager.instance.setRequestTimeout(previousTimeout)
            killIfAlive(pidFile)
            navigation.dispose()
            LanguageServerRegistry.unregister(config.languageId)
        }
    }

    @Test
    fun `a hover times out on its own short budget, not the 30s click budget`() = runBlocking {
        requirePython3()
        // The configured default is the 30s click budget. A hover must not hold
        // the pointer for that long: it has its own, shorter budget, so a hung
        // server degrades to "no hover" in about a second and a half.
        val dir = tempDir()
        val pidFile = File(dir, "pid")
        val config = fakeLspConfig(dir, "hang", "hover-cap", "hvc", pidFile)
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        val previousTimeout = LspSettingsManager.instance.configuration.value.defaultRequestTimeoutMs
        try {
            LspSettingsManager.instance.setRequestTimeout(30_000)
            val startedAt = System.nanoTime()
            assertNull(
                navigation.resolveHover(
                    "def fake_fn(x: int) -> int",
                    File(dir, "hover.hvc").absolutePath,
                    4,
                    dir.absolutePath,
                ),
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMs < 8_000, "hover must time out on its own ~1.5s budget, not 30s; took ${elapsedMs}ms")
        } finally {
            LspSettingsManager.instance.setRequestTimeout(previousTimeout)
            killIfAlive(pidFile)
            navigation.dispose()
            LanguageServerRegistry.unregister(config.languageId)
        }
    }

    @Test
    fun `a hover on a dead server degrades to null and the next hover restarts it`() = runBlocking {
        requirePython3()
        // Phase 1 ("die"): the server answers initialize, then exits mid-session.
        // The hover must degrade to null - not crash, not burn a failure
        // cooldown (a request-level death is not a startup failure).
        // Phase 2 ("healthy"): the SAME lookup with a healthy server must
        // succeed, proving the dead client did not stick and the happy path
        // works end to end (real process, real wire, real parse).
        val dir = tempDir()
        val pidFile = File(dir, "pid")
        val config = fakeLspConfig(dir, "die", "hover-die", "hdi", pidFile)
        LanguageServerRegistry.register(config)
        val navigation = LspNavigation()
        val filePath = File(dir, "hover.hdi").absolutePath
        val content = "def fake_fn(x: int) -> int"
        try {
            assertNull(navigation.resolveHover(content, filePath, 4, dir.absolutePath))
            // Switch the same fake server to healthy: the NEXT cold start reads
            // the new mode, so the second lookup runs against a live server.
            File(dir, "hover-die-mode").writeText("healthy")
            val hover = assertNotNull(
                navigation.resolveHover(content, filePath, 4, dir.absolutePath),
                "after a dead server, a fresh start must answer the hover again",
            )
            assertEquals("def fake_fn(x: int) -> int", hover.text)
            assertTrue(hover.isMarkdown)
        } finally {
            killIfAlive(pidFile)
            navigation.dispose()
            LanguageServerRegistry.unregister(config.languageId)
        }
    }

    // ---- document versions ----------------------------------------------

    @Test
    fun `versions increase and never go negative`() {
        // The version used to be System.currentTimeMillis().toInt(), which wraps
        // every ~49 days and is negative for half of each cycle - i.e. BELOW the
        // version didOpen sent. A server that drops non-increasing changes would
        // then keep answering from the opened snapshot, so unsaved edits stop
        // being reflected, silently.
        val nav = LspNavigation()
        val versions = buildList {
            add(nav.didOpen("file:///a.ts", "typescript", "x").version())
            repeat(2000) { add(nav.didChange("file:///a.ts", "x$it").version()) }
        }
        assertTrue(versions.all { it > 0 }, "a version must never be negative or zero")
        assertEquals(versions.sorted(), versions, "versions must be non-decreasing")
        assertEquals(versions.distinct().size, versions.size, "versions must be distinct")
    }

    private fun JsonElement.version(): Int =
        jsonObject.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.int

    // ---- didOpen vs didChange -------------------------------------------

    @Test
    fun `the same document on the same client opens once, then changes`() {
        val nav = LspNavigation()
        val client = RecordingClient()
        nav.syncDocument(client, "file:///a.ts", "typescript", "one")
        nav.syncDocument(client, "file:///a.ts", "typescript", "two")
        nav.syncDocument(client, "file:///a.ts", "typescript", "three")
        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_CHANGE, LspMethods.DID_CHANGE), client.methods)
    }

    @Test
    fun `an unchanged document is not shipped again`() {
        val nav = LspNavigation()
        val client = RecordingClient()
        nav.syncDocument(client, "file:///a.ts", "typescript", "same")
        nav.syncDocument(client, "file:///a.ts", "typescript", "same")
        assertEquals(listOf(LspMethods.DID_OPEN), client.methods)
    }

    @Test
    fun `a replaced server is told about the document again`() {
        // LanguageServerManager hands back a FRESH process when a server has
        // died, and that process has never heard of the document. Sending it a
        // didChange leaves the file answering NotFound for the rest of the
        // session, with nothing logged anywhere.
        val nav = LspNavigation()
        val first = RecordingClient()
        assertTrue(nav.trackClient("typescript", "/root", first))
        assertFalse(nav.trackClient("typescript", "/root", first))
        nav.syncDocument(first, "file:///a.ts", "typescript", "one")
        nav.syncDocument(first, "file:///a.ts", "typescript", "two")

        val restarted = RecordingClient()
        // The old client had a logged hover timeout: its dedupe entry holds a
        // strong reference (transport + process streams) and must be evicted
        // with its opened entry, not kept until dispose().
        nav.markHoverTimeoutLogged(first)
        assertTrue(nav.hoverTimeoutLogged(first))
        assertTrue(nav.trackClient("typescript", "/root", restarted))
        assertFalse(nav.hoverTimeoutLogged(first), "a replaced client's hover-timeout entry is evicted with its opened entry")
        nav.syncDocument(restarted, "file:///a.ts", "typescript", "three")
        nav.syncDocument(restarted, "file:///a.ts", "typescript", "four")

        // Reaching the old client again is synthetic, but proves its strong-key entry was evicted.
        nav.syncDocument(first, "file:///a.ts", "typescript", "five")

        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_CHANGE, LspMethods.DID_OPEN), first.methods)
        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_CHANGE), restarted.methods)
    }

    @Test
    fun `each document is opened on its own`() {
        val nav = LspNavigation()
        val client = RecordingClient()
        nav.syncDocument(client, "file:///a.ts", "typescript", "a")
        nav.syncDocument(client, "file:///b.ts", "typescript", "b")
        nav.syncDocument(client, "file:///a.ts", "typescript", "a2")
        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_OPEN, LspMethods.DID_CHANGE), client.methods)
    }

    @Test
    fun `languages do not share an open-document set`() {
        val nav = LspNavigation()
        val ts = RecordingClient()
        val py = RecordingClient()
        nav.syncDocument(ts, "file:///a", "typescript", "a")
        nav.syncDocument(py, "file:///a", "python", "a")
        assertEquals(listOf(LspMethods.DID_OPEN), ts.methods)
        assertEquals(listOf(LspMethods.DID_OPEN), py.methods)
    }

    private class RecordingClient : LspClient {
        val methods = mutableListOf<String>()
        override val state = LspClientState.INITIALIZED
        override val isInitialized = true
        override val serverCapabilities: ServerCapabilities? = null
        override suspend fun request(method: String, params: JsonElement?): JsonElement? = null
        override fun notify(method: String, params: JsonElement?) { methods += method }
        override fun onNotification(method: String?, handler: (String, JsonElement?) -> Unit) = Unit
        override fun onRequest(method: String, handler: suspend (JsonElement?) -> JsonElement?) = Unit
        override suspend fun initialize(params: InitializeParams) = InitializeResult(ServerCapabilities())
        override fun initialized() = Unit
        override suspend fun shutdown() = Unit
        override fun exit() = Unit
        override fun dispose() = Unit
    }
}
