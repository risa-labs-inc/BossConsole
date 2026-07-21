# BossEditor

BossEditor is the code editor library providing IDE-like editor features in BOSS.

**Repository**: [risa-labs-inc/BossEditor](https://github.com/risa-labs-inc/BossEditor)
**Maven Artifact**: `com.risaboss:bosseditor-compose-desktop`
**Dependency**: **Not a host dependency.** The `editor-tab` plugin bundles
BossEditor (and its `kotlin-compiler-embeddable` PSI stack) privately inside its
own JAR — the same arrangement as BossTerm inside `terminal-tab`. The plugin's
classloader resolves `ai.rever.bosseditor.*` child-first from the plugin JAR
while sharing the host's Compose runtime via parent delegation. Bumping
BossEditor only requires re-releasing the plugin, not BossConsole.

The host talks to the editor through `EditorTabPluginAPI` (plugin-api-core):
the Settings window's Editor and Language Server sections delegate to
`EditorSettingsPanel` / `LspSettingsPanel` served by the plugin via
`EditorAPIAccess` (mirroring `TerminalAPIAccess`).

## LSP Integration

Language Server Protocol support for multi-language editing.

**Capabilities**:
- Code completion
- Go-to-definition
- Find references
- Diagnostics (errors, warnings)
- Semantic token highlighting

## PSI Code Analysis

Kotlin navigation using kotlin-compiler-embeddable for native Kotlin support without external LSP.

**Important Notes**:
- `parseKotlinFile(fileName, content)` returns non-nullable `KtFile`
- `parseFile(file)` returns nullable `KtFile?`
- K1 API deprecation warnings are intentional (awaiting K2 Analysis API stability)
- The PSI stack (including `kotlin-compiler-embeddable`) ships inside the
  `editor-tab` plugin JAR; the host has no PSI dependency of its own

## Advanced Editor Features

**Visual Enhancements**:
- Code minimap with visual navigation
- Fixed header display during scrolling (sticky scroll)
- Contextual breadcrumb navigation
- Bracket pair colorization (rainbow brackets)
- Git blame line annotations
- Type and parameter hints (inlay hints)
- Highlight symbol occurrences

## Editor Settings

**Configuration**:
- Settings persisted to `~/.boss/editor-settings.json` (or `~/.boss_debug/` in dev mode)
- LSP settings persisted to `~/.boss/lsp-settings.json`

**Available Settings**:
- Font family and size
- Line numbers, minimap, breadcrumbs toggles
- Tab size and spaces vs tabs
- Word wrap
- Rainbow brackets
- Sticky scroll
- Inlay hints
