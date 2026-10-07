# BOSS Code Editor Tab Plugin

A dynamic plugin that provides code editor tabs in the main panel area of BOSS Console.

## Features

- Syntax highlighting for 50+ languages (via RSyntaxTextArea)
- Code folding
- Bracket matching
- Line numbers with fold indicators
- Run gutter icons for detected main functions
- File modification tracking with save support (Cmd+S)
- Theme integration with BOSS themes

## Requirements

- BOSS Console 8.16.26 or later
- Plugin API 1.0.11 or later

## Installation

1. Download the latest JAR from the [Releases](https://github.com/risa-labs-inc/boss-plugin-editor-tab/releases) page
2. Open BOSS Console
3. Go to Settings > Plugins > Install from File
4. Select the downloaded JAR file

Or install via Plugin Store in BOSS Console.

## Building

```bash
./gradlew jar
```

The plugin JAR will be created in `build/libs/`.

## Supported Languages

The editor supports syntax highlighting for many languages including:
- Kotlin, Java, Scala
- JavaScript, TypeScript
- Python, Ruby, PHP
- C, C++, Rust, Go
- HTML, CSS, XML, JSON, YAML
- Markdown, TOML
- SQL
- And many more...

## Saving files

Manual save, autosave, and editable diff-pane saves share a protected local save
transaction. See [protected saves](docs/protected-saves.md) for failure behavior
and filesystem limits.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2025-2026 Risa Labs Inc.
