# BossConsole Plugin Launchpad & Developer CLI (`boss plugin`)

The **Plugin Launchpad** provides developer tooling and a high-performance CLI suite for scaffolding, validating, and hot-reloading third-party plugins in BossConsole.

---

## Command Reference

### 1. `boss plugin init <name>`
Scaffolds a new, buildable plugin project with Gradle Kotlin Multiplatform and `boss-plugin-api`.

```bash
# Basic MCP tool plugin scaffold
boss plugin init my-tools

# Explicit template selection
boss plugin init my-service --template background-service

# Custom output directory and overwrite flag
boss plugin init my-panel --template ui-panel --dir ~/projects/my-panel --force

# Machine-readable JSON output
boss plugin init my-full-plugin --template full --json
```

#### Options & Flags
| Option | Short | Description | Default |
|---|---|---|---|
| `--template` | `-t` | Plugin template (`mcp-tool`, `ui-panel`, `background-service`, `full`) | `mcp-tool` |
| `--dir` | `-d` | Output directory path | `./<name>` |
| `--force` | `-f` | Overwrite target directory if non-empty | `false` |
| `--json` | | Emit structured JSON output | `false` |

```json
{
  "status": "scaffolded",
  "pluginId": "my-tools",
  "template": "mcp-tool",
  "targetDir": "C:/Users/dev/my-tools",
  "files": [
    "plugin.json",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar",
    "src/main/kotlin/com/example/mytools/MyToolsPlugin.kt",
    "src/test/kotlin/com/example/mytools/MyToolsPluginTest.kt",
    ".gitignore",
    "README.md"
  ]
}
```

---

### 2. `boss plugin validate [<path>]`
Validates a plugin source directory or packaged `.jar`/`.zip` archive against BossConsole canonical schemas, permissions, and bytecode integrity.

```bash
# Validate current directory
boss plugin validate

# Validate specific directory
boss plugin validate path/to/my-plugin

# Validate packaged JAR archive
boss plugin validate build/libs/my-plugin-0.1.0.jar

# Machine-readable JSON validation
boss plugin validate . --json
```

#### Verification Diagnostics
- **Directory Mode**:
  - `target-exists`: Verifies directory exists.
  - `manifest-exists`: Confirms presence of `plugin.json`.
  - `manifest-json-valid`: Parses manifest schema without fatal exceptions.
  - `id-format`: Checks ID against regex `^[a-z0-9-]+$`.
  - `version-format`: Validates SemVer conformance (`MAJOR.MINOR.PATCH`).
  - `minApiVersion`: Asserts requirement $\le$ `HostMeta.CURRENT_API_VERSION` (`1.0.88`).
  - `entrypoint-class`: Validates fully qualified class name syntax.
  - `permissions`: Validates declared permissions against the allowed host registry.
  - `mcp-tools`: Validates tool names against `^mcp__[a-z0-9_-]+__[a-z0-9_-]+$`.
- **Archive Mode (`.jar` / `.zip`)**:
  - Validates archive readability.
  - Validates embedded `plugin.json`.
  - **Bytecode Verification**: Ensures `entrypointClass.replace('.', '/') + ".class"` exists within archive bytecode entries.

#### Human-Readable Output Example
```
Validating plugin at C:\Users\dev\my-tools...
[✓] target-exists: Target path exists: C:\Users\dev\my-tools
[✓] manifest-exists: plugin.json found
[✓] manifest-json-valid: plugin.json parsed successfully
[✓] id-format: Plugin ID 'my-tools' matches ^[a-z0-9-]+$
[✓] version-format: Plugin version '0.1.0' is valid SemVer
[✓] min-api-version: minApiVersion '1.0.88' is compatible (host: 1.0.88)
[✓] entrypoint-class: Entrypoint class 'com.example.mytools.MyToolsPlugin' is a valid fully-qualified class name
[✓] permissions: All declared permissions (1) are allowed
[✓] mcp-tools: All 1 MCP tool declarations are valid

[✓] Validation passed (9/9 checks passed)
```

---

### 3. `boss plugin link [<path>]`
Links or stages a local compiled plugin JAR into the BossConsole version-rotated development directory (`$BOSS_HOME/plugins/dev/<plugin-id>/v<timestamp>/<plugin-id>.jar`).

```bash
# Link local plugin project (must be built first with ./gradlew build)
boss plugin link .

# Link specific directory
boss plugin link path/to/my-plugin

# Machine-readable JSON output
boss plugin link . --json
```

#### Hot-Reload Lifecycle
1. **Artifact Resolution**: Resolves the compiled JAR from `build/libs/<plugin-id>-*.jar`. Fails fast with an actionable error if the project has not been compiled (`./gradlew build` required before linking).
2. **Pre-flight Validation**: Automatically runs `PluginValidator.validate` against the target JAR archive before modifying any staging files. Invalid plugins fail fast with exit code `1`.
3. **Version-Rotated Staging**: Stages the JAR into:
   `$BOSS_HOME/plugins/dev/<plugin-id>/v<timestamp>/<plugin-id>.jar`
   This prevents Windows file-locking collisions on running classloaders. Retains the latest 3 builds and prunes older versions automatically.
4. **Instance Detection & Hot-Reload**:
   - **If BossConsole is running**: Dispatches `<TOKEN> PLUGIN_DEV_RELOAD <PLUGIN_ID>\n` over loopback socket and awaits synchronous host acknowledgment (`RELOAD_OK`). Any host-side exceptions are captured and returned as `RELOAD_FAILED <message>` without dropping the socket.
   - **If BossConsole is offline**: Stages the plugin cleanly into the version-rotated dev folder and reports ready for next launch (`status: "staged", running: false`).

---

## Project Templates

| Template | Primary Use Case | Default Permissions | MCP Tools |
|---|---|---|---|
| `mcp-tool` | Exposing MCP tools to AI agents | `mcp` | 1 sample action tool |
| `ui-panel` | Custom Compose Desktop UI panels & tabs | `notifications` | None |
| `background-service` | Autonomous background workers / daemons | `terminal`, `notifications` | None |
| `full` | Enterprise plugins combining UI, MCP, and CLI | `mcp`, `terminal`, `notifications`, `network` | 1 action tool |

---

## Canonical Schemas

### `plugin.json` Schema
```json
{
  "id": "sample-plugin",
  "name": "Sample Plugin",
  "version": "0.1.0",
  "description": "BossConsole plugin for sample-plugin",
  "author": "Boss Developer",
  "minApiVersion": "1.0.88",
  "entrypointClass": "com.example.sampleplugin.SamplePluginPlugin",
  "permissions": [
    "mcp"
  ],
  "mcpTools": [
    {
      "name": "mcp__sample_plugin__action",
      "description": "Executes sample-plugin action tool",
      "adminOnly": false
    }
  ]
}
```

### Allowed Permissions
- `network`
- `filesystem`
- `terminal`
- `browser`
- `notifications`
- `auth`
- `mcp`
- `editor`
- `clipboard`
- `settings`
- `system`
- `storage`

---

## Typical Developer Workflow

```bash
# 1. Initialize project
boss plugin init code-helper --template mcp-tool
cd code-helper

# 2. Build plugin JAR (Required before linking)
./gradlew build

# 3. Validate project structure and compiled JAR
boss plugin validate .

# 4. Link & live-reload into running BossConsole
boss plugin link .
```
