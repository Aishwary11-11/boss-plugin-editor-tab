package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.compose.NavigationResolveResult
import ai.rever.bosseditor.lsp.client.LspClient
import ai.rever.bosseditor.lsp.client.LspClientState
import ai.rever.bosseditor.lsp.client.LspMethods
import ai.rever.bosseditor.lsp.protocol.InitializeParams
import ai.rever.bosseditor.lsp.protocol.InitializeResult
import ai.rever.bosseditor.lsp.protocol.ServerCapabilities
import ai.rever.bosseditor.lsp.server.LanguageServerConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        assertTrue(nav.trackClient("typescript", "/root", restarted))
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
