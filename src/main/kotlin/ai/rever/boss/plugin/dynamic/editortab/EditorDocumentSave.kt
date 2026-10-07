package ai.rever.boss.plugin.dynamic.editortab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Shared by manual save, autosave and the editable diff pane. The plugin's local
 * writer owns atomic disk replacement independently of the host release.
 * Ordinary failures leave the buffer unsaved; fatal JVM errors still propagate.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun saveEditorDocument(
    buffer: EditorBuffer,
    write: ((String, String) -> Boolean)? = ::writeProtectedEditorFile,
): DocumentSaveResult =
    buffer.saveMutex.withLock {
        val state = buffer.editorState
        if (!state.isModified.value) return@withLock DocumentSaveResult.UNCHANGED
        if (write == null) return@withLock DocumentSaveResult.UNAVAILABLE
        if (buffer.externalState.value == ExternalState.CONFLICT) return@withLock DocumentSaveResult.CONFLICT

        val content = buffer.content
        val version = buffer.version
        val conflict =
            try {
                withContext(Dispatchers.IO) {
                    val file = File(buffer.path)
                    val current = signatureOf(file)
                    current != buffer.knownSignature && current.exists && file.readText() != content
                }
            } catch (_: IOException) {
                return@withLock DocumentSaveResult.FAILED
            } catch (_: SecurityException) {
                return@withLock DocumentSaveResult.FAILED
            }
        if (conflict) {
            buffer.setExternalState(ExternalState.CONFLICT)
            return@withLock DocumentSaveResult.CONFLICT
        }

        // A debounce cancellation must not abandon bookkeeping after the blocking writer
        // commits. New keystrokes remain dirty because only the captured version is saved.
        withContext(NonCancellable) {
            val committed =
                withContext(Dispatchers.IO) {
                    try {
                        write(buffer.path, content)
                    } catch (_: Exception) {
                        false
                    }
                }
            if (committed) {
                buffer.noteWrittenByUs()
                if (buffer.version == version) state.markAsSaved()
                DocumentSaveResult.SAVED
            } else {
                DocumentSaveResult.FAILED
            }
        }
    }

/** Local disk commit used by every document-save viewport; never truncates the destination. */
@Suppress("TooGenericExceptionCaught")
internal fun writeProtectedEditorFile(path: String, content: String): Boolean =
    try {
        val file = File(path)
        file.parentFile?.mkdirs()
        AtomicFileWrite.writeText(file, content)
        true
    } catch (failure: Exception) {
        // Edited text and exception messages may contain credentials. Log only metadata.
        System.err.println("[EditorDocumentSave] Failed to save '$path' (${failure::class.simpleName})")
        false
    }
