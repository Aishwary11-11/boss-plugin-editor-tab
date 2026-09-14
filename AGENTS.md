# AGENTS.md

## Project Overview

**Code Editor Tab** (`ai.rever.boss.plugin.dynamic.editortab`) is a dynamic plugin for the BOSS desktop application.

Code editor tab with syntax highlighting, code folding, and run gutter icons

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.editortab`
- **Main Class**: `ai.rever.boss.plugin.dynamic.editortab.EditorTabDynamicPlugin`
- **API Version**: pinned in `build.gradle.kts` (currently `boss-plugin-api-1.0.87.jar`); the floor moves whenever the plugin uses a newer surface

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
./gradlew test               # Kotlin tests
```

### Markdown preview DOM tests

The preview pane is a JavaScript page, so its behaviour is tested by running it
in a headless browser rather than by grepping its source. Two steps, because the
JS test opens the page the Kotlin test writes:

```bash
./gradlew test --tests '*MarkdownPreviewFixtureTest'   # writes build/preview-fixture/
node src/test/js/preview-dom.test.mjs                  # opens it, asserts on the DOM
```

No npm dependencies; needs Node ≥ 22 and a Chromium binary, taken from `$CHROME`
if set and otherwise found in the usual Playwright/Puppeteer cache locations.
Both suites run on every PR via `.github/workflows/tests.yml`.

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **bosseditor-compose-desktop**: bundled privately; 1.0.13 provides multiline inline AI suggestions and the LSP startup fixes used here
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.
