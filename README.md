<div align="center">

<img src="docs/boss-logo.png" alt="BOSS" width="128" height="128" />

### 🔬 The operator's console for AI agents — and the scientists who direct them

**The first open-source, multi-platform harness for AI agents — an evolvable toolbox for agents and the humans they work with.**

_🧪 Built for enterprises, science, and research._

Bring your own agent — Claude Code, Codex, Gemini, or OpenCode — and give it a real browser, terminal, editor, secrets, and automation. Then decide exactly what each one is allowed to touch. Run it on your own machine, hand a live terminal to your phone with a QR code, and reshape any tool — by hand or by the agent itself — while the app keeps running.

[![BOSS Version](https://img.shields.io/github/v/release/risa-labs-inc/BossConsole-Releases.svg?label=BOSS&color=brightgreen)](https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest)
[![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Windows%20%7C%20Linux-blue.svg)](https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

[**⬇ Download**](#downloads) · [📊 Compare](#how-boss-compares) · [🤖 Run an agent](#run-any-ai-coding-agent) · [🔐 Governance](#you-decide-what-your-agents-can-touch) · [🧰 Toolbox](#toolbox--an-app-store-inside-the-app) · [🖥️ BossTerm](#bossterm--a-terminal-you-can-share-to-any-device)

</div>

> **Just want to download BOSS?** Head to [**BossConsole-Releases**](https://github.com/risa-labs-inc/BossConsole-Releases) for pre-built installers.

Built with Kotlin Multiplatform and Compose Multiplatform, BOSS unifies an embedded browser, a blazing-fast shareable terminal, a code editor, an extensible **Toolbox** of plugins, and a governed **MCP** tool layer into one desktop workspace for complex, AI-assisted work.

---

## How BOSS compares

Every other AI-agent desktop app and agentic IDE — **Claude Desktop, OpenAI Codex, Google Antigravity, Cursor, and Windsurf/Devin** — is closed source, and most lock you to a single vendor's model. **BOSS is the first open-source, multi-platform harness in the category:** Apache-2.0, cross-platform, and built to run *any* agent.

The deeper difference: **BOSS is both the agent's home _and_ an agent-operable app.** The same MCP layer your agent uses to do work also exposes BOSS itself — its tabs, terminals, browser, editor, git, secrets, and automation — as **100+ `mcp__boss__*` tools**. Read-only tools (list tabs, read a pane's output, tail the console, snapshot performance, inspect git) give an agent live **situational awareness of the workspace**; action tools let it act on what it finds. So the agent doesn't just chat about your code — it perceives the running app and drives it, carrying out multi-step tasks **autonomously**.

And unlike most of the field, BOSS bundles a **real embedded browser — Fluck —** the agent can navigate, script, and automate (`browser_navigate`, `browser_run_js`, plus record-and-replay RPA), with logins filled from the **Secret Manager** and never handed to the model.

| | **BOSS** | Claude Desktop | Codex | Google Antigravity | Cursor | Windsurf / Devin |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| **Open source** | ✅ Apache-2.0 | ❌ | △ CLI only¹ | ❌ | ❌ | ❌ |
| **Bring any agent / model** | ✅ BYO agent | ❌ Claude only | ❌ OpenAI only | ✅ multi | ✅ multi + BYOK | ✅ multi + BYOK |
| **MCP tools** | ✅ 100+ built-in | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Embedded browser (agent-driven)** | ✅ Fluck | △ Computer Use² | ❌ | ✅ + DevTools | ❌ | ❌ |
| **Integrated terminal** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Share terminal session** (QR / multi-user / E2E) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Per-tool governance** (RBAC + kill-switch) | ✅ | △³ | △³ | △³ | △ Teams | △ Enterprise |
| **Plugin ecosystem** | ✅ Toolbox store | △ MCP extensions | △ IDE ext. | ✅ VS Code ext. | ✅ VS Code ext. | ✅ VS Code ext. |
| **Platforms** | mac · Win · Linux (x64+ARM64) | mac · Win · Linux (beta) | mac · Win · Linux⁴ | mac · Win · Linux | mac · Win · Linux | mac · Win · Linux |

<sub>✅ yes · △ partial/limited · ❌ no. &nbsp;¹ Only OpenAI's Codex **CLI** is open source (Apache-2.0); the Codex desktop app is proprietary. &nbsp;² Claude Desktop's "Computer Use" controls the whole screen rather than bundling a scriptable in-app browser. &nbsp;³ Enterprise-plan admin controls exist, but fine-grained per-tool RBAC isn't documented. &nbsp;⁴ Codex CLI is cross-platform; the desktop app is macOS/Windows. &nbsp;Compiled from public sources, July 2026 — these products move fast, so corrections are welcome via issue or PR.</sub>

---

## Contents

- [How BOSS compares](#how-boss-compares)
- [Downloads](#downloads)
- [Why BOSS](#why-boss)
- [Run any AI coding agent](#run-any-ai-coding-agent)
- [You decide what your agents can touch](#you-decide-what-your-agents-can-touch)
- [MCP — give agents real tools](#mcp--give-agents-real-tools)
- [Toolbox — an app store inside the app](#toolbox--an-app-store-inside-the-app)
- [BossTerm — a terminal you can share to any device](#bossterm--a-terminal-you-can-share-to-any-device)
- [Case study: the DNA Origami Inventor](#case-study-the-dna-origami-inventor)
- [Design System](#design-system)
- [Development](#development)
- [CLI](#cli) · [CI/CD](#cicd) · [Documentation](#documentation) · [Open source & ecosystem](#open-source--ecosystem)

---

## Downloads

| Platform | Architecture | Download |
|----------|--------------|----------|
| **macOS** | Universal (Apple Silicon + Intel) | [Homebrew](https://formulae.brew.sh/cask/boss) \| [DMG](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=dmg) |
| **Windows** | x64 | [MSI](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=msi) |
| **Windows** | ARM64 | [MSI](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=msi&arch=arm64) |
| **Linux** | AMD64 | [DEB](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=deb&arch=amd64) \| [RPM](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=rpm&arch=amd64) \| [JAR](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=jar&arch=amd64) |
| **Linux** | ARM64 | [DEB](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=deb&arch=arm64) \| [RPM](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=rpm&arch=arm64) \| [JAR](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=jar&arch=arm64) |

Download links always fetch the newest release directly (via the [`latest-release`](supabase/functions/latest-release/app.ts) edge function; the saved file keeps its versioned name). Release metadata as JSON — version, assets, sha256 checksums — is at [`?app=boss`](https://api.risaboss.com/functions/v1/latest-release?app=boss). To browse all versions, see [BossConsole-Releases](https://github.com/risa-labs-inc/BossConsole-Releases/releases).

### Quick Install

```bash
# macOS (Homebrew — Recommended)
brew install --cask boss

# macOS/Linux (Universal Script)
curl -fsSL https://raw.githubusercontent.com/risa-labs-inc/BossConsole-Releases/main/install.sh | bash

# Windows (PowerShell)
iwr -useb https://raw.githubusercontent.com/risa-labs-inc/BossConsole-Releases/main/install.ps1 | iex
```

---

## Why BOSS

- 🤖 **Bring your own agent** — run **Claude Code, Codex, Gemini, or OpenCode** in a BOSS terminal and give them a real toolset (browser, files, git, shell, secrets, automation) over MCP.
- 🧰 **Extensible by design** — a built-in **Toolbox** plugin store; the terminal, editor, and browser are themselves hot-loadable plugins, and you can scaffold your own with an AI agent.
- 🔐 **You stay in control** — server-enforced role-based access control, a per-user kill-switch for every agent tool, and user-scoped secrets. You decide exactly what an agent can call.
- 🖥️ **A terminal built to share** — hand a live terminal session to your phone via QR, or to a teammate over an end-to-end-encrypted link, with view-only or full control.
- ⚡ **Fast and native** — Compose Multiplatform desktop app with the built-in **Fluck** browser, on macOS, Windows, and Linux (x64 + ARM64).
- ⚙️ **Built to scale** — multi-threaded by default, on an out-of-process microkernel architecture engineered to run from a laptop to a supercomputer.

---

## Run any AI coding agent

BOSS is agent-agnostic. Open a terminal, launch your preferred CLI, and — once attached — it gains the `boss` toolset automatically.

| Agent | Binary | Attach method |
|-------|--------|---------------|
| **Claude Code** | `claude` | `claude mcp add --scope user --transport sse boss <url>` |
| **Codex** | `codex` | `codex mcp add boss --url <url>/mcp` (streamable HTTP) |
| **Gemini CLI** | `gemini` | `gemini mcp add boss <url> --transport sse --scope user` |
| **OpenCode** | `opencode` | writes the server into `~/.config/opencode/opencode.json` |

**How it works:** the `terminal-tab` plugin runs a small loopback MCP server named **`boss`**. You attach a CLI once (one click from **Toolbox → MCP**, or the BossTerm AI menu); BOSS then **re-attaches it automatically** on every restart and port change, and injects `BOSS_MCP_PORT` into each terminal so an agent launched inside BOSS always dials the right instance. If a CLI isn't scriptable, BOSS copies a ready-to-paste config snippet to your clipboard.

**Scaffold a tool for any of them** — the built-in **Tool Creator** generates a new plugin (build files, manifest, skeleton UI, CI) and writes its skill in *all four* CLI formats (`.claude/`, `.codex/`, `.gemini/`, `.opencode/`), so the repo works with whichever agent you open it with — then launches that agent in a fresh terminal to start building.

**A tool-creation framework for a reinforcement layer.** Together, Tool Creator and its companion **Tool Evolver** let an agent build *and continuously improve* its own capabilities: scaffold a tool, probe how it behaves (memory, leaks, logs, output), then evolve it — hot-reloaded into the running app and opened as a PR. Tools are created, measured, and reinforced in a loop, so the toolbox gets better with use.

---

## You decide what your agents can touch

An agent with tools is only as safe as the controls around it. BOSS is a **governed** environment for tool-using agents — not a black box.

- **Server-enforced RBAC.** Roles and permissions live in Postgres and are enforced server-side via row-level security (Supabase). Plugins declare the permissions they need; a plugin — and its tools — only appear for users whose role grants them. See the [RBAC Guide](docs/RBAC_GUIDE.md).
- **A kill-switch for every tool.** Every `mcp__boss__*` tool an agent can call is listed in **Toolbox → MCP**, and you can toggle any of them off. The exposed set is `all tools − your disabled set − permission-denied`, persisted to `~/.boss/mcp-disabled-tools.json` and enforced on the live server. Disable a plugin and its tools vanish from every agent instantly.
- **User-scoped secrets.** The [Secret Manager](#security--secrets) stores encrypted credentials that are row-level-scoped to you. Its **browser auto-fill** injects a username/password straight into a web page's form fields — the value goes to the page, never to the model. (A secret is handed to an agent only if you explicitly call the permission-gated `secret_get` tool, which you can also toggle off.)
- **Signed plugins.** Plugins installed from the BOSS Plugin Store carry a store signature binding `pluginId | version | sha256`; a **tampered or invalid signature fails closed** at download and load time — a re-signed or swapped JAR won't load.
- **Fault isolation.** Each plugin runs in a supervised scope with a watchdog and auto-restart, so a crashing or hung plugin can't take down the host.

> **Honest scope:** the `boss` MCP server is loopback-only and single-user (local machine), and plugins run in-process (crash-isolated, not OS-sandboxed). BOSS's guarantees are about **governance** — RBAC, per-tool toggles, secret scoping, and signed plugins — giving you fine-grained say over what an agent can do, rather than OS-level process sandboxing.

---

## MCP — give agents real tools

BOSS speaks the **Model Context Protocol**. The `terminal-tab` plugin hosts a loopback `boss` server (SSE over HTTP, `127.0.0.1:7677`, with a fallback port walk), and every active plugin contributes tools that surface to agents as **`mcp__boss__<tool>`** — roughly **100+ tools across ~20 plugins**, appearing and disappearing automatically as plugins load. A sampling:

| Area | Example tools |
|------|---------------|
| Terminal | `run_command`, `run_in_sidebar`, `run_in_panel`, `list_tabs`, `read_scrollback`, `send_input` |
| Code & Git | `codebase_read`, `codebase_write`, `codebase_tree`, `git_status`, `git_log`, `git_stage`, `git_checkout` |
| Browser | `browser_navigate`, `browser_get_url`, `browser_run_js` |
| Secrets | `secrets_list`, `secret_search`, `secret_get`, `secret_create` |
| Automation | `flow_run`, `rpa_run`, `rpa_record_toggle`, `llmrpa_run`, `evolver_evolve` |
| Productivity | `bookmarks_list`, `bookmark_add`, `downloads_list`, `plugins_list` |

Plugin authors add tools by implementing `McpToolProvider` (boss-plugin-api 1.0.51+). Full reference: [**PLUGIN_DEVELOPMENT.md**](https://github.com/risa-labs-inc/boss-plugins/blob/main/PLUGIN_DEVELOPMENT.md) in boss-plugins.

---

## Toolbox — an app store inside the app

**Toolbox** is BOSS's built-in plugin store (a left-sidebar panel; internally the `plugin-manager` system plugin). Browse, install, update, and enable/disable plugins; toggle individual MCP tools; and — for admins — **Create** new plugins with an AI agent via Tool Creator. Plugins are hot-loaded at runtime and live in the separate [**boss-plugins**](https://github.com/risa-labs-inc/boss-plugins) repo.

### Core tabs
| Plugin | What it does |
|--------|--------------|
| **[Terminal Tab](https://github.com/risa-labs-inc/boss-plugin-terminal-tab)** | Full terminal built on the BossTerm library — persistent sessions, split panes, sharing, and the `boss` MCP server |
| **[Code Editor Tab](https://github.com/risa-labs-inc/boss-plugin-editor-tab)** | Code editor with syntax highlighting (50+ languages), code folding, and run-gutter icons |
| **[Fluck Browser](https://github.com/risa-labs-inc/boss-plugin-fluck-browser)** | Embedded web browser — zoom, security/loading indicators, downloads, and Secret Manager autofill |
| **Jupyter Notebook** | Cursor-style `.ipynb` editor running against a local Python kernel over Jupyter's ZeroMQ protocol |

### Dev tools
| Plugin | What it does |
|--------|--------------|
| **[Codebase](https://github.com/risa-labs-inc/boss-plugin-codebase)** | Browse and explore project files (`codebase_*` MCP tools) |
| **[Console](https://github.com/risa-labs-inc/boss-plugin-console)** | Captured stdout/stderr logs, filterable per plugin (`console_*` MCP tools) |
| **[Git Status](https://github.com/risa-labs-inc/boss-plugin-git-status) / [Git Log](https://github.com/risa-labs-inc/boss-plugin-git-log)** | Working-tree status & staged changes; commit history with a graph |
| **[Run Configurations](https://github.com/risa-labs-inc/boss-plugin-run-configurations)** | Auto-detect and run project run-configs (`run_config_*` MCP tools) |
| **[Performance](https://github.com/risa-labs-inc/boss-plugin-performance)** | Live JVM metrics — CPU, memory, resource counts |

### AI & automation
| Plugin | What it does |
|--------|--------------|
| **[Tool Creator](https://github.com/risa-labs-inc/boss-plugin-tool-creator)** | Scaffold a new plugin and hand it to Claude Code / Codex / Gemini / OpenCode to build |
| **[Tool Evolver](https://github.com/risa-labs-inc/boss-plugin-tool-evolver)** | Probe a plugin's memory/leaks/logs, then AI-evolve it with hot-reload + a PR (`evolver_*`) |
| **[LLM RPA](https://github.com/risa-labs-inc/boss-plugin-llmrpa)** | AI-powered robotic process automation (`llmrpa_*`) |
| **[RPA Engine](https://github.com/risa-labs-inc/boss-plugin-rpaengine) / [Recorder](https://github.com/risa-labs-inc/boss-plugin-rparecorder)** | Record browser interactions and replay them as automation (`rpa_*`) |
| **[ChatGPT](https://github.com/risa-labs-inc/boss-plugin-fluck-chatgpt)** | Embedded ChatGPT integration |

### Security & secrets
| Plugin | What it does |
|--------|--------------|
| **[Secret Manager](https://github.com/risa-labs-inc/boss-plugin-secret-manager)** | Encrypted credential vault — website/username/password, notes, tags, 2FA, expiry. Row-level-scoped to you, browser auto-fill, and permission-gated `secret_*` MCP tools |
| **[My Secrets](https://github.com/risa-labs-inc/boss-plugin-user-secret-list)** | Read-only view of your own and shared credentials |

### Productivity & admin
| Plugin | What it does |
|--------|--------------|
| **[Bookmarks](https://github.com/risa-labs-inc/boss-plugin-bookmarks)** | Bookmark management with global-search integration (`bookmark_*`) |
| **[Downloads](https://github.com/risa-labs-inc/boss-plugin-downloads)** | Active and completed downloads |
| **[Top of Mind](https://github.com/risa-labs-inc/boss-plugin-topofmind)** | Quick access to frequently used items |
| **[Admin Role Management](https://github.com/risa-labs-inc/boss-plugin-admin-role-management) / [Role Creation](https://github.com/risa-labs-inc/boss-plugin-role-creation)** | Manage roles and permissions; build custom roles (admin) |

*…and more in [boss-plugins](https://github.com/risa-labs-inc/boss-plugins), including analytics, atlas (chat about the current page), and hardware integrations.*

---

## BossTerm — a terminal you can share to any device

The terminal is powered by [**BossTerm**](https://github.com/kshivang/BossTerm) — a fast, embeddable terminal emulator (also published to Maven Central as `com.risaboss:bossterm-compose`). Its headline feature: **session sharing**.

### Share a live session — to your phone or a teammate
Your machine *is* the server: BossTerm runs a small embedded web server and streams the session to an `xterm.js` viewer over a WebSocket — **no cloud relay, no account**.

- **Scan a QR code with your phone** → the session opens in any mobile browser (nothing to install), touch-tuned with an on-screen key bar, drag-to-scroll, and pinch-zoom.
- **Two links, two QR codes:** a **View** link (read-only) and a **Control** link (full typing access). A view-only viewer can request control mid-session; you approve it.
- **Share a tab, a window, or all windows** — splits are preserved (they collapse into swipeable sub-tabs on a phone).
- **Multiple people at once:** send someone the link, or have another BossTerm *"add remote"* to mirror your tabs as first-class remote tabs — with control requests relayed hop-by-hop, each host approving in turn.
- **End-to-end encrypted:** a per-share key rides in the URL *fragment* (never sent to any server), and each connection derives a fresh **AES-256-GCM** key via HKDF-SHA256; both ends show a short verification code. Public tunnels can't read your session.
- **Reach it anywhere:** LAN by default, or a public link via **Cloudflare** (the default — BossTerm fetches `cloudflared` for you, no account) or **Tailscale**.

### More BossTerm features
- **Drive it with AI** — an in-process MCP server exposes the terminal to agents; a phone or second machine can even point an AI client at the host's terminals.
- **Inline images** in the terminal (iTerm2 OSC 1337), true color, full Unicode/emoji, Nerd Fonts, OSC 52 clipboard, and shell integration.
- **Split panes & multiple windows**, per-pane titles, regex search, command-complete notifications.
- **Optional session daemon** (tmux-style) with a tray icon, so sessions, shares, and the MCP server survive closing the GUI.

---

## Case study: the DNA Origami Inventor

BOSS isn't only for shipping software. **🧬 DNA Origami Inventor** is a BOSS plugin for computational nanotechnology: a researcher describes a target nanostructure in a **chat-first UI**, and an agent designs the **DNA origami** and runs **oxDNA** molecular-dynamics simulations to validate it — orchestrating BOSS's terminal, editor, browser, and tools end to end.

It's a concrete example of what BOSS is built for beyond coding: **enterprises, science, and research** — domains where an agent needs real tools, real compute, and governed access, not just a chat window.

---

## Design System

BOSS and BossTerm share one visual language — **"Operator's Console"**: an amber **signal** (`#F2A93B`) for what's live on a calm **ink** floor (`#0E1217`), cyan **data** accents, and a MesloLGS mono voice. The whole app re-skins live across three themes — **Operator** (dark), **Daylight** (light), and **Clean** (neutral) — and every dynamic plugin follows along.

- 📖 **[Design System spec](docs/DESIGN_SYSTEM.md)** — tokens, themes, and where they ship in code
- 🎨 **[Visual styleguide](docs/design-system.html)** — a self-contained HTML reference (open it in a browser)

---

## Development

This repository contains the source code for BOSS. For building from source and contributing, follow the instructions below.

### Prerequisites
- **JDK 17+** (recommended: Azul Zulu or Oracle JDK)
- **Gradle 8.x** (wrapper included)

### Build Commands

```bash
./gradlew run                    # Run desktop application
./gradlew showVersion            # Display current version
./gradlew test                   # Run tests
./gradlew build                  # Build application
./gradlew packageDmg             # Build macOS DMG
./gradlew packageMsi             # Build Windows MSI
./gradlew packageDistributionForCurrentOS  # Build for current platform
```

### Project Structure

```
/composeApp        # Main Compose Multiplatform UI application
  /commonMain      # Cross-platform shared code
  /desktopMain     # Desktop-specific code (JVM)
/plugin-platform   # Host-side plugin platform: loader, repository, SDK modules
/modules           # Microkernel / out-of-process architecture (boss-ipc, boss-ui-sdk, …)
/server            # Minimal Ktor server component
/shared            # Shared business logic
/supabase          # Database migrations and Edge Functions
/docs              # Documentation
```

> The dynamic plugins themselves (terminal, editor, browser, Toolbox, …) live in the separate [boss-plugins](https://github.com/risa-labs-inc/boss-plugins) repo. BossEditor and BossTerm are standalone libraries bundled inside the `editor-tab` and `terminal-tab` plugins respectively.

### Configuration

Create `local.properties` in the project root:

```properties
# JxBrowser (required to run the embedded browser)
jxbrowser.license.key=<your-license-key>

# Supabase (required for auth / backend)
SUPABASE_URL=https://api.risaboss.com
SUPABASE_ANON_KEY=<anon-key>
SUPABASE_FUNCTION_URL=https://api.risaboss.com/functions/v1

# GitHub (optional — 60 req/hr without)
GITHUB_TOKEN=ghp_your_token_here
```

**Resolution order:** environment variables → system properties → `local.properties` → build-time embedded config. There are no credential fallbacks in source; official builds embed these at build time from CI secrets, and a fork without keys still builds (browser/auth features degrade with a clear log).

### Key Technologies
- **Kotlin Multiplatform** + **Compose Multiplatform**
- **Fluck** — BOSS's built-in browser (JxBrowser 9.x engine)
- **Decompose** for navigation
- **Supabase** + Edge Functions for backend and RBAC
- **BossTerm** for terminal integration (also hosts the `boss` MCP server)
- **MCP (Model Context Protocol)** for exposing plugin tools to in-terminal AI agents
- **kotlin-compiler-embeddable** for PSI code analysis

---

## Version Management

```bash
./gradlew showVersion       # Display current version
./gradlew incrementVersion  # Increment patch (9.2.0 → 9.2.1)
./gradlew incrementMinor    # Increment minor (9.2.0 → 9.3.0)
./gradlew incrementMajor    # Increment major (9.2.0 → 10.0.0)
```

All version info is stored in [`version.properties`](./version.properties).

---

## CI/CD

Releases build and sign for all platforms (macOS, Windows, Linux × x64/ARM64), notarize on macOS, and publish to the BOSS Plugin Store and Maven Central. Release-capable workflows are gated behind an owner-approved GitHub environment.

| Workflow | Trigger | Description |
|----------|---------|-------------|
| **Release Build** | Manual dispatch / `v*.*.*` tags | Builds, signs, notarizes, and publishes releases for all platforms |
| **Build** | Push to `main`, PRs | Cross-platform builds and tests |
| **Claude Code Review** | Pull requests | Automated review (org members) |

**Cutting a release:** *Actions → Release Build → Run workflow* (pick a version bump), then approve the `release` environment prompt.

---

## CLI

BOSS includes a command-line interface for quick access:

```bash
boss url https://github.com    # Open a URL in a browser tab
boss folder ~/Documents        # Open a folder
boss file README.md            # Open a file in the editor
boss terminal                  # Open a terminal
boss plugin bookmarks          # Open a plugin panel
boss --help                    # Show help
```

**Installation:** Toolbox / Tools → Install BOSS CLI (or automatically via Homebrew).

---

## Documentation

- [Core Subsystems](docs/SUBSYSTEMS.md) — Auth, UI, keyboard shortcuts, threading
- [Design System](docs/DESIGN_SYSTEM.md) — "Operator's Console" tokens, themes, and the live styleguide
- [BossEditor Module](docs/BOSSEDITOR.md) — LSP, PSI, editor features
- [Application Features](docs/FEATURES.md) — Performance monitoring, dashboard, downloads
- [Keyboard Shortcuts](docs/KEYBOARD_SHORTCUTS.md) — Detailed shortcuts reference
- [RBAC Guide](docs/RBAC_GUIDE.md) — Role-based access control
- [Plugin System](plugin-platform/README.md) — Host-side plugin platform / SDK
- [Plugin Development & MCP](https://github.com/risa-labs-inc/boss-plugins/blob/main/PLUGIN_DEVELOPMENT.md) — Writing plugins, the manifest, RBAC, and exposing `mcp__boss__*` tools

---

## Open source & ecosystem

BOSS is developed in the open, end to end — the host app, the plugin platform, the terminal, and every tool is its own public repository.

**Core & libraries**

- [**BossConsole**](https://github.com/risa-labs-inc/BossConsole) — the host app (this repo)
- [**boss-plugins**](https://github.com/risa-labs-inc/boss-plugins) — plugin umbrella + the [Plugin Development & MCP guide](https://github.com/risa-labs-inc/boss-plugins/blob/main/PLUGIN_DEVELOPMENT.md)
- [**boss-plugin-api**](https://github.com/risa-labs-inc/boss-plugin-api) — the plugin API contract
- [**boss-microkernel-runtime**](https://github.com/risa-labs-inc/boss-microkernel-runtime) — out-of-process plugin runtime
- [**BossTerm**](https://github.com/kshivang/BossTerm) — the terminal library (`com.risaboss:bossterm-compose`)

**Plugins** — each is its own repo:

- **Tabs** — [terminal-tab](https://github.com/risa-labs-inc/boss-plugin-terminal-tab) · [editor-tab](https://github.com/risa-labs-inc/boss-plugin-editor-tab) · [fluck-browser](https://github.com/risa-labs-inc/boss-plugin-fluck-browser)
- **Dev tools** — [codebase](https://github.com/risa-labs-inc/boss-plugin-codebase) · [console](https://github.com/risa-labs-inc/boss-plugin-console) · [git-status](https://github.com/risa-labs-inc/boss-plugin-git-status) · [git-log](https://github.com/risa-labs-inc/boss-plugin-git-log) · [run-configurations](https://github.com/risa-labs-inc/boss-plugin-run-configurations) · [performance](https://github.com/risa-labs-inc/boss-plugin-performance)
- **AI & automation** — [tool-creator](https://github.com/risa-labs-inc/boss-plugin-tool-creator) · [tool-evolver](https://github.com/risa-labs-inc/boss-plugin-tool-evolver) · [llmrpa](https://github.com/risa-labs-inc/boss-plugin-llmrpa) · [rpaengine](https://github.com/risa-labs-inc/boss-plugin-rpaengine) · [rparecorder](https://github.com/risa-labs-inc/boss-plugin-rparecorder)
- **Security** — [secret-manager](https://github.com/risa-labs-inc/boss-plugin-secret-manager) · [user-secret-list](https://github.com/risa-labs-inc/boss-plugin-user-secret-list)
- **Productivity** — [bookmarks](https://github.com/risa-labs-inc/boss-plugin-bookmarks) · [downloads](https://github.com/risa-labs-inc/boss-plugin-downloads) · [topofmind](https://github.com/risa-labs-inc/boss-plugin-topofmind)
- **Admin** — [admin-role-management](https://github.com/risa-labs-inc/boss-plugin-admin-role-management) · [role-creation](https://github.com/risa-labs-inc/boss-plugin-role-creation)

**Releases** — [**BossConsole-Releases**](https://github.com/risa-labs-inc/BossConsole-Releases) hosts the pre-built installers for every platform.

---

## Support

- **Issues**: [GitHub Issues](https://github.com/risa-labs-inc/BossConsole/issues)
- **Releases**: [BossConsole-Releases](https://github.com/risa-labs-inc/BossConsole-Releases)
- **Enterprise**: [enterprise@risalabs.ai](mailto:enterprise@risalabs.ai)
- **Website**: [risalabs.ai](https://www.risalabs.ai)

---

© 2025 Risa Labs Inc. Licensed under the [Apache License 2.0](LICENSE).
