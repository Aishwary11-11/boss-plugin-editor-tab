package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorDocumentSaveTest {
    private fun buffer(
        file: File,
        original: String = "original",
    ): EditorBuffer =
        EditorBuffer(file.path, EditorState(original, file.path), "text").also {
            it.knownSignature = signatureOf(file)
            it.editorState.insertText("edited ")
            it.headStale = false
        }

    private suspend fun withFile(test: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("document-save").toFile()
        try {
            test(File(directory, "document.txt").apply { writeText("original") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `provider false and exception preserve dirty state and baseline`() =
        runBlocking {
            withFile { file ->
                for (throws in listOf(false, true)) {
                    val buffer = buffer(file)
                    val baseline = buffer.knownSignature
                    val result =
                        saveEditorDocument(buffer) { path, text ->
                            assertEquals(file.path, path)
                            assertEquals(buffer.content, text)
                            if (throws) throw IOException("disk full")
                            false
                        }
                    assertEquals(DocumentSaveResult.FAILED, result)
                    assertNotNull(result.message)
                    assertTrue(buffer.editorState.isModified.value)
                    assertEquals(baseline, buffer.knownSignature)
                    assertFalse(buffer.headStale)
                    assertEquals("original", file.readText())
                }
            }
        }

    @Test
    fun `missing provider never falls back to a direct write`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                assertEquals(DocumentSaveResult.UNAVAILABLE, saveEditorDocument(buffer, null))
                assertTrue(buffer.editorState.isModified.value)
                assertEquals("original", file.readText())
            }
        }

    @Test
    fun `partial staging failure leaves original or absent destination and no staging file`() =
        runBlocking {
            withFile { file ->
                for (existing in listOf(true, false)) {
                    if (!existing) assertTrue(file.delete())
                    val buffer = buffer(file)
                    val baseline = buffer.knownSignature
                    // A provider test double exercises the plugin boundary. The real writer's
                    // injected partial-output/metadata tests live in BossConsole #427.
                    val result =
                        saveEditorDocument(buffer) { path, _ ->
                            val stage = Files.createTempFile(File(path).parentFile.toPath(), ".stage-", ".tmp")
                            try {
                                Files.writeString(stage, "partial")
                                throw IOException("disk full after partial output")
                            } finally {
                                Files.delete(stage)
                            }
                        }
                    assertEquals(DocumentSaveResult.FAILED, result)
                    assertTrue(buffer.editorState.isModified.value)
                    assertEquals(baseline, buffer.knownSignature)
                    assertEquals(existing, file.exists())
                    if (existing) assertEquals("original", file.readText())
                    assertEquals(if (existing) listOf(file.name) else emptyList(), file.parentFile.list()!!.toList())
                }
            }
        }

    @Test
    fun `successful shorter UTF8 replacement updates the shared buffer only after commit`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                buffer.editorState.document.setText("é\n")
                val baseline = buffer.knownSignature
                val result =
                    saveEditorDocument(buffer) { path, text ->
                        assertEquals(baseline, buffer.knownSignature)
                        assertTrue(buffer.editorState.isModified.value)
                        commit(path, text)
                    }
                assertEquals(DocumentSaveResult.SAVED, result)
                assertEquals("é\n", file.readText())
                assertEquals(3L, file.length())
                assertFalse(buffer.editorState.isModified.value)
                assertEquals(signatureOf(file), buffer.knownSignature)
                assertTrue(buffer.headStale)
            }
        }

    @Test
    fun `external edits and unresolved watcher conflicts prevent provider invocation`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                file.writeText("external change")
                assertEquals(
                    DocumentSaveResult.CONFLICT,
                    saveEditorDocument(buffer) { _, _ -> error("must not write") },
                )
                assertEquals(ExternalState.CONFLICT, buffer.externalState.value)
                buffer.knownSignature = signatureOf(file)
                assertEquals(
                    DocumentSaveResult.CONFLICT,
                    saveEditorDocument(buffer) { _, _ -> error("must not write") },
                )
                assertTrue(buffer.editorState.isModified.value)
                assertEquals("external change", file.readText())
            }
        }

    @Test
    fun `debounce cancellation finishes commit bookkeeping and leaves newer typing dirty`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                val captured = buffer.content
                val started = CompletableDeferred<Unit>()
                val finish = CountDownLatch(1)
                val save =
                    launch {
                        saveEditorDocument(buffer) { path, text ->
                            started.complete(Unit)
                            check(finish.await(10, TimeUnit.SECONDS))
                            commit(path, text)
                        }
                    }
                started.await()
                buffer.editorState.insertText("new typing")
                save.cancel()
                finish.countDown()
                save.join()
                assertEquals(captured, file.readText())
                assertEquals(signatureOf(file), buffer.knownSignature)
                assertTrue(buffer.editorState.isModified.value)
                assertTrue(buffer.content.contains("new typing"))
            }
        }

    @Test
    fun `manual and autosave requests serialize across shared viewports`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                val started = CompletableDeferred<Unit>()
                val finish = CountDownLatch(1)
                val manual =
                    async {
                        saveEditorDocument(buffer) { path, text ->
                            started.complete(Unit)
                            check(finish.await(10, TimeUnit.SECONDS))
                            commit(path, text)
                        }
                    }
                started.await()
                buffer.editorState.insertText("later")
                val autosave = async { saveEditorDocument(buffer, ::commit) }
                finish.countDown()
                assertEquals(DocumentSaveResult.SAVED, manual.await())
                assertEquals(DocumentSaveResult.SAVED, autosave.await())
                assertEquals(buffer.content, file.readText())
                assertFalse(buffer.editorState.isModified.value)
            }
        }

    @Test
    fun `keep mine permits retry while reload preserves external content`() =
        runBlocking {
            withFile { file ->
                for (keepMine in listOf(true, false)) {
                    file.writeText("original")
                    val buffer = buffer(file)
                    val edited = buffer.content
                    val watcher = ExternalChangeWatcher(this)
                    file.writeText("external change")
                    assertEquals(DocumentSaveResult.CONFLICT, saveEditorDocument(buffer, ::commit))
                    if (keepMine) {
                        watcher.resolveByKeepingMine(buffer)
                        assertTrue(buffer.editorState.isModified.value)
                        assertEquals(DocumentSaveResult.SAVED, saveEditorDocument(buffer, ::commit))
                        assertEquals(edited, file.readText())
                    } else {
                        watcher.resolveByReloading(buffer)
                        assertEquals("external change", buffer.content)
                        assertEquals(
                            DocumentSaveResult.UNCHANGED,
                            saveEditorDocument(buffer) { _, _ -> error("reload must not write") },
                        )
                        assertEquals("external change", file.readText())
                    }
                    assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
                    assertFalse(buffer.editorState.isModified.value)
                    assertEquals(signatureOf(file), buffer.knownSignature)
                }
            }
        }

    private fun commit(
        path: String,
        text: String,
    ): Boolean {
        val target = File(path).toPath()
        val stage = Files.createTempFile(target.parent, ".stage-", ".tmp")
        try {
            Files.writeString(stage, text)
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(stage)
        }
        return true
    }
}
