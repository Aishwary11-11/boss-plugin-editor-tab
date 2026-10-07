package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.settings.EditorSettings
import ai.rever.bosseditor.settings.EditorSettingsManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EditorSettingsAuthorityTest {
    @Test
    fun `editor tabs and settings panel share one reactive settings stream`() {
        assertSame(
            EditorSettingsManager.instance.settings,
            editorSettingsFlow(),
        )
    }

    @Test
    fun `the source returns the manager flow without wrapping it`() = withManager { manager ->
        assertSame(manager.settings, editorSettingsFlow(manager))
    }

    @Test
    fun `a panel update reaches an open tab synchronously`() = withManager { manager ->
        val observedByTab = editorSettingsFlow(manager)
        val changed = manager.settings.value.copy(fontSize = 19f)

        manager.updateSettings(changed)

        assertSame(changed, observedByTab.value)
    }

    @Test
    fun `separate windows observe the same stream`() = withManager { manager ->
        val firstWindow = editorSettingsFlow(manager)
        val secondWindow = editorSettingsFlow(manager)

        assertSame(firstWindow, secondWindow)
        manager.updateSetting { it.copy(showMinimap = true) }
        assertEquals(true, firstWindow.value.showMinimap)
        assertEquals(true, secondWindow.value.showMinimap)
    }

    @Test
    fun `reset propagates through the shared stream`() = withManager { manager ->
        val observedByDiff = editorSettingsFlow(manager)
        manager.updateSettings(EditorSettings().copy(tabSize = 8, wordWrap = true))

        manager.resetToDefaults()

        assertEquals(EditorSettings.Default, observedByDiff.value)
    }

    @Test
    fun `the manager remains the only persistence authority`() {
        val directory = createTempDirectory("editor-settings-authority").toFile()
        try {
            val settingsFile = File(directory, "editor-settings.json")
            val writer = newManager(settingsFile)
            writer.updateSettings(EditorSettings().copy(themeName = "Dracula", fontSize = 17f))

            val reloaded = newManager(settingsFile)

            assertEquals("Dracula", editorSettingsFlow(reloaded).value.themeName)
            assertEquals(17f, editorSettingsFlow(reloaded).value.fontSize)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withManager(block: (EditorSettingsManager) -> Unit) {
        val directory = createTempDirectory("editor-settings-authority").toFile()
        try {
            block(newManager(File(directory, "editor-settings.json")))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun newManager(settingsFile: File): EditorSettingsManager {
        return EditorSettingsManager.withCustomPath(settingsFile.absolutePath)
    }
}
