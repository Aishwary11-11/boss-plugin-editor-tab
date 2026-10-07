package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.BufferChange
import ai.rever.boss.plugin.api.BufferSnapshot
import ai.rever.boss.plugin.api.EditResult
import ai.rever.boss.plugin.api.EditorTabPluginAPI
import ai.rever.boss.plugin.api.FocusedDocument
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.dynamic.editortab.settings.LspSettingsContent
import ai.rever.boss.plugin.dynamic.editortab.settings.AutoSaveSettingsContent
import ai.rever.boss.plugin.dynamic.editortab.settings.ExternalReloadSettingsContent
import ai.rever.boss.plugin.dynamic.editortab.settings.MarkdownViewSettingsContent
import ai.rever.boss.plugin.ui.BossDarkBorder
import ai.rever.bosseditor.settings.EditorSettingsManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editor-tab's implementation of EditorTabPluginAPI, registered with the host
 * via registerPluginAPI(). The panels render against the BossEditor bundled
 * inside this plugin's JAR - the host no longer has BossEditor on its
 * classpath (same inversion as terminal-tab's TerminalTabPluginAPIImpl).
 *
 * The buffer model (1.0.87) resolves against [EditorBufferRegistry]: one
 * live buffer per path, shared by every viewport, so readBuffer/applyEdit/
 * observeChanges/focusedDocument all see the state the user sees.
 */
class EditorTabPluginAPIImpl(
    private val context: PluginContext,
    private val markdownSettingsManager: MarkdownViewSettingsManager,
    private val autoSaveSettingsManager: AutoSaveSettingsManager,
    private val externalReloadSettingsManager: ExternalReloadSettingsManager
) : EditorTabPluginAPI {

    override fun autoSaveEnabled(): StateFlow<Boolean> = autoSaveSettingsManager.enabled

    override fun setAutoSaveEnabled(enabled: Boolean) {
        autoSaveSettingsManager.setEnabled(enabled)
    }

    @Composable
    override fun EditorSettingsPanel(modifier: Modifier) {
        // BossEditor's singleton settings manager gives reactive updates;
        // settings persist to editor-settings.json under the BOSS data root and
        // apply to all BossEditor instances immediately.
        // The panel is BossEditor's own UI, so it needs the host chrome pushed in, and
        // it previews the follow-host theme through the registry - which is populated
        // by the bridge, not by the panel. Both have to happen here as well as in a
        // tab: Settings can be opened in a fresh session with no editor tab composed.
        EditorHostThemeEffects()

        val settingsManager = remember { EditorSettingsManager.instance }
        val currentSettings by editorSettingsFlow(settingsManager).collectAsState()
        val markdownSettings by markdownSettingsManager.settings.collectAsState()
        val autoSaveEnabled by autoSaveSettingsManager.enabled.collectAsState()
        val externalReloadEnabled by externalReloadSettingsManager.enabled.collectAsState()
        val markdownSettingsLoaded by markdownSettingsManager.isLoaded.collectAsState()

        Column(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                ai.rever.bosseditor.settings.EditorSettingsPanel(
                    settings = currentSettings,
                    onSettingsChange = { newSettings ->
                        settingsManager.updateSettings(newSettings)
                    },
                    onResetToDefaults = settingsManager::resetToDefaults,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BossDarkBorder)
            )

            AutoSaveSettingsContent(
                enabled = autoSaveEnabled,
                onEnabledChange = autoSaveSettingsManager::setEnabled,
                modifier = Modifier.fillMaxWidth()
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BossDarkBorder)
            )

            ExternalReloadSettingsContent(
                enabled = externalReloadEnabled,
                onEnabledChange = externalReloadSettingsManager::setEnabled,
                modifier = Modifier.fillMaxWidth()
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BossDarkBorder)
            )

            if (markdownSettingsLoaded) {
                MarkdownViewSettingsContent(
                    settings = markdownSettings,
                    onDefaultViewChange = markdownSettingsManager::setDefaultView,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }

    @Composable
    override fun LspSettingsPanel(modifier: Modifier) {
        LspSettingsContent(modifier = modifier)
    }

    // --- Buffer model (boss-plugin-api 1.0.87) ---------------------------

    /**
     * Callers (MCP tools, the search plugin) pass project-relative or
     * absolute paths; buffers are keyed by absolute path, so relative
     * input is resolved against the current project first.
     */
    private fun resolvePath(path: String): String {
        if (File(path).isAbsolute || path.startsWith("~/")) return path
        val project = context.projectPath
        return if (project != null) File(project, path).path else path
    }

    override suspend fun readBuffer(path: String): BufferSnapshot? {
        val buffer = EditorBufferRegistry.find(resolvePath(path)) ?: return null
        // The registry lookup above is a plain map read and stays on the caller's
        // thread deliberately: the host's find-in-files loop calls this for EVERY
        // walked file, and dispatching all of those to Main would serialise a whole
        // project scan through the UI thread. Only the part that touches the
        // document hops - the same rule applyEdit states, applied to the read side,
        // which had been reading Compose-backed state from Dispatchers.IO while the
        // user was typing into it.
        return withContext(Dispatchers.Main) {
            BufferSnapshot(
                path = buffer.path,
                content = buffer.content,
                version = buffer.version,
                isModified = buffer.editorState.isModified.value,
            )
        }
    }

    override suspend fun applyEdit(
        path: String,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        newText: String,
        expectedVersion: Long
    ): EditResult {
        val buffer = EditorBufferRegistry.find(resolvePath(path))
            ?: return EditResult(applied = false, reason = "no open buffer for this path")

        // Document reads AND mutations touch Compose-backed state: main thread only.
        //
        // The version check belongs INSIDE this block, not before it. Checking on the
        // caller's thread and then hopping left a window for a keystroke already
        // queued on Main to run between the two: the check passed, the document moved,
        // and the edit applied at offsets computed from the pre-keystroke snapshot -
        // silent corruption, in the one place the API promises "fails stale rather
        // than mis-applying over newer content". The host's replace-in-files loop
        // (ContentSearchService.replaceInBuffer) rests its whole safety argument on
        // this check being race-free, and calls it from Dispatchers.IO.
        return withContext(Dispatchers.Main) {
            if (buffer.version != expectedVersion) {
                return@withContext EditResult(
                    applied = false,
                    reason = "stale version: expected $expectedVersion but buffer is at ${buffer.version}",
                )
            }
            val state = buffer.editorState
            val doc = state.document
            val lineCount = doc.lineCount
            if (
                startLine !in 1..lineCount ||
                endLine !in 1..lineCount ||
                startCol !in 1..(doc.getLineLength(startLine - 1) + 1) ||
                endCol !in 1..(doc.getLineLength(endLine - 1) + 1)
            ) {
                EditResult(applied = false, reason = "edit range out of bounds")
            } else {
                val start = doc.positionToOffset(startLine - 1, startCol - 1)
                val end = doc.positionToOffset(endLine - 1, endCol - 1)
                if (start !in 0..doc.length || end !in 0..doc.length || start > end) {
                    EditResult(applied = false, reason = "edit range out of bounds")
                } else {
                    // document.replace goes through the document's UndoManager
                    // listener, so the edit is one normal undo step (the
                    // undo manager coalesces rapid replaces into a group).
                    doc.replace(start, end, newText)
                    EditResult(applied = true, newVersion = buffer.version)
                }
            }
        }
    }

    override fun observeChanges(path: String): Flow<BufferChange>? {
        val buffer = EditorBufferRegistry.find(resolvePath(path)) ?: return null
        return buffer.observe()
    }

    override suspend fun focusedDocument(): FocusedDocument? {
        val buffer = EditorBufferRegistry.focused() ?: return null
        // suspend per the api (an out-of-process caller must not block a thread
        // on a full-document read), but this body's reads are local registry
        // accesses, so no hop is needed. It can still observe a torn
        // content/version pair while the user types - callers needing a
        // version-validated snapshot should prefer readBuffer.
        val state = buffer.editorState
        val selection = state.selection.value
        val hasSelection = state.hasSelection && selection != null
        return FocusedDocument(
            path = buffer.path,
            content = buffer.content,
            version = buffer.version,
            selectionStartLine = if (hasSelection) selection!!.start.line + 1 else null,
            selectionStartCol = if (hasSelection) selection!!.start.column + 1 else null,
            selectionEndLine = if (hasSelection) selection!!.end.line + 1 else null,
            selectionEndCol = if (hasSelection) selection!!.end.column + 1 else null,
            language = buffer.language,
        )
    }

    override suspend fun openEditor(path: String, line: Int?): Boolean {
        val operations = context.splitViewOperations ?: return false
        val fileName = path.substringAfterLast('/')
        return try {
            if (line != null) {
                operations.openFileAtPosition(path, fileName, line, 1)
            } else {
                operations.openFileInActivePanel(path, fileName)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun openSplit(path: String): Boolean {
        val operations = context.splitViewOperations ?: return false
        return try {
            operations.openTabInSplit(
                EditorTabData(id = "editor-${System.nanoTime()}", filePath = path),
                TabSplitMode.HORIZONTAL_SPLIT,
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
