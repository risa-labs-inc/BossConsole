[![Watch the BOSS Console launch film](https://raw.githubusercontent.com/wiki/risa-labs-inc/BossConsole/images/boss-launch-poster.jpg)](https://bossconsole.ai/media/boss-launch.mp4)

<div align="center">

# BOSS Console

### Bring your agent. Choose its tools.

A desktop workspace where you and your AI agents work with a real browser, terminal, editor, and an extensible toolbox.

**[▶ Watch the 64-second film](https://bossconsole.ai/media/boss-launch.mp4)** · **[Download BOSS](#downloads)** · **[Explore the wiki](https://github.com/risa-labs-inc/BossConsole/wiki)** · **[Open live sessions](https://cli.risaboss.com/)**

[![BOSS release](https://img.shields.io/github/v/release/risa-labs-inc/BossConsole-Releases.svg?label=release&color=84cc16)](https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest)
[![Platforms](https://img.shields.io/badge/macOS%20%7C%20Windows%20%7C%20Linux-18181b?logo=linux&logoColor=white)](#downloads)
[![License](https://img.shields.io/badge/Apache--2.0-18181b?logo=opensourceinitiative&logoColor=white)](LICENSE)

[Product website](https://bossconsole.ai/) · [Visual walkthrough](https://github.com/risa-labs-inc/BossConsole/wiki/Product-Walkthrough) · [Contribute](CONTRIBUTING.md)

</div>

---

## One workspace for the whole task

Keep the page you are researching beside the agent working on it. Run commands, inspect files, review changes, and arrange everything into split panes and saved **Spaces**.

BOSS runs on the JVM with Kotlin and Compose Multiplatform. Its browser, terminal, editor, and other tools come from dynamic plugins, so the workspace can grow with the work.

[![BOSS on macOS with an Arcade pane beside Fluck Agent](https://raw.githubusercontent.com/wiki/risa-labs-inc/BossConsole/images/arcade-fluck.png)](https://github.com/risa-labs-inc/BossConsole/wiki/Spaces-and-Everyday-Use)

<sub>A real macOS workspace: a 2048 board beside Fluck Agent looking up tools. This capture shows work in progress.</sub>

| Browse | Build | Organize |
| :--- | :--- | :--- |
| **Fluck Browser** brings real webpages into the workspace, with agent tools for navigation and page interaction. | **BossTerm + BossEditor** put commands and code beside the task. Add Git, run configurations, and logs from Toolbox. | **Spaces + split panes** keep projects and activities together. Save layouts and move between tasks. |

**[Explore Spaces and everyday use →](https://github.com/risa-labs-inc/BossConsole/wiki/Spaces-and-Everyday-Use)**

## Run any AI coding agent

**Claude Code · Codex · Gemini CLI · OpenCode**

Bring the CLI you already use. Open a BOSS terminal, connect it through **Toolbox → MCP** or the BossTerm AI menu, and choose which BOSS tools it can call. The local `boss` MCP server discovers tools from active plugins, including browser, terminal, editor, Git, and automation tools.

An agent can inspect the running workspace and act in it: read a terminal's output, open a file, navigate a browser tab, or run a command. The available tools depend on your installed plugins and access settings.

### From page context to visible results

[![A webpage attached as context in Fluck Agent beside the browser](https://raw.githubusercontent.com/wiki/risa-labs-inc/BossConsole/images/fluck-context.png)](https://github.com/risa-labs-inc/BossConsole/wiki/Product-Walkthrough)

**1. Give the agent context.** In this Fluck Agent example, the current BOSS webpage is attached to the conversation while staying visible in the neighboring pane.

<table>
<tr>
<td width="50%" valign="top">
<a href="https://github.com/risa-labs-inc/BossConsole/wiki/Product-Walkthrough"><img src="https://raw.githubusercontent.com/wiki/risa-labs-inc/BossConsole/images/fluck-approval.png" alt="Fluck Agent asks for approval to open example.com" width="640" /></a>
<p><strong>2. Review the requested action.</strong><br />Fluck Agent asks before opening the destination. This is that agent's approval UI; other agents and tool policies can behave differently.</p>
</td>
<td width="50%" valign="top">
<a href="https://github.com/risa-labs-inc/BossConsole/wiki/Product-Walkthrough"><img src="https://raw.githubusercontent.com/wiki/risa-labs-inc/BossConsole/images/fluck-result.png" alt="example.com opened in a real browser tab beside the source page" width="640" /></a>
<p><strong>3. Inspect the result.</strong><br />The destination opens as a real browser tab. You can see and interact with the same workspace the agent is using.</p>
</td>
</tr>
</table>

<sub>These three product captures show BOSS 9.5.19 on Linux. The current release may look different.</sub>

**[Connect your agent →](https://github.com/risa-labs-inc/BossConsole/wiki/Agents-and-MCP)** · **[See the complete walkthrough →](https://github.com/risa-labs-inc/BossConsole/wiki/Product-Walkthrough)**

## Build your own toolbox

Install the tools you need from the built-in **Toolbox**. Browse plugins, manage updates, enable or disable tools, and customize the workspace around your project.

[![Toolbox setup with browser, terminal, editor, and agent plugins selected](https://raw.githubusercontent.com/wiki/risa-labs-inc/BossConsole/images/plugin-setup.png)](https://github.com/risa-labs-inc/BossConsole/wiki/Plugins)

<sub>Development setup in BOSS 9.5.32 on Linux: review the selected tools before installing them.</sub>

Start with a browser, terminal, and editor. Add Docker or Kubernetes for infrastructure, notebooks for research, automation for repeatable browser work, or your own plugin.

**Tool Creator** scaffolds a plugin project. **Tool Evolver** helps inspect and improve an existing tool. Many plugins hot-reload while the host stays open; infrastructure updates can require a restart.

**[Discover and manage plugins →](https://github.com/risa-labs-inc/BossConsole/wiki/Plugins)** · **[Build a plugin →](docs/PLUGIN_LAUNCHPAD.md)** · **[Browse the ecosystem →](https://github.com/risa-labs-inc/boss-plugins)**

## Your workspace, on another device

**[Open cli.risaboss.com →](https://cli.risaboss.com/)**

Sign in with the same BOSS account to find your shared sessions. The portal separates terminals from application windows, so you can open the kind of session you need.

| BossTerm live sessions | BossConsole live sessions |
| :--- | :--- |
| Open shared terminals from standalone BossTerm or BossConsole. View output or control the terminal according to the share's access settings. | View and control a shared BOSS window from a browser or another BossConsole. One controller holds the input lease at a time. |
| BossTerm also offers **View** and **Control** links with QR codes for sharing to a phone or another device. | Full-window hosting is currently a **macOS 14+ preview** and needs Screen Recording permission. Desktop BOSS itself also supports Windows and Linux. |

Full-window sharing publishes one encrypted video stream per window through Cloudflare Realtime SFU for multiple viewers, with encrypted remote-control messages. Same-account access and automatic control are enabled by default for fresh preferences; saved opt-outs are preserved. The account backend remains trusted for admission and key delivery.

**[Sharing setup, access settings, and protocol diagram →](https://github.com/risa-labs-inc/BossConsole/wiki/Live-Sessions-and-Window-Sharing)**

## You decide what your agents can touch

Choose the tool access that fits the task through **Toolbox → MCP**.

- **Per-tool switches** remove disabled tools from the agent's available BOSS toolset and block calls to them.
- **Roles and permissions** govern tools that declare permission requirements. Admin users bypass role checks, but still obey the per-tool switches.
- **Secret Manager** supports credential references and browser autofill so supported calls can use a credential without placing its value in the agent transcript. Explicit secret-read tools have different behavior.
- **Signed store plugins** bind the plugin identity, version, and JAR content. A valid signature establishes provenance, not that a plugin is safe.

These controls apply to calls through BOSS. They do not sandbox an external agent, and plugins run in the host process. Disabling a tool does not cancel work already authorized. An authorized page or tool receiving a secret can still expose it.

**[Security and privacy →](https://github.com/risa-labs-inc/BossConsole/wiki/Security-and-Privacy)** · **[Secret-reference guarantees →](docs/MCP_SECRET_REFERENCES.md)** · **[Infrastructure plugin audit →](docs/INFRASTRUCTURE_GUARDRAILS.md)**

---

## Downloads

Install BOSS, sign in, then use Toolbox to choose your initial tools.

| Platform | Architecture | Installer |
| :--- | :--- | :--- |
| **macOS** | Apple Silicon + Intel | [Homebrew](https://formulae.brew.sh/cask/boss) · [DMG](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=dmg) |
| **Windows** | x64 | [MSI](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=msi) |
| **Windows** | ARM64 | [MSI](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=msi&arch=arm64) |
| **Linux** | AMD64 | [DEB](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=deb&arch=amd64) · [RPM](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=rpm&arch=amd64) · [JAR](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=jar&arch=amd64) |
| **Linux** | ARM64 | [DEB](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=deb&arch=arm64) · [RPM](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=rpm&arch=arm64) · [JAR](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=jar&arch=arm64) |

On macOS:

```bash
brew install --cask boss
```

For installation scripts, platform notes, and your first workspace, follow the **[Getting started guide](https://github.com/risa-labs-inc/BossConsole/wiki/Getting-Started)**. Download links resolve to the latest stable release; use **[all releases](https://github.com/risa-labs-inc/BossConsole-Releases/releases)** to choose a specific version.

## Documentation

The **[BossConsole wiki](https://github.com/risa-labs-inc/BossConsole/wiki)** is the product guide. Start with a workflow, then follow its links into the technical references.

| What you want to do | Read |
| :--- | :--- |
| Install BOSS and set up your first tools | [Getting started](https://github.com/risa-labs-inc/BossConsole/wiki/Getting-Started) |
| See the app in action | [Product walkthrough](https://github.com/risa-labs-inc/BossConsole/wiki/Product-Walkthrough) |
| Arrange tabs, splits, and projects | [Spaces and everyday use](https://github.com/risa-labs-inc/BossConsole/wiki/Spaces-and-Everyday-Use) |
| Connect an agent and manage tools | [Agents and MCP](https://github.com/risa-labs-inc/BossConsole/wiki/Agents-and-MCP) |
| Install or build a plugin | [Plugins](https://github.com/risa-labs-inc/BossConsole/wiki/Plugins) |
| Open your terminal or window remotely | [Live sessions and window sharing](https://github.com/risa-labs-inc/BossConsole/wiki/Live-Sessions-and-Window-Sharing) |
| Use BOSS from a shell or script | [CLI guide](https://github.com/risa-labs-inc/BossConsole/wiki/CLI) |
| Understand access and credentials | [Security and privacy](https://github.com/risa-labs-inc/BossConsole/wiki/Security-and-Privacy) |
| Diagnose a problem | [Troubleshooting](https://github.com/risa-labs-inc/BossConsole/wiki/Troubleshooting) |
| Contribute to the host or plugin platform | [Development and contributing](https://github.com/risa-labs-inc/BossConsole/wiki/Development-and-Contributing) |

<details>
<summary><strong>Technical references, architecture, and benchmarks</strong></summary>

- [CLI reference](docs/CLI.md) and [Plugin Launchpad](docs/PLUGIN_LAUNCHPAD.md)
- [Core subsystems](docs/SUBSYSTEMS.md) and [host plugin platform](plugin-platform/README.md)
- [Design system](docs/DESIGN_SYSTEM.md) and [visual styleguide](docs/design-system.html)
- [Editor architecture](docs/BOSSEDITOR.md), [application features](docs/FEATURES.md), and [keyboard shortcuts](docs/KEYBOARD_SHORTCUTS.md)
- [RBAC guide](docs/RBAC_GUIDE.md), [secret references](docs/MCP_SECRET_REFERENCES.md), and [dated infrastructure guardrails audit](docs/INFRASTRUCTURE_GUARDRAILS.md)
- [Browser benchmark report](benchmark.md) and [reproducible harness and evidence](benchmarks/speedometer/)

</details>

## Development

Use **JDK 17** and the included Gradle wrapper. Begin with [CONTRIBUTING.md](CONTRIBUTING.md) for setup, validation, and the contribution workflow. Pull requests target **`dev`**.

```bash
git clone https://github.com/risa-labs-inc/BossConsole.git
cd BossConsole
git switch dev
./gradlew showVersion
```

Browser and backend features need local configuration. Follow the **[development guide](https://github.com/risa-labs-inc/BossConsole/wiki/Development-and-Contributing)** for `local.properties`, build commands, and isolated testing. Repository-specific instructions live in [AGENTS.md](AGENTS.md).

Install the BOSS CLI through **Toolbox → Tools → Install BOSS CLI** to inspect and operate the running workspace:

```bash
boss status --json
boss doctor
boss mcp list --filter browser
boss file README.md
```

**[Complete CLI reference →](docs/CLI.md)** · **[Plugin authoring and MCP →](https://github.com/risa-labs-inc/boss-plugins/blob/main/PLUGIN_DEVELOPMENT.md)**

## Open source & ecosystem

BOSS is licensed under **Apache-2.0**. The host and tools live in separate repositories; plugins release independently.

| Project | Role |
| :--- | :--- |
| **[BossConsole](https://github.com/risa-labs-inc/BossConsole)** | Desktop host, workspace UI, authentication, and sharing |
| **[boss-plugins](https://github.com/risa-labs-inc/boss-plugins)** | Plugin catalog and submodule workspace |
| **[boss-plugin-api](https://github.com/risa-labs-inc/boss-plugin-api)** | Plugin contracts provided by the host |
| **[BossTerm](https://github.com/kshivang/BossTerm)** | Terminal library, bundled by the terminal plugin |
| **[BossEditor](https://github.com/risa-labs-inc/BossEditor)** | Editor library, bundled by the editor plugin |
| **[boss-microkernel-runtime](https://github.com/risa-labs-inc/boss-microkernel-runtime)** | Out-of-process plugin runtime |
| **[BossConsole-Releases](https://github.com/risa-labs-inc/BossConsole-Releases)** | Installers and release history |

**Core plugins:** [Fluck Browser](https://github.com/risa-labs-inc/boss-plugin-fluck-browser) · [Terminal](https://github.com/risa-labs-inc/boss-plugin-terminal-tab) · [Editor](https://github.com/risa-labs-inc/boss-plugin-editor-tab)

## Support

[Report an issue](https://github.com/risa-labs-inc/BossConsole/issues) · [Troubleshoot](https://github.com/risa-labs-inc/BossConsole/wiki/Troubleshooting) · [Enterprise inquiries](mailto:enterprise@risalabs.ai)

---

<div align="center">

**Your agent. Real tools. You in control.**

[**Download BOSS**](#downloads) · [**Watch the film**](https://bossconsole.ai/media/boss-launch.mp4) · [**Read the wiki**](https://github.com/risa-labs-inc/BossConsole/wiki) · [**Open your sessions**](https://cli.risaboss.com/)

[bossconsole.ai](https://bossconsole.ai/) · [risaboss.com](https://risaboss.com/) · [Risa Labs](https://www.risalabs.ai)

<sub>Video from bossconsole.ai. Original product captures from risaboss.com, preserved in the wiki with <a href="https://raw.githubusercontent.com/wiki/risa-labs-inc/BossConsole/images/sources.json">source attribution</a>. Licensed under <a href="LICENSE">Apache-2.0</a>.</sub>

</div>
