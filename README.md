# BOSS - Business OS + Simulator

[![BOSS Version](https://img.shields.io/github/v/release/risa-labs-inc/BossConsole-Releases.svg?label=BOSS&color=brightgreen)](https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest)
[![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Windows%20%7C%20Linux-blue.svg)](https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest)
[![License](https://img.shields.io/badge/license-Proprietary-red.svg)](https://github.com/risa-labs-inc/BossConsole-Releases/blob/main/LICENSE)

BOSS (Business OS + Simulator) is a sophisticated, AI-powered desktop workspace built with Kotlin Multiplatform and Compose Multiplatform. It combines **AI automation**, **configurable layouts**, and **intelligent workflow management** into a unified platform designed for complex business operations.

> **Looking to download BOSS?** Visit the [**BossConsole-Releases**](https://github.com/risa-labs-inc/BossConsole-Releases) repository for pre-built installers for all platforms.

---

## Downloads

| Platform | Architecture | Download |
|----------|--------------|----------|
| **macOS** | Universal (Apple Silicon + Intel) | [Homebrew](https://formulae.brew.sh/cask/boss) \| [DMG](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=dmg) |
| **Windows** | x64 | [MSI](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=msi) |
| **Windows** | ARM64 | [MSI](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=msi&arch=arm64) |
| **Linux** | AMD64 | [DEB](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=deb&arch=amd64) \| [RPM](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=rpm&arch=amd64) \| [JAR](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=jar&arch=amd64) |
| **Linux** | ARM64 | [DEB](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=deb&arch=arm64) \| [RPM](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=rpm&arch=arm64) \| [JAR](https://api.risaboss.com/functions/v1/latest-release?app=boss&download=jar&arch=arm64) |

Download links always fetch the newest release directly (via the [`latest-release`](supabase/functions/latest-release/app.ts) edge function; the saved file keeps its versioned name). Release metadata as JSON — version, assets, sha256 checksums — is at [`?app=boss`](https://api.risaboss.com/functions/v1/latest-release?app=boss) without the `download` parameter. To browse all versions, use [BossConsole-Releases](https://github.com/risa-labs-inc/BossConsole-Releases/releases).

### Quick Install

```bash
# macOS (Homebrew - Recommended)
brew install --cask boss

# macOS/Linux (Universal Script)
curl -fsSL https://raw.githubusercontent.com/risa-labs-inc/BossConsole-Releases/main/install.sh | bash

# Windows (PowerShell)
iwr -useb https://raw.githubusercontent.com/risa-labs-inc/BossConsole-Releases/main/install.ps1 | iex
```

For detailed installation instructions, system requirements, and troubleshooting, see the [BossConsole-Releases README](https://github.com/risa-labs-inc/BossConsole-Releases#readme).

---

## Key Features

- **Modular Workspace Architecture**: Configurable panels, multi-tab browser, code editor, terminal integration
- **AI & Automation**: LLM integration with robotic process automation, smart workflows, browser automation
- **Healthcare Focus**: Prior authorization, patient triage, EHR management, compliance workflows
- **Enterprise Ready**: Auto-updates, workspace configuration management, Git integration, cross-platform support

---

## Design System

BOSS and BossTerm share one visual language — **"Operator's Console"**: an amber **signal** (`#F2A93B`) for what's live/now on a calm **ink** floor (`#0E1217`), cyan **data** accents, and a MesloLGS mono voice. The whole app re-skins live across three themes — **Operator** (the signature dark identity), **Daylight** (light), and **Clean** (neutral charcoal) — and every dynamic plugin follows along.

- 📖 **[Design System spec](docs/DESIGN_SYSTEM.md)** — tokens, themes, and where they ship in code
- 🎨 **[Visual styleguide](docs/design-system.html)** — a self-contained HTML reference for colors / type / components (open it in a browser)

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
/composeApp     # Main Compose Multiplatform UI application
  /commonMain   # Cross-platform shared code
  /desktopMain  # Desktop-specific code (JVM)
/plugins        # Modular plugin system
  /plugin-api   # Core plugin interfaces
  /plugin-panel-*  # Panel plugins (git, console, performance, etc.)
  /plugin-tab-*    # Tab type plugins (browser, editor, terminal)
/bosseditor     # Standalone code editor with LSP and PSI support
/server         # Minimal Ktor server component
/shared         # Shared business logic
/supabase       # Database migrations and Edge Functions
/docs           # Documentation
```

### Configuration

Create `local.properties` in project root:

```properties
# JxBrowser (Required)
jxbrowser.license.key=<your-license-key>

# Supabase (Required)
SUPABASE_URL=https://api.risaboss.com
SUPABASE_ANON_KEY=<anon-key>
SUPABASE_FUNCTION_URL=https://api.risaboss.com/functions/v1

# GitHub (Optional - 60 req/hr without)
GITHUB_TOKEN=ghp_your_token_here
```

**Priority**: Environment variables > System properties > local.properties > Fallbacks

### Key Technologies

- **Kotlin Multiplatform** + **Compose Multiplatform**
- **JxBrowser 8.x** (BOSS-branded Chromium)
- **Decompose** for navigation
- **Supabase** + Edge Functions for backend
- **BossTerm** for terminal integration
- **kotlin-compiler-embeddable** for PSI code analysis

---

## Version Management

```bash
./gradlew showVersion       # Display current version
./gradlew incrementVersion  # Increment patch (8.8.0 → 8.8.1)
./gradlew incrementMinor    # Increment minor (8.8.0 → 8.9.0)
./gradlew incrementMajor    # Increment major (8.8.0 → 9.0.0)
```

All version info is stored in [`version.properties`](./version.properties).

---

## CI/CD

### Workflows

| Workflow | Trigger | Description |
|----------|---------|-------------|
| **Release** | Tags `v*.*.*` | Builds and publishes releases for all platforms |
| **Build** | Push to `main`, PRs | Cross-platform builds and tests |
| **Version Bump** | Manual | Automated version management |

### Creating a Release

```bash
# Option 1: Tag-based
./gradlew incrementMinor
git add version.properties
git commit -m "Release v8.9.0"
git tag v8.9.0
git push origin main --tags

# Option 2: GitHub Actions UI
# Actions → Release Build → Run workflow
```

---

## CLI

BOSS includes a command-line interface for quick access:

```bash
boss url https://github.com    # Open URL
boss folder ~/Documents        # Open folder
boss file README.md            # Open file
boss terminal                  # Open terminal
boss plugin bookmarks          # Access plugin
boss --help                    # Show help
```

**Installation**: Tools > Install BOSS CLI (or automatically via Homebrew)

---

## Documentation

- [Core Subsystems](docs/SUBSYSTEMS.md) - Auth, UI, keyboard shortcuts, threading
- [Design System](docs/DESIGN_SYSTEM.md) - "Operator's Console" tokens, themes, and the live styleguide
- [BossEditor Module](docs/BOSSEDITOR.md) - LSP, PSI, editor features
- [Application Features](docs/FEATURES.md) - Performance monitoring, dashboard, downloads
- [Keyboard Shortcuts](docs/KEYBOARD_SHORTCUTS.md) - Detailed shortcuts reference
- [Threading Best Practices](docs/THREADING.md) - Threading patterns and pitfalls
- [RBAC Guide](docs/RBAC_GUIDE.md) - Role-based access control
- [Plugin System](plugin-platform/README.md) - Modular plugin architecture

---

## Related Repositories

- [**boss-plugins**](https://github.com/risa-labs-inc/boss-plugins) - Master repository for all BOSS plugins, managed as git submodules
- [**BossConsole-Releases**](https://github.com/risa-labs-inc/BossConsole-Releases) - Pre-built installers for all platforms

---

## Support

- **Issues**: [GitHub Issues](https://github.com/risa-labs-inc/BossConsole/issues)
- **Releases**: [BossConsole-Releases](https://github.com/risa-labs-inc/BossConsole-Releases)
- **Enterprise**: [enterprise@risalabs.ai](mailto:enterprise@risalabs.ai)
- **Website**: [risalabs.ai](https://www.risalabs.ai)

---

**© 2025 Risa Labs Inc. All rights reserved.**
