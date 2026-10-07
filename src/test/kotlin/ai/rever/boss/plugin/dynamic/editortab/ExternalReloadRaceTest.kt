package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalReloadRaceTest {
    @Test
    fun `typing before a queued reload is applied keeps unsaved edits`() =
        runBlocking {
            race { buffer, watcher, finish ->
                buffer.editorState.insertText("my unsaved work ")
                val mine = buffer.content
                finish()

                assertEquals(mine, buffer.content)
                assertTrue(buffer.editorState.isModified.value)
                watcher.checkOnce(buffer)
                assertEquals(ExternalState.CONFLICT, buffer.externalState.value)
            }
        }

    @Test
    fun `a completed save invalidates an older queued disk reload`() =
        runBlocking {
            race { buffer, _, finish ->
                buffer.editorState.insertText("saved work ")
                val mine = buffer.content
                java.io.File(buffer.path).writeText(mine)
                buffer.editorState.markAsSaved()
                buffer.noteWrittenByUs()
                val savedSignature = buffer.knownSignature
                finish()

                assertEquals(mine, buffer.content)
                assertEquals(savedSignature, buffer.knownSignature)
                assertFalse(buffer.editorState.isModified.value)
                assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
            }
        }

    @Test
    fun `a manually reloaded buffer invalidates an older queued disk reload`() =
        runBlocking {
            race { buffer, watcher, finish ->
                java.io.File(buffer.path).writeText("newer external content")
                watcher.resolveByReloading(buffer)
                val acceptedSignature = buffer.knownSignature
                finish()

                assertEquals("newer external content", buffer.content)
                assertEquals(acceptedSignature, buffer.knownSignature)
                assertFalse(buffer.editorState.isModified.value)
                assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
            }
        }

    @Test
    fun `keep mine invalidates a queued reload even without a document edit`() =
        runBlocking {
            race { buffer, watcher, finish ->
                watcher.resolveByKeepingMine(buffer)
                val acceptedSignature = buffer.knownSignature
                finish()

                assertEquals("original", buffer.content)
                assertEquals(acceptedSignature, buffer.knownSignature)
                assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
            }
        }

    private suspend fun race(verify: suspend (EditorBuffer, ExternalChangeWatcher, suspend () -> Unit) -> Unit) =
        kotlinx.coroutines.coroutineScope {
            val file = Files.createTempFile("reload-race", ".txt").toFile()
            val queued = CompletableDeferred<Runnable>()
            val dispatcher =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        check(queued.complete(block))
                    }
                }
            try {
                file.writeText("original")
                val buffer = EditorBuffer(file.path, EditorState("original", file.path), "text")
                buffer.knownSignature = signatureOf(file)
                file.writeText("external replacement")
                val watcher = ExternalChangeWatcher(CoroutineScope(Dispatchers.Unconfined), applyOn = dispatcher)
                val check = async { watcher.checkOnce(buffer) }
                val apply = withTimeout(5_000) { queued.await() }
                // Resume on this test thread, after the user's action. No sleeps or scheduler races.
                var resumed = false
                try {
                    val immediateWatcher =
                        ExternalChangeWatcher(
                            CoroutineScope(Dispatchers.Unconfined),
                            applyOn = Dispatchers.Unconfined,
                        )
                    verify(buffer, immediateWatcher) {
                        resumed = true
                        apply.run()
                        check.await()
                    }
                } finally {
                    if (!resumed) apply.run()
                    check.await()
                }
            } finally {
                file.delete()
            }
        }
}
