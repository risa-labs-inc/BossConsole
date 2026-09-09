# BossConsole Command Line Interface (CLI)

BossConsole provides a native command-line interface that allows developers and **external AI agents** (Claude Code, Gemini CLI, Aider, custom scripts) to interact directly with the running BossConsole desktop application.

---

## Installation & Setup

When BossConsole is installed, the `boss` executable is placed on your system PATH or accessible via:
- **macOS / Linux**: `boss` (or `/Applications/BOSS.app/Contents/MacOS/boss`)
- **Windows**: `boss.bat` or `boss.ps1` (or in `%LOCALAPPDATA%\Programs\BOSS\boss.bat`)

You can install or update the CLI symlinks inside BossConsole via **Toolbox → Tools → Install BOSS CLI**.

---

## Core Desktop Commands

| Command | Description | Example |
|---|---|---|
| `boss <url>` | Opens a URL in an embedded browser tab | `boss https://github.com` |
| `boss file <path>` | Opens a file in the BossEditor | `boss file src/main.kt` |
| `boss folder <path>` | Opens a project folder in workspace | `boss folder ~/projects/my-app` |
| `boss workspace <file>` | Loads a workspace configuration | `boss workspace ./workspace.json` |
| `boss terminal` | Opens a new integrated BossTerm pane | `boss terminal` |
| `boss status` | Checks running BossConsole health and status | `boss status --json` |
| `boss mcp <action>` | Discovers and invokes MCP tools | `boss mcp list` |
| `boss completion <shell>` | Generates shell tab-completion scripts | `boss completion bash > ~/.boss-complete.sh` |

---

## CLI Agent Harness (`boss status` & `boss mcp`)

The packaged executable is required. Set `BOSS_BIN` (macOS/Linux) or `BOSS_EXE` (Windows) for a nonstandard install. These commands do not start the desktop when it is closed.

The **CLI Agent Harness** bridges terminal coding agents directly into the running desktop harness via an authenticated local Unix socket (macOS/Linux) or loopback TCP channel (Windows). Terminal agents can inspect the workspace, drive the browser, run git operations, and trigger automation without running a heavy SSE or WebSocket client.

### 1. `boss status`

Inspects the running BossConsole desktop instance, reporting active project, memory consumption, version, and platform health.

```bash
# Human-readable status report
boss status

# Machine-readable JSON output
boss status --json
```

**JSON Output Example**:
```json
{
  "running": true,
  "version": "9.5.7",
  "os": "Windows 11",
  "arch": "amd64",
  "activeProject": "BossConsole",
  "memory": {
    "usedMb": 412,
    "maxMb": 2048,
    "heapPercent": 20
  }
}
```

---

### 2. `boss mcp list`

Discovers all registered and accessible MCP tools exposed by BossConsole and its active plugins.

```bash
# List all registered tools
boss mcp list

# Filter tools by keyword (saves agent context tokens)
boss mcp list --filter browser
boss mcp list -f git

# Emit machine-readable JSON array of tools
boss mcp list --json
boss mcp list -f terminal --json
```

---

### 3. `boss mcp describe <tool_name>`

**Token Guardrail**: Dumping 100+ tool schemas into an LLM prompt can consume 15,000+ tokens. `boss mcp describe` prints only the selected tool and its input schema. Discovery currently transfers the accessible registry over local IPC before filtering. Use the exact registered name from `boss mcp list`; client-specific prefixes such as `mcp__boss__` are not part of registry names.

```bash
# Human-readable tool details and required permissions
boss mcp describe browser_navigate

# Machine-readable JSON schema
boss mcp describe browser_navigate --json
```

**Sample Output**:
```
MCP Tool: browser_navigate
Plugin:   fluck-browser
Access:   Standard

Description:
  Navigates the active browser tab to the specified URL.
  Arguments: {"url": "<target_url>"}
```

---

### 4. `boss mcp invoke <tool_name>`

Invokes tools exposed by the host plugin registry. Built-in terminal MCP server tools are a separate surface and are not included. JSON arguments are bounded to 767 KiB so their Base64 framing fits the 1 MiB request limit.

```bash
# 1. Direct arguments with JSON string
boss mcp invoke search_workspace --args '{"query":"SingleInstanceManager"}'

# 2. Raw output mode (-r) for shell scripts and piping
boss mcp invoke git_status -r | grep "modified"

# 3. Piping multi-KB / multiline payloads from standard input
cat query.json | boss mcp invoke run_sql --stdin
echo '{"path": "build.gradle.kts"}' | boss mcp invoke read_file -a -

# 4. Client wait timeout (default: 35 seconds, range: 1-60)
# Server execution is limited to 30 seconds; a shorter client wait does not cancel work.
boss mcp invoke heavy_build -a '{"target":"desktopJar"}' --timeout 60

# 5. Full structured JSON envelope
boss mcp invoke workspace_info --json
```

---

### 5. `boss completion <bash|zsh|fish>`

Generates tab-autocompletion scripts for your shell, completing subcommands and MCP actions (`list`, `describe`, `invoke`):

```bash
# Bash completion setup
boss completion bash > ~/.boss-completion.bash
echo "source ~/.boss-completion.bash" >> ~/.bashrc

# Zsh completion setup
boss completion zsh > ~/.zsh/_boss
echo "source ~/.zsh/_boss" >> ~/.zshrc

# Fish completion setup
boss completion fish > ~/.config/fish/completions/boss.fish
```

---

## Process Exit Codes & Stream Guarantees

The CLI adheres to strict UNIX process exit codes and standard stream separation:

- **Exit Code `0`**: Operation succeeded. `stdout` contains the tool output or JSON response.
- **Exit Code `1`**: Tool execution failed (`isError == true`), invalid tool arguments, or desktop app offline. Clikt usage errors also use exit code `1`. The error description is written strictly to `stderr`, leaving `stdout` clean so shell pipelines do not ingest corrupted data.

### Offline Fail-Fast
If BossConsole is not running, commands fail immediately without hanging:
```bash
$ boss mcp list
Error: BOSS is not running. Launch BOSS to list MCP tools.
$ echo $?
1
```

---

## Security & Governance

1. **Local Authentication**: Uses a per-launch 32-byte cryptographically secure random token written to an owner-restricted runtime directory. Other OS users cannot read that token. Processes running as the same OS user can read it and are trusted by this channel.
2. **Non-Blocking Coroutines**: Tool execution runs on a background client thread with a cooperative 30-second coroutine timeout. Socket watchdogs bound client waits; a blocking plugin handler may continue after a timeout.
3. **RBAC & Kill-Switch**: All calls go through `McpToolRegistryImpl`, enforcing role-based permissions and per-tool user disable switches (`mcp-disabled-tools.json`).

PowerShell: use `--stdin` for JSON on Windows PowerShell 5.1 or legacy native argument passing, which can strip embedded quotes from `--args`. PowerShell 7.3+ uses Standard argument passing in this launcher. Packaged Windows console I/O still requires platform verification.

Registry access before sign-in follows the existing host policy: tools without required permissions or an admin requirement remain available. For an admin operator, the per-tool disabled switch is the remaining registry access control.
