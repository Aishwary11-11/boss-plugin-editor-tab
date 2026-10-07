package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditorMcpAtomicWriteTest {
    private class BareContext : PluginContext {
        override val panelRegistry: PanelRegistry get() = error("not used")
        override val tabRegistry: TabRegistry get() = error("not used")
        override val pluginScope: CoroutineScope get() = error("not used")
    }

    private fun tool(
        editor: EditorContentProvider? = null,
        writer: (String, String) -> Boolean = ::writeProtectedEditorFile,
    ): McpToolDefinition =
        EditorTabMcpToolProvider("test-editor", BareContext(), editor, null, null, null, writer)
            .tools().single { it.name == "editor_write_file" }

    private suspend fun scratch(test: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("editor-mcp-write").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the actual tool never delegates a save to a destructive old host provider`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("irreplaceable original") }
            var hostWrites = 0
            val host = Proxy.newProxyInstance(EditorContentProvider::class.java.classLoader,
                arrayOf(EditorContentProvider::class.java)) { _, method, _ ->
                if (method.name == "writeFileContent") {
                    hostWrites++
                    target.writeText("truncated")
                    throw IOException("old host partially wrote the file")
                }
                null
            } as EditorContentProvider
            val result = tool(host).handler.call(McpToolArgs(mapOf("path" to target.path, "content" to "é\n")))
            assertFalse(result.isError)
            assertEquals("é\n", target.readText())
            assertEquals(3L, target.length())
            assertEquals(0, hostWrites)
        }
    }

    @Test
    fun `a missing provider still allows a protected first save and creates parent directories`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "new/nested/file.txt")
            val result = tool().handler.call(McpToolArgs(mapOf("path" to target.path, "content" to "new file")))
            assertFalse(result.isError)
            assertEquals("new file", target.readText())
            assertEquals(listOf(target.name), target.parentFile.list()!!.toList())
        }
    }

    @Test
    fun `partial production staging failure preserves an existing or absent destination and returns a safe error`() = runBlocking {
        scratch { directory ->
            for (existing in listOf(true, false)) {
                val target = File(directory, "file.txt")
                if (existing) target.writeText("irreplaceable original") else target.delete()
                val edited = "sensitive edited document"
                val result = tool(writer = { path, content ->
                    AtomicFileWrite.writeText(File(path), content) { stage, text ->
                        Files.writeString(stage, text.take(3))
                        throw IOException("disk full: $content")
                    }
                    true
                }).handler.call(McpToolArgs(mapOf("path" to target.path, "content" to edited)))
                assertTrue(result.isError)
                assertEquals("Write failed for ${target.path}.", result.text)
                assertFalse(result.text.contains(edited))
                assertEquals(existing, target.exists())
                if (existing) assertEquals("irreplaceable original", target.readText())
                assertEquals(if (existing) listOf(target.name) else emptyList(), directory.list()!!.toList())
            }
        }
    }

    @Test
    fun `missing or null arguments never erase the existing document`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("original") }
            for (args in listOf(
                mapOf("path" to target.path),
                mapOf("path" to target.path, "content" to null),
                mapOf("content" to "replacement"),
                mapOf("path" to null, "content" to "replacement"),
            )) {
                assertTrue(tool().handler.call(McpToolArgs(args)).isError)
                assertEquals("original", target.readText())
            }
        }
    }

    @Test
    fun `explicit empty content and non string scalar content retain the existing argument contract`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("original") }
            val tool = tool()
            assertFalse(tool.handler.call(McpToolArgs(mapOf("path" to target.path, "content" to 42))).isError)
            assertEquals("42", target.readText())
            assertFalse(tool.handler.call(McpToolArgs(mapOf("path" to target.path, "content" to ""))).isError)
            assertEquals(0L, target.length())
        }
    }

    @Test
    fun `non string path keeps typed argument conversion and executes the writer off the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        var invocation: Pair<String, String>? = null
        val result = tool(writer = { path, content ->
            assertFalse(Thread.currentThread() === callerThread)
            invocation = path to content
            true
        }).handler.call(McpToolArgs(mapOf("path" to 42, "content" to false)))
        assertFalse(result.isError)
        assertEquals("42" to "false", invocation)
    }

    @Test
    fun `a canceled disk writer propagates cancellation rather than returning a normal write failure`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("original") }
            assertFailsWith<CancellationException> {
                tool(writer = { _, _ -> throw CancellationException("canceled") })
                    .handler.call(McpToolArgs(mapOf("path" to target.path, "content" to "replacement")))
            }
            assertEquals("original", target.readText())
        }
    }

    @Test
    fun `write tool still requires editor write permission and is never advertised as read only`() {
        val tool = tool()
        assertFalse(tool.readOnly)
        assertEquals(listOf("editor.write"), tool.requiredPermissions)
    }

    @Test
    fun `disk only tool writes do not mark a live dirty buffer saved or hide the external conflict`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("original") }
            val buffer = EditorBufferRegistry.acquire(target.path, "original", "text")
            try {
                buffer.editorState.insertText("unsaved edits ")
                val mine = buffer.content
                val version = buffer.version
                val signature = buffer.knownSignature
                assertFalse(tool().handler.call(McpToolArgs(mapOf("path" to target.path, "content" to "tool disk replacement"))).isError)
                assertEquals(mine, buffer.content)
                assertEquals(version, buffer.version)
                assertEquals(signature, buffer.knownSignature)
                assertTrue(buffer.editorState.isModified.value)
                ExternalChangeWatcher(this, applyOn = Dispatchers.Unconfined).checkOnce(buffer)
                assertEquals(ExternalState.CONFLICT, buffer.externalState.value)
                assertEquals(mine, buffer.content)
            } finally {
                EditorBufferRegistry.release(target.path)
            }
        }
    }

    @Test
    fun `a dot alias waits for an existing file buffer save lock`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("original") }
            verifyAliasWaits(target, File(directory, "./file.txt").path, listOf(target.path))
        }
    }

    @Test
    fun `a dot alias to an absent file still waits for the buffer at its real parent`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "new-file.txt")
            verifyAliasWaits(target, File(directory, "./new-file.txt").path, listOf(target.path))
        }
    }

    @Test
    fun `a symlink write locks every registered spelling of the physical file`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("original") }
            val link = File(directory, "alias.txt")
            createSymlink(link, target)
            verifyAliasWaits(target, link.path, listOf(target.path, link.path))
        }
    }

    @Test
    fun `a dangling final symlink waits for the absent target buffer save lock`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "new-file.txt")
            val link = File(directory, "alias.txt")
            createSymlink(link, target)
            verifyAliasWaits(target, link.path, listOf(target.path))
            assertTrue(Files.isSymbolicLink(link.toPath()))
        }
    }

    @Test
    fun `a relative dangling symlink chain waits for the absent target buffer save lock`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "new-file.txt")
            val inner = File(directory, "inner.txt")
            val outer = File(directory, "outer.txt")
            createSymlink(inner, File(target.name))
            createSymlink(outer, File(inner.name))
            verifyAliasWaits(target, outer.path, listOf(target.path))
            assertTrue(Files.isSymbolicLink(inner.toPath()))
            assertTrue(Files.isSymbolicLink(outer.toPath()))
        }
    }

    @Test
    fun `symlink parent dot dot semantics are followed before matching the save lock`() = runBlocking {
        scratch { directory ->
            val project = File(directory, "project").apply { mkdirs() }
            val actualParent = File(directory, "other").apply { mkdirs() }
            val deep = File(actualParent, "deep").apply { mkdirs() }
            val target = File(actualParent, "file.txt").apply { writeText("original") }
            val decoy = File(project, "file.txt").apply { writeText("unrelated") }
            val link = File(project, "entry")
            createSymlink(link, deep)
            verifyAliasWaits(target, File(link, "../file.txt").path, listOf(target.path))
            assertEquals("unrelated", decoy.readText(), "the original path was lexically normalized before following its link")
        }
    }

    private suspend fun verifyAliasWaits(target: File, argument: String, bufferPaths: List<String>) = coroutineScope {
        val buffers = bufferPaths.map { path ->
            EditorBufferRegistry.acquire(path, target.takeIf { it.exists() }?.readText().orEmpty(), "text")
        }
        val blocker = buffers.first()
        blocker.saveMutex.lock()
        var blockerHeld = true
        try {
            val started = CompletableDeferred<Unit>()
            val write = async(start = CoroutineStart.UNDISPATCHED) {
                tool(writer = { path, content ->
                    started.complete(Unit)
                    writeProtectedEditorFile(path, content)
                }).handler.call(McpToolArgs(mapOf("path" to argument, "content" to "replacement")))
            }
            try {
                assertEquals(null, withTimeoutOrNull(500) { started.await() },
                    "an alternate path bypassed an existing physical file's buffer lock")
            } finally {
                blocker.saveMutex.unlock()
                blockerHeld = false
            }
            assertFalse(withTimeout(5_000) { write.await() }.isError)
            assertEquals("replacement", target.readText())
            for (buffer in buffers) {
                assertTrue(buffer.saveMutex.tryLock(), "a tool write left a buffer locked")
                buffer.saveMutex.unlock()
            }
        } finally {
            if (blockerHeld) blocker.saveMutex.unlock()
            for (path in bufferPaths) EditorBufferRegistry.release(path)
        }
    }

    private fun createSymlink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (failure: UnsupportedOperationException) {
            org.junit.Assume.assumeNoException("This filesystem cannot create symlinks", failure)
        } catch (failure: java.nio.file.FileSystemException) {
            if (System.getProperty("os.name").startsWith("Windows") &&
                failure.reason.orEmpty().contains("privilege", ignoreCase = true)) {
                org.junit.Assume.assumeNoException("This Windows account lacks symlink privileges", failure)
            } else {
                throw failure
            }
        }
    }

    @Test
    fun `tool writes wait for an open buffer save without adopting its clean state`() = runBlocking {
        scratch { directory ->
            val target = File(directory, "file.txt").apply { writeText("original") }
            val buffer = EditorBufferRegistry.acquire(target.path, "original", "text")
            val finish = CountDownLatch(1)
            try {
                buffer.editorState.insertText("saved first ")
                val started = CompletableDeferred<Unit>()
                val toolStarted = CompletableDeferred<Unit>()
                val save = async {
                    saveEditorDocument(buffer) { path, content ->
                        started.complete(Unit)
                        check(finish.await(5, TimeUnit.SECONDS))
                        writeProtectedEditorFile(path, content)
                    }
                }
                withTimeout(5_000) { started.await() }
                buffer.editorState.insertText("newer unsaved work ")
                val mine = buffer.content
                val write = async(start = CoroutineStart.UNDISPATCHED) {
                    tool(writer = { path, content ->
                        toolStarted.complete(Unit)
                        writeProtectedEditorFile(path, content)
                    }).handler.call(McpToolArgs(mapOf("path" to target.path, "content" to "tool disk replacement")))
                }
                try {
                    assertEquals(null, withTimeoutOrNull(500) { toolStarted.await() })
                } finally {
                    finish.countDown()
                }
                assertEquals(DocumentSaveResult.SAVED, withTimeout(5_000) { save.await() })
                assertFalse(withTimeout(5_000) { write.await() }.isError)
                assertEquals("tool disk replacement", target.readText())
                assertEquals(mine, buffer.content)
                assertTrue(buffer.editorState.isModified.value)
            } finally {
                finish.countDown()
                EditorBufferRegistry.release(target.path)
            }
        }
    }
}
