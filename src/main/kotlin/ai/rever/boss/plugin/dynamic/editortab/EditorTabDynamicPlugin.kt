package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.bosseditor.psi.PSIBootstrap
import ai.rever.bosseditor.psi.PSIThreadBridge
import ai.rever.bosseditor.psi.ProjectIndexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Code Editor Tab dynamic plugin - Loaded from external JAR.
 *
 * Provides code editor tabs in the main panel area using the host's
 * editor infrastructure (RSyntaxTextArea or BossEditor).
 *
 * Features:
 * - Syntax highlighting for 50+ languages
 * - Code folding
 * - Bracket matching
 * - Line numbers with fold indicators
 * - Run gutter icons for detected main functions
 * - File modification tracking with save support (Cmd+S)
 *
 * NOTE: This is a main panel TAB plugin, not a sidebar panel.
 * It registers as a TabType via tabRegistry.registerTabType().
 */
class EditorTabDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.editortab"
    override val displayName: String = "Code Editor Tab"
    override val version: String = readManifestVersion()
    override val description: String = "Code editor tab with syntax highlighting, code folding, and run gutter icons"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-editor-tab"

    private var pluginContext: PluginContext? = null
    private var markdownSettingsManager: MarkdownViewSettingsManager? = null
    private var autoSaveSettingsManager: AutoSaveSettingsManager? = null
    private var externalReloadSettingsManager: ExternalReloadSettingsManager? = null
    private var composerAgent: ComposerAgent? = null
    private var composerSessions: ComposerSessions? = null
    private var pluginScope: CoroutineScope? = null

    override fun register(context: PluginContext) {
        // disposeShared deliberately leaves a dead singleton installed during unload so a stale
        // tab cannot resurrect a process. A fresh registration is the only point that re-arms it.
        LspNavigation.resetShared()
        EditorAiGatewayCache.reset()
        pluginContext = context

        markdownSettingsManager?.dispose()
        autoSaveSettingsManager?.dispose()
        externalReloadSettingsManager?.dispose()
        // Same re-entry treatment for the plugin scope and the watcher:
        // register can run again after an in-place update without a dispose,
        // and an un-cancelled previous scope keeps its polls, the composer
        // persistence jobs and the file watcher alive on the OLD git
        // provider - while ExternalChangeWatcher.install would ignore its
        // new arguments and the old watcher would sweep the new buffers.
        if (pluginScope != null) {
            ExternalChangeWatcher.uninstall()
            AiCompletionSettings.stop()
            PluginEditorSettings.stop()
            pluginScope?.cancel()
            pluginScope = null
            composerSessions = null
            composerAgent = null
        }
        val storage = runCatching {
            context.pluginStorageFactory?.createStorage(pluginId)
        }.getOrNull()
        val markdownSettings = MarkdownViewSettingsManager(storage = storage)
        markdownSettingsManager = markdownSettings
        val autoSaveSettings = AutoSaveSettingsManager(storage = storage)
        autoSaveSettingsManager = autoSaveSettings
        val externalReloadSettings = ExternalReloadSettingsManager(storage = storage)
        externalReloadSettingsManager = externalReloadSettings

        // Seed BossEditor with the host theme before anything composes: the settings
        // panel can be the first thing to render, and it resolves the follow-host
        // theme through a registry that is a plain map, not snapshot state.
        publishHostThemeToEditor()

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(EditorTabType) { tabInfo, ctx ->
            EditorTabComponent(ctx, tabInfo, context, markdownSettings, autoSaveSettings, externalReloadSettings)
        }

        // The diff tab renders here, not in the host: it is a variation of the
        // editor tab, and the lexer, semantic tokens and overview ruler that
        // make it readable are bundled in this plugin and unreachable from the
        // host. The host still creates and persists the config; it reaches us
        // as a DiffTabConfig (api 1.0.87).
        context.tabRegistry.registerTabType(DiffTabType) { tabInfo, ctx ->
            DiffTabComponent(ctx, tabInfo, context)
        }

        // Build the EditorTabPluginAPI impl once, shared by the settings panels and
        // the buffer MCP tools.
        //
        // Guarded: EditorTabPluginAPI is a shared-package (parent-first) class,
        // so on hosts that predate it the impl class fails to link. Those hosts
        // still render their own editor settings from their own BossEditor
        // dependency, so skipping registration degrades nothing there (the
        // buffer MCP tools report "API unavailable" instead).
        //
        // Belt and braces at the CURRENT floor: minApiVersion 1.0.87 and
        // minBossVersion 9.5.7 both postdate the buffer model, so a host that
        // could take this branch cannot load the plugin at all. Read the
        // null-handling below as insurance against the floor dropping later,
        // not as a path any shipping host walks.
        val editorApi =
            try {
                EditorTabPluginAPIImpl(context, markdownSettings, autoSaveSettings, externalReloadSettings)
            } catch (e: LinkageError) {
                null
            }

        // Composer (IDE batch P4.3): a plugin-level agent shared by every composer
        // tab and the ai_compose* MCP tools, keyed by session id. Built only
        // when the buffer API linked - the agent references its members, so a
        // host that predates them would fail to link it.
        //
        // Session STATE is plugin-level too (ComposerSessions), not tab-level:
        // a run has to survive the tab being switched away from, closed, or
        // reopened - see the class comment there.
        val composerSessions =
            if (editorApi != null) {
                pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                ComposerSessions(
                    store = runCatching { context.pluginStorageFactory?.createStorage(pluginId) }
                        .getOrNull()?.let { ComposerSessionStore(it) },
                    scope = pluginScope!!,
                )
            } else {
                null
            }
        val composerAgent =
            if (editorApi != null && composerSessions != null) {
                runCatching { ComposerAgent(context, editorApi, pluginScope!!, composerSessions) }.getOrNull()
            } else {
                null
            }
        if (composerAgent != null && composerSessions != null) {
            this.composerAgent = composerAgent
            this.composerSessions = composerSessions
            context.tabRegistry.registerTabType(ComposerTabType) { tabInfo, cctx ->
                ComposerTabComponent(cctx, tabInfo, composerAgent, composerSessions)
            }
        }

        // Contribute editor_read_file/write_file/detect_language plus the 1.0.87
        // buffer tools and the composer tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(
            EditorTabMcpToolProvider(
                pluginId,
                context,
                context.editorContentProvider,
                editorApi,
                composerAgent,
                composerSessions,
            ),
        )

        editorApi?.let { context.registerPluginAPI(it) }

        // Watch the files behind open buffers. Started here rather than per
        // tab so that one poll covers every viewport, including the diff tab's
        // editable pane, which shares the same buffers.
        val watcherScope = pluginScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also {
            pluginScope = it
        }
        ExternalChangeWatcher.install(watcherScope, { context.gitDataProvider }) {
            externalReloadSettingsManager?.enabled?.value ?: true
        }
        // The two settings file-polls used to run on GlobalScope, where nothing
        // could cancel them (they held this classloader alive after unload).
        // Run them on the plugin scope instead, stopped in dispose().
        AiCompletionSettings.start(watcherScope)
        PluginEditorSettings.start(watcherScope)

        // Warm up the bundled PSI stack off the UI thread. The host did this at
        // startup while BossEditor was on its classpath; the plugin owns it now.
        // Single-flight: semantic analysis awaits the same deferred, so files
        // opened while the warm-up runs still get colors when it completes.
        PluginSemanticTokenProvider.warmUp()
    }

    override fun dispose() {
        // Unregister tab types when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(EditorTabType.typeId)
        pluginContext?.tabRegistry?.unregisterTabType(DiffTabType.typeId)
        pluginContext?.tabRegistry?.unregisterTabType(ComposerTabType.typeId)
        pluginContext = null

        composerAgent = null
        // Written out before the scope that persists them is cancelled.
        composerSessions?.flushAll()
        composerSessions = null
        ExternalChangeWatcher.uninstall()
        // Language servers are child processes, and nothing else reaps them:
        // without this an unload leaves one per language running until BOSS exits.
        LspNavigation.disposeShared()
        // Stop the settings file-polls explicitly; scope cancellation below
        // is a backstop, not the mechanism.
        AiCompletionSettings.stop()
        PluginEditorSettings.stop()
        pluginScope?.cancel()
        pluginScope = null

        markdownSettingsManager?.dispose()
        markdownSettingsManager = null
        autoSaveSettingsManager?.dispose()
        autoSaveSettingsManager = null
        externalReloadSettingsManager?.dispose()
        externalReloadSettingsManager = null

        // Undo what register() published. The registry and the chrome holder live in
        // this plugin's classloader, so a reload replaces them anyway - symmetry is
        // cheaper than depending on that reasoning staying true.
        runCatching { unpublishHostThemeFromEditor() }

        // Tear down the bundled PSI stack (previously the host main.kt shutdown
        // hook's job, when BossEditor lived on the host classpath).
        runCatching { ProjectIndexer.shutdownGlobal() }
        runCatching { PSIBootstrap.shutdown() }
        runCatching { PSIThreadBridge.shutdown() }
    }

    private companion object {
        const val FALLBACK_VERSION = "0.0.0"

        /**
         * The version from the packaged plugin.json, so build.gradle.kts
         * (via processResources) stays the single source of truth. This
         * string was hand-maintained here and had already drifted once.
         */
        private fun readManifestVersion(): String {
            val stream =
                EditorTabDynamicPlugin::class.java.classLoader
                    ?.getResourceAsStream("META-INF/boss-plugin/plugin.json")
                    ?: return FALLBACK_VERSION
            return stream.use { input ->
                Regex(""""version"\s*:\s*"([^"]+)\"""")
                    .find(input.readBytes().decodeToString())
                    ?.groupValues
                    ?.get(1)
                    ?: FALLBACK_VERSION
            }
        }
    }
}
