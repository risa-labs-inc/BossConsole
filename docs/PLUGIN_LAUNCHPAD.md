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
  - `id-format`: Enforces reverse domain notation (`^[a-zA-Z][a-zA-Z0-9_-]*(?:\.[a-zA-Z0-9_-]+)+$`).
  - `version-format`: Validates SemVer conformance (`MAJOR.MINOR.PATCH`).
  - `minApiVersion`: Asserts requirement $\le$ `HostMeta.CURRENT_API_VERSION` (`1.0.88`).
  - `entrypoint-class`: Validates fully qualified class name syntax.
  - `permissions`: Validates declared permissions against host RBAC permission identifier format.
  - `mcp-tools`: Validates tool names against `^mcp__[a-zA-Z0-9_-]+__[a-zA-Z0-9_-]+$`.
  - `host-manifest-contract`: Validates against host `PluginManifestReader.parseManifest` and `validateManifest`.
- **Archive Mode (`.jar` / `.zip`)**:
  - Validates archive readability.
  - Validates embedded `plugin.json`.
  - **Bytecode Verification**: Ensures `mainClass.replace('.', '/') + ".class"` exists within archive bytecode entries and implements `Plugin`.

#### Human-Readable Output Example
```
Validating plugin at C:\Users\dev\my-tools...
[✓] target-exists: Target path exists: C:\Users\dev\my-tools
[✓] manifest-exists: plugin.json found
[✓] manifest-json-valid: plugin.json parsed successfully
[✓] id-format: Plugin ID 'com.example.my-tools' follows reverse domain notation
[✓] version-format: Plugin version '0.1.0' is valid SemVer
[✓] min-api-version: apiVersion '1.0.89' is compatible (host: 1.0.89)
[✓] entrypoint-class: Entrypoint class 'com.example.mytools.MyToolsPlugin' is a valid fully-qualified class name
[✓] permissions: Declared permissions list is empty (accessible to all authenticated users)
[✓] mcp-tools: All 1 MCP tool declarations are valid
[✓] host-manifest-contract: Manifest conforms to host PluginManifestReader contract

[✓] Validation passed (10/10 checks passed)
```

---

### 3. `boss plugin link [<path>]`
Links or stages a local compiled plugin JAR into the BossConsole version-rotated development directory (`~/.boss/plugins/dev/<plugin-id>/v<timestamp>/<plugin-id>.jar`).

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
   `~/.boss/plugins/dev/<plugin-id>/v<timestamp>/<plugin-id>.jar`
   (or `~/.boss_debug/plugins/dev/...` in dev mode).
   This prevents Windows file-locking collisions on running classloaders. The host keeps the latest 3 builds plus every build that may have acquired a classloader during this session, including failed reload candidates. Those extra paths remain until restart; a later successful reload can prune them. The offline CLI never prunes staging.
4. **Instance Detection & Hot-Reload**:
   - **If BossConsole is running**: Dispatches `<PROTOCOL_VERSION> <TOKEN> PLUGIN_DEV_RELOAD <PLUGIN_ID>\n` over loopback socket and awaits synchronous host acknowledgment (`RELOAD_OK`). Any host-side exceptions are captured and returned as `RELOAD_FAILED <message>` without dropping the socket.
   - **If BossConsole is offline**: Stages the plugin cleanly into the version-rotated dev folder and reports ready for next launch (`status: "staged", running: false`).

The API, Toolbox, terminal, browser and editor system plugins cannot be overridden through dev staging. Live reload refuses protected identities and plugins owning native resources; their normal installation/update workflow and an application restart remain necessary. A plugin that exists only in dev staging has no installed record, so disabling it is not persisted across restart. Remove its staging directory while BOSS is stopped to remove that dev override.

A failed newest dev build falls back to the installed store build on startup, not to an older dev version. Rebuild and link a corrected version, or remove the failed staging directory while BOSS is stopped. A link during host startup can report failure before any plugin manager is ready; the staged build remains on disk. In-progress staging directories are left to their writer rather than pruned by another reload.

> **Note on Startup Precedence**: At application launch, BossConsole prioritizes staged development JARs in `~/.boss/plugins/dev/<plugin-id>/` over installed store versions for that same plugin ID via pre-load resolution (`resolvePersistedEntryPath` and `deduplicateJars`). Since `boss plugin link` pre-validates JARs before staging, dev artifacts are well-formed; if a dev build is missing or corrupt, startup automatically falls back to the installed store build. Protected system plugins and plugins requiring application restart (`HotReloadPolicy`) are never overridden by development JARs.

---

### 4. `boss plugin inspect [<path>]`
Reads a plugin's manifest and prints what it declares: identity, API requirements, permissions, MCP tools, and entrypoint. Useful before linking a third-party plugin, for a quick local sanity check after editing `plugin.json`, and in scripts that gate on what a plugin asks for.

Read-only: `boss plugin inspect` never touches the host, the plugin loader, or the filesystem beyond reading the manifest. It accepts the same inputs as `boss plugin link` - a directory containing `plugin.json` or `src/main/resources/META-INF/boss-plugin/plugin.json`, or a packaged `.jar`/`.zip` whose manifest lives at `META-INF/boss-plugin/plugin.json`. It does NOT resolve into `build/libs` the way `boss plugin link` does, because the question is "what does the source say?", not "what will run?".

```bash
# Inspect the current directory's manifest
boss plugin inspect

# Inspect a packaged JAR
boss plugin inspect build/libs/my-plugin-0.1.0.jar

# Inspect a third-party plugin you are considering installing
boss plugin inspect ~/Downloads/some-plugin.jar

# Machine-readable JSON output for scripting
boss plugin inspect build/libs/my-plugin-0.1.0.jar --json
```

#### Human-Readable Output Example
```
Plugin: Demo Plugin
  ID:          com.example.demo
  Display:     Demo Plugin
  Version:     1.2.3
  API:         1.0.88
  Entrypoint:  com.example.demo.DemoPlugin
  Author:      Demo Author
  License:     Apache-2.0
  Description:
    Demo plugin that exercises every manifest field.

Permissions (3):
  - network
  - terminal
  - secret.read (unrecognised)

MCP Tools (2):
  - mcp__demo__echo
    Echoes its argument.
  - mcp__demo__admin_tool [admin]
    Administers the demo.

Source:
  Archive:     /path/to/demo-1.2.3.jar
  Size:        4096 bytes
  Entries:     5
```

`systemPlugin: true` and `canUnload: false` are flagged with a `[flag]` marker only when they deviate from the defaults - a manifest that sets them to their defaults prints nothing extra. Unknown permissions are flagged with `(unrecognised)` so a typo (`secert.read`) does not silently grant permission to do nothing.

#### JSON Output Example
```json
{
  "status": "ok",
  "pluginId": "com.example.demo",
  "displayName": "Demo Plugin",
  "version": "1.2.3",
  "apiVersion": "1.0.88",
  "mainClass": "com.example.demo.DemoPlugin",
  "author": "Demo Author",
  "license": "Apache-2.0",
  "description": "Demo plugin that exercises every manifest field.",
  "manifestVersion": 1,
  "systemPlugin": false,
  "canUnload": true,
  "permissions": [
    { "id": "network", "recognised": true, "category": "NETWORK" },
    { "id": "terminal", "recognised": true, "category": "TERMINAL" },
    { "id": "secret.read", "recognised": false }
  ],
  "mcpTools": [
    { "name": "mcp__demo__echo", "description": "Echoes its argument.", "adminOnly": false },
    { "name": "mcp__demo__admin_tool", "description": "Administers the demo.", "adminOnly": true }
  ],
  "source": {
    "type": "archive",
    "archivePath": "/path/to/demo-1.2.3.jar",
    "archiveSizeBytes": 4096,
    "entryCount": 5
  }
}
```

Exit code: `0` on success, `1` if the target path is missing, malformed, or carries an unreadable manifest. The command never modifies anything, so a non-zero exit is purely diagnostic.

---

---

## Project Templates

| Template | Primary Use Case | Default Permissions | MCP Tools |
|---|---|---|---|
| `mcp-tool` | Exposing MCP tools to AI agents | `[]` (Open to authenticated users) | 1 sample action tool |
| `ui-panel` | Custom Compose Desktop UI panels & tabs | `[]` (Open to authenticated users) | None |
| `background-service` | Autonomous background workers / daemons | `[]` (Open to authenticated users) | None |
| `full` | Enterprise plugins combining UI, MCP, and CLI | `[]` (Open to authenticated users) | 1 action tool |

---

## Canonical Schemas

### `plugin.json` Schema
```json
{
  "pluginId": "com.example.sample-plugin",
  "displayName": "Sample Plugin",
  "version": "0.1.0",
  "description": "BossConsole plugin for sample-plugin",
  "author": "Boss Developer",
  "apiVersion": "1.0.88",
  "mainClass": "com.example.sampleplugin.SamplePluginPlugin",
  "requiredPermissions": [],
  "mcpTools": [
    {
      "name": "mcp__com_example_sample_plugin__action",
      "description": "Executes sample-plugin action tool",
      "adminOnly": false
    }
  ]
}
```

### Permission Model & RBAC
`requiredPermissions` declares the host RBAC permissions (e.g. `plugins.create`, `secret.read`, `api_key.create`) required to access and run the plugin.
- An empty list (`[]`) means the plugin is accessible to all authenticated users.
- Scaffolded starter templates emit `[]` by default so any non-admin developer can build, test, and link without hitting RBAC permission gating (`pluginAccessAllowed`).
- When non-empty, permissions must follow the standard dot/dash/underscore RBAC identifier format (`^[a-zA-Z0-9]+([._-][a-zA-Z0-9]+)*$`).

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
