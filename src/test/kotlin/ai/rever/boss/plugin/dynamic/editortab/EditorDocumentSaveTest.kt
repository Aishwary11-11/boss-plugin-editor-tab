package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    fun `default local writer commits the captured buffer without a host provider`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                assertEquals(DocumentSaveResult.SAVED, saveEditorDocument(buffer))
                assertEquals(buffer.content, file.readText())
                assertFalse(buffer.editorState.isModified.value)
                assertEquals(signatureOf(file), buffer.knownSignature)
                assertTrue(buffer.headStale)
            }
        }

    @Test
    fun `writer false and exception preserve dirty state and baseline`() =
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
    fun `unavailable writer never falls back to a direct write`() =
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
                    // This double exercises transaction state after a partial staging failure.
                    // Production staging/metadata regressions live in AtomicFileWriteTest.
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
    fun `external edits and unresolved watcher conflicts prevent writer invocation`() =
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
                withTimeout(5_000) { started.await() }
                buffer.editorState.insertText("new typing")
                save.cancel()
                finish.countDown()
                withTimeout(5_000) { save.join() }
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
                val secondStarted = CompletableDeferred<Unit>()
                val finish = CountDownLatch(1)
                val manual =
                    async {
                        saveEditorDocument(buffer) { path, text ->
                            started.complete(Unit)
                            check(finish.await(10, TimeUnit.SECONDS))
                            commit(path, text)
                        }
                    }
                withTimeout(5_000) { started.await() }
                buffer.editorState.insertText("later")
                val autosave = async(start = CoroutineStart.UNDISPATCHED) {
                    saveEditorDocument(buffer) { path, text ->
                        secondStarted.complete(Unit)
                        commit(path, text)
                    }
                }
                try {
                    assertEquals(null, withTimeoutOrNull(500) { secondStarted.await() },
                        "a second viewport began writing before the first transaction finished")
                } finally {
                    finish.countDown()
                }
                assertEquals(DocumentSaveResult.SAVED, withTimeout(5_000) { manual.await() })
                assertEquals(DocumentSaveResult.SAVED, withTimeout(5_000) { autosave.await() })
                assertEquals(buffer.content, file.readText())
                assertFalse(buffer.editorState.isModified.value)
            }
        }

    @Test
    fun `reload requested during a blocked save waits and cannot leave a clean stale buffer`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                val captured = buffer.content
                val started = CompletableDeferred<Unit>()
                val finish = CountDownLatch(1)
                val save = async {
                    saveEditorDocument(buffer) { path, text ->
                        started.complete(Unit)
                        check(finish.await(5, TimeUnit.SECONDS))
                        commit(path, text)
                    }
                }
                withTimeout(5_000) { started.await() }
                file.writeText("external content seen before commit")
                val watcher = ExternalChangeWatcher(this, applyOn = Dispatchers.Unconfined)
                val reload = async(start = CoroutineStart.UNDISPATCHED) { watcher.resolveByReloading(buffer) }
                try {
                    assertFalse(reload.isCompleted, "reload changed the buffer while its save was in flight")
                    assertEquals(captured, buffer.content)
                    assertTrue(buffer.editorState.isModified.value)
                } finally {
                    finish.countDown()
                }
                assertEquals(DocumentSaveResult.SAVED, withTimeout(5_000) { save.await() })
                withTimeout(5_000) { reload.await() }
                assertEquals(captured, file.readText())
                assertEquals(file.readText(), buffer.content)
                assertFalse(buffer.editorState.isModified.value)
                assertEquals(signatureOf(file), buffer.knownSignature)
            }
        }

    @Test
    fun `watcher observation waits for the save transaction before adopting disk state`() =
        runBlocking {
            withFile { file ->
                val buffer = buffer(file)
                val started = CompletableDeferred<Unit>()
                val finish = CountDownLatch(1)
                val save = async {
                    saveEditorDocument(buffer) { path, text ->
                        started.complete(Unit)
                        check(finish.await(5, TimeUnit.SECONDS))
                        commit(path, text)
                    }
                }
                withTimeout(5_000) { started.await() }
                file.writeText("external content seen before commit")
                val watcher = ExternalChangeWatcher(this, applyOn = Dispatchers.Unconfined)
                val observation = async(start = CoroutineStart.UNDISPATCHED) { watcher.checkOnce(buffer) }
                try {
                    assertEquals(null, withTimeoutOrNull(500) { observation.await(); true },
                        "watcher applied an observation while the save was in flight")
                } finally {
                    finish.countDown()
                }
                assertEquals(DocumentSaveResult.SAVED, withTimeout(5_000) { save.await() })
                withTimeout(5_000) { observation.await() }
                assertEquals(buffer.content, file.readText())
                assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
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
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(stage)
        }
        return true
    }
}
