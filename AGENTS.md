# AGENTS.md

This file provides guidance to coding agents working with this repository.

## Project Overview

BOSS (Business Operating System Service) is a desktop application built with Kotlin Multiplatform and Compose Multiplatform. It features WebAuthn/passkey authentication, integrated browser (JxBrowser), terminal integration (BossTerm), customizable keyboard shortcuts, and role-based access control.

**Target Platforms**: Desktop only (macOS, Windows, Linux).

## Essential Commands

```bash
./gradlew run                    # Run desktop application
./gradlew showVersion           # Display current version
./gradlew test                  # Run tests
./gradlew build                 # Build application
./gradlew packageDmg            # Build macOS DMG
./gradlew packageMsi            # Build Windows MSI
./gradlew packageDistributionForCurrentOS  # Linux packages
./gradlew incrementVersion      # Increment patch version
```

## Workflow Rules

**IMPORTANT**: Do NOT run `./gradlew run` in a blocking/foreground way just to test — the user runs and tests the app themselves. **Exception:** launching the app **in a dedicated bottom split pane is allowed** (backgrounded so it doesn't wedge the pane).

### Running commands in a visible terminal pane

When a terminal MCP server is available, prefer it over the plain `Bash` tool for commands worth showing — it runs in a visible BossTerm pane and still returns stdout/stderr/exit code. Two servers may be present depending on which app hosts the session; use whichever the session's `SessionStart` hook designates:

- **`mcp__boss__*`** — exposed by the `terminal-tab` plugin inside BossConsole (e.g. `mcp__boss__run_command`, `run_in_sidebar`).
- **`mcp__bossterm__*`** — exposed by the standalone BossTerm app.

For a bottom split use `panel: horizontal_split`. Reuse a pane across calls by passing back its `pane_id`. Keep plain `Bash` for trivial read-only commands where opening a visible pane is churn. (Do not mix the two servers in one session — they target different app instances.)

## Architecture

### Module Structure
- **`composeApp/`** - Main Compose Multiplatform UI application
- **`server/`** - Minimal Ktor server component
- **`modules/`** - Microkernel / out-of-process architecture Gradle modules
  (`boss-ipc`, `boss-service-*`, `boss-orchestrator`, `boss-ui-sdk`,
  `boss-mastery-*`, `boss-app-*`). They keep flat Gradle paths (`:boss-ipc`),
  only the directory lives under `modules/`; excluded on Windows-ARM64 (no
  protoc binary). `boss-ipc`/`boss-ui-sdk` publish as upstream jars consumed by
  the standalone `boss-microkernel-runtime` repo.
- **`plugin-platform/`** - Host-side plugin platform / SDK modules
  (`plugin-loader`, `plugin-repository`, `plugin-api-core`, …). This is the
  infrastructure that loads and runs plugins, **not** the plugins themselves —
  those live in the separate `boss_plugins` repo.
- **`supabase/`** - Database migrations and Edge Functions

### External Dependencies
- **BossEditor** (`com.risaboss:bosseditor-compose-desktop`) - Standalone code editor with LSP and PSI support. **Not a host dependency** — bundled privately inside the `editor-tab` plugin, like BossTerm inside `terminal-tab` (see [docs/BOSSEDITOR.md](docs/BOSSEDITOR.md))

### Key Technologies
- Kotlin Multiplatform + Compose Multiplatform
- JxBrowser (version pinned in `gradle/libs.versions.toml`, with BOSS-branded Chromium)
- Decompose for navigation
- Supabase + Edge Functions
- BossTerm for terminal integration (bundled in the `terminal-tab` plugin)

## Configuration

Create `local.properties`:
```properties
jxbrowser.license.key=<your-license-key>
SUPABASE_URL=https://api.risaboss.com
SUPABASE_ANON_KEY=<anon-key>
SUPABASE_FUNCTION_URL=https://api.risaboss.com/functions/v1
GITHUB_TOKEN=ghp_your_token_here  # Optional, 60 req/hr without
OPENAI_API_KEY=sk-your-key-here  # Optional, enables AI self-healing in RepairEngine
MACOS_DEVELOPER_ID=Developer ID Application: ...  # Optional, signs local packaging
```

**Priority**: Environment variables > System properties > local.properties > Embedded build config

There are NO credential fallbacks in source (public repo). Packaged builds get
the JxBrowser license and Supabase settings baked in by the
`generateEmbeddedConfig` Gradle task, which reads env vars (CI secrets) or
local.properties at build time and emits a git-ignored classpath resource.

### Supabase Deployment
```bash
supabase functions deploy <function-name> --project-ref pcnwqamqdnsadranufjv --no-verify-jwt
supabase link --project-ref pcnwqamqdnsadranufjv  # First time
```

## Code Quality

- Use Compose Multiplatform Resource API (not Android resources)
- Location: `composeApp/src/commonMain/composeResources/`
- Use `BossLogger` for logging (not `println()` or `printStackTrace()`)
- All Kotlin files must end with newline

## Logging

Use structured logging via `BossLogger` (SLF4J backend):

```kotlin
private val logger = BossLogger.forComponent("MyComponent")

logger.info(LogCategory.AUTH, "User signed in", mapOf("email" to LogSanitizer.maskEmail(email)))
logger.error(LogCategory.NETWORK, "Request failed", error = exception)
```

**Categories**: AUTH, PASSKEY, BROWSER, TERMINAL, NETWORK, UI, SYSTEM, EDITOR, FILE, WORKSPACE, GENERAL

**Security**: Always use `LogSanitizer` for sensitive data:
- `maskEmail()`, `maskToken()`, `maskCredentialId()`, `maskUserId()`, `maskUriParams()`

**Config**: Set `BOSS_LOG_LEVEL` env var or `boss.log.level` system property (TRACE/DEBUG/INFO/WARN/ERROR)

## Build and Deployment

**GitHub Actions**: `build.yml` (multi-platform tests), `release.yml` (signed builds)

**Secrets Required**: `JXBROWSER_LICENSE_KEY`, `SUPABASE_ANON_KEY`, code signing certs

## Development Notes

**Current Focus**: RBAC, LSP integration, performance monitoring, cross-device auth

**Known Issues**:
- Issue #33: Remove hardcoded credential fallbacks after testing
- Issue #34: Use JxBrowser for login instead of system browser

## Key Files

**Client**: `AuthService.kt`, `SessionManager.kt`, `DesktopPasskeyService.kt`, `SupabaseConfig.kt`

**Server**: `supabase/functions/passkey/services/auth.ts`, `utils/jwt.ts`, `utils/crypto.ts`

**Config**: `version.properties`, `build.gradle.kts`

## Deep Links

App registers `boss://` protocol for authentication callbacks from external browsers.

## Documentation

- [Core Subsystems](docs/SUBSYSTEMS.md) - Auth, UI, keyboard shortcuts, threading, default browser, runner, BossTerm
- [BossEditor](docs/BOSSEDITOR.md) - External editor dependency, LSP, PSI, editor features
- [Application Features](docs/FEATURES.md) - Performance monitoring, dashboard, downloads, Chromium branding
- [Keyboard Shortcuts](docs/KEYBOARD_SHORTCUTS.md) - Detailed shortcuts reference
- [Threading Best Practices](docs/THREADING.md) - Threading patterns and pitfalls
- [RBAC Guide](docs/RBAC_GUIDE.md) - Role-based access control
- [Role Creation](docs/ROLE_CREATION_GUIDE.md) - Creating and managing roles
- [Windows Deep Link](docs/WINDOWS_DEEP_LINK_SETUP.md) - Windows protocol handler setup
- [Release Rebuild](docs/RELEASE_REBUILD_GUIDE.md) - Re-running release builds

