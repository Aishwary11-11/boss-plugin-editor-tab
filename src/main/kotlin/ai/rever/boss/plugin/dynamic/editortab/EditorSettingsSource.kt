package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.settings.EditorSettings
import ai.rever.bosseditor.settings.EditorSettingsManager
import kotlinx.coroutines.flow.StateFlow

/**
 * The single settings stream shared by the settings panel and every editor or diff viewport.
 *
 * BossEditor is bundled privately in this plugin, so its manager is one singleton per plugin
 * classloader. Returning its flow directly keeps updates synchronous across windows without a
 * second serialized model or a polling coroutine.
 */
internal fun editorSettingsFlow(
    manager: EditorSettingsManager = EditorSettingsManager.instance,
): StateFlow<EditorSettings> = manager.settings
