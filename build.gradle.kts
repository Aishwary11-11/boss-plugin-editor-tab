import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
// 1.4.0 signals the new contract (same inversion terminal-tab 2.1.0 did for
// BossTerm): the plugin bundles bosseditor-compose-desktop privately; the host
// no longer carries it. The plugin also implements EditorTabPluginAPI
// (boss-plugin-api 1.0.53) so the host renders editor/LSP settings through it,
// and owns the PSI stack (kotlin-compiler-embeddable is bundled).
// 1.3.0: contributes MCP tools (editor_read_file/write_file/detect_language)
// via boss-plugin-api 1.0.51's McpToolProvider, surfaced on the `boss` MCP server.
// 1.4.19: editor tabs follow the BOSS host theme by default (followHostTheme), and
// the bundled BossEditor's own chrome follows it too - which hard-requires
// bosseditor 1.0.12 for EditorTheme.FOLLOW_HOST_THEME, ChromeColors and
// EditorChrome.
// 1.5.0: AI tab completion (ghost text) via boss-plugin-api 1.0.74's AiGatewayAPI,
// rendered as a plugin-side overlay (the bundled BossEditor has no inline-
// suggestion mechanism). Degrades to nothing when ai-gateway is absent.
// 1.6.0: IDE-features batch on boss-plugin-api 1.0.87 - live buffer model
// (readBuffer/applyEdit/observeChanges/focusedDocument/openEditor/openSplit)
// implemented over one shared buffer per path, "Open Diff" context-menu entry
// (host diff tab via GitDataProvider.openDiff), and the editor MCP tools
// (editor_read_buffer/editor_get_selection/editor_apply_edit/editor_open_split).
// 1.6.3: auto-bumped bundled BossEditor to 1.0.13
// (release: https://github.com/risa-labs-inc/BossEditor/releases/tag/v1.0.13).
version = "1.6.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"
val bossConsolePath = "../../BossConsole"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    val bossPluginApi = if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.87.jar")
    } else {
        // CI: use downloaded JAR
        files("build/downloaded-deps/boss-plugin-api.jar")
    }
    compileOnly(bossPluginApi)
    testImplementation(bossPluginApi)

    // BossEditor is private to this plugin (bundled into the plugin JAR by
    // buildPluginJar) — the host no longer carries it. Bumping bosseditor only
    // requires re-releasing this plugin, not BossConsole.
    implementation("com.risaboss:bosseditor-compose-desktop:1.0.13")

    // PSI (org.jetbrains.kotlin.psi.*) used by PluginSemanticTokenProvider.
    // BossEditor's POM carries kotlin-compiler-embeddable at runtime scope only,
    // so it must be declared for compilation; the runtime copy arrives
    // transitively and is bundled by buildPluginJar. Keep the version in sync
    // with BossEditor's.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21")

    // Compose Multiplatform — provided by the host classloader (parent-first
    // shared packages); bundling a second Compose runtime would break the
    // single-runtime invariant.
    compileOnly(compose.desktop.currentOs)
    compileOnly(compose.runtime)
    compileOnly(compose.ui)
    compileOnly(compose.foundation)
    compileOnly(compose.material)
    compileOnly(compose.materialIconsExtended)

    // Compose Icons - Feather icons (same as bundled editor); not host-shared,
    // bundled into the plugin JAR.
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // Decompose for ComponentContext — provided by host (shared package)
    compileOnly("com.arkivanov.decompose:decompose:3.3.0")
    compileOnly("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines — provided by host (shared package)
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // JSON serialization for settings — provided by host (shared package)
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Kotlin reflection for extracting filePath from host's EditorTabInfo —
    // kotlin.* is parent-first and the host ships kotlin-reflect
    compileOnly(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Same pattern as coroutines above: host-provided at runtime, but the
    // composer session store is @Serializable, so the tests that exercise it
    // need the runtime on the TEST classpath (it is not bundled in the jar).
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // The Compose compiler plugin also runs for compileTestKotlin and requires
    // the runtime on that classpath, even when an individual test is pure logic.
    testImplementation(compose.runtime)
    // androidx.compose.ui.graphics.Color, for the theme-derivation tests. Compose
    // is compileOnly for main (the host provides it) and bosseditor does not
    // re-export it, so without this the test classpath has no Color at all.
    // Test-only scope: it stays out of runtimeClasspath, which is what
    // buildPluginJar bundles from.
    testImplementation(compose.ui)
    // Reflection, for the test that compares PluginEditorSettingsData against
    // bosseditor's EditorSettings property by property. kotlin-reflect is compileOnly
    // for main (the host ships it), so the test classpath needs its own copy.
    testImplementation(kotlin("reflect"))
}

// The default :jar task would otherwise write build/libs/boss-plugin-editor-tab-<version>.jar —
// the exact archive buildPluginJar produces — and whichever task runs last wins.
// A thin-jar clobber ships a plugin missing all of ai.rever.bosseditor + the
// bundled compiler (this exact failure bit fluck-browser). Disabled rather than
// classified as "-thin": the release workflow uploads build/libs/*.jar and both
// the host's GitHub-asset regex and the plugin store's server-side asset pick
// would happily grab a thin jar if one were attached to the release.
tasks.named<Jar>("jar") {
    enabled = false
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-editor-tab-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // No native binaries may ship inside the plugin JAR: the host bundles this
    // JAR in its .app, and macOS notarization scans bundled JARs and rejects
    // unsigned Mach-O libs (Apple: "The binary is not signed"). The shaded
    // kotlin-compiler-embeddable carries jansi .jnilib/.dll (CLI terminal
    // colors) and lz4 .so (incremental-compilation caches) natives that the
    // embedded PSI path never loads — and both libraries fall back to
    // pure-Java when their natives are absent.
    exclude("**/*.jnilib", "**/*.dylib", "**/*.so", "**/*.dll")

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Code Editor Tab Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.editortab.EditorTabDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest
    from("src/main/resources")

    // Bundle bosseditor-compose-desktop + its plugin-private transitives.
    // Compose Multiplatform / decompose / kotlinx-coroutines /
    // kotlinx-serialization / slf4j stay on the host classloader
    // (parent-first shared packages).
    //
    // Filters match the gradle-cache PATH (…/files-2.1/<group>/<module>/…),
    // not the jar filename: bosseditor-compose-desktop's KMP jar is named
    // compose-ui-desktop-X.Y.Z.jar — the exact filename bossterm-compose-desktop
    // publishes under (see terminal-tab's buildPluginJar note), so filename
    // prefixes are ambiguous between the two libraries.
    from({
        configurations.runtimeClasspath.get().filter { jar ->
            val p = jar.path.replace('\\', '/')
            // BossEditor itself
            p.contains("/com.risaboss/bosseditor-compose-desktop/") ||
                // PSI stack: the embedded Kotlin compiler and its runtime deps.
                // org.jetbrains.kotlin.* packages are NOT host-shared (only
                // `kotlin.` is), so these resolve child-first and must ship here.
                p.contains("/org.jetbrains.kotlin/kotlin-compiler-embeddable/") ||
                p.contains("/org.jetbrains.kotlin/kotlin-script-runtime/") ||
                p.contains("/org.jetbrains.kotlin/kotlin-daemon-embeddable/") ||
                p.contains("/org.jetbrains.intellij.deps/trove4j/") ||
                // material3 (used by BossEditor's UI): the current host happens
                // to ship material3 (plugin-sandbox pulls it), so this copy is
                // normally inert — androidx.compose.* is parent-first. It is
                // bundled defensively: PluginClassLoader's super.loadClass falls
                // back to the plugin JAR on a parent miss, so the editor keeps
                // working if the host ever drops material3.
                p.contains("/org.jetbrains.compose.material3/") ||
                // Feather icon pack used by the editor UI (not host-shared)
                p.contains("/br.com.devsrsouza")
        }.map { zipTree(it) }
    })
}

// Sync version from build.gradle.kts into plugin.json (single source of truth).
// Declare `version` as a task input so a version-only bump invalidates the task —
// otherwise processResources stays UP-TO-DATE (its file inputs are unchanged) and
// ships a stale plugin.json whose version disagrees with the JAR filename.
tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

// MarkdownPreviewFixtureTest lays down the real preview page for
// src/test/js/preview-dom.test.mjs to open in a headless browser. Pass the
// destination explicitly rather than letting it resolve a relative path against
// the test JVM's working directory.
tasks.test {
    systemProperty(
        "preview.fixture.dir",
        layout.buildDirectory.dir("preview-fixture").get().asFile.absolutePath
    )
}

tasks.build {
    dependsOn("buildPluginJar")
}

// Task to deploy the plugin JAR to the BOSS plugins directory
tasks.register<Copy>("deployPlugin") {
    dependsOn("buildPluginJar")

    val userHome = System.getProperty("user.home")
    val pluginsDir = file("$userHome/.boss/plugins")

    from(layout.buildDirectory.file("libs/boss-plugin-editor-tab-${version}.jar"))
    into(pluginsDir)

    doFirst {
        pluginsDir.mkdirs()
    }

    doLast {
        println("Plugin JAR deployed to: $pluginsDir/boss-plugin-editor-tab-${version}.jar")
    }
}
