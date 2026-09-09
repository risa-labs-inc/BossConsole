# Core Subsystems

This document covers the core subsystems of BOSS Console.

## Authentication System

**WebAuthn/Passkey Implementation**:
- Local biometric: Touch ID (macOS), Windows Hello (Windows)
- Cross-device: QR code generation for mobile/browser authentication
- Session management via Supabase Admin API

**Key Files**:
- `AuthService.kt` - Core authentication orchestration
- `SessionManager.kt` - Session establishment and persistence
- `DesktopPasskeyService.kt` - Desktop WebAuthn implementation
- `SupabasePasskeyService.kt` - Server-side passkey management
- `supabase/functions/passkey/` - Edge Functions for auth

**Platform-Specific**:
- macOS: Swift scripts for Touch ID, Keychain Services
- Windows: PowerShell scripts for Windows Hello, Credential Manager

**Server-side ceremony verification** (`supabase/functions/passkey/`):

The relying party - not the browser - is responsible for these checks, and the
edge function performs all of them on every ceremony:

| Check | Where |
|-------|-------|
| `clientDataJSON.challenge` equals the challenge issued for the ceremony | `utils/webauthn.ts` → `challengeMatches` |
| The challenge row is consumed atomically before a session is granted (single use, deterministic under concurrency) | `utils/database.ts` → `consumeChallengeRow` |
| `authData.rpIdHash` is `SHA-256` of an allowed RP ID (pinned to `user_passkeys.rp_id` once recorded) | `utils/webauthn.ts` → `matchRpIdHash` |
| User Present flag is set | both services |
| Signature counter advances, written compare-and-set (authenticators that always report 0 are tolerated) | `utils/webauthn.ts` → `evaluateSignCounter`, `utils/database.ts` → `recordPasskeyUse` |
| Signature verifies for the credential's algorithm - ES256 and RS256 | `utils/crypto.ts` → `verifySignature` |
| Origin is in `ALLOWED_ORIGINS` | route **and** service layer |
| Ceremony principal binding: challenge ↔ user, credential ↔ challenge, attested ↔ stored credential id | both services |
| Enrolment is bound to a verified caller, never to a body `userId` | `utils/authorization.ts` + `routes/register.ts` |

**Who a passkey gets enrolled for**

A credential enrolled on an account is a permanent way in, so registration is the
one flow here that requires an existing session:

- `POST /register/challenge` **requires** `Authorization: Bearer <user access token>`.
  The challenge row is bound to that verified identity. A `userId` in the body is
  optional and may only agree with the token - a mismatch is a 403.
- `POST /register/complete` derives the enrolling user from that challenge row.
  A bearer token is verified and cross-checked when present, but is not required:
  the cross-device page runs in a phone browser that holds no session of ours, and
  possession of the single-use, 256-bit challenge is what authorises that ceremony.
- `create_mobile_registration_session` (SQL) can mint the same kind of challenge
  without proving account ownership, so it is `service_role`-only as of migration
  `20260727000000`. It has no callers.
- `POST /manage/list|delete|update` require the same verified session and act on
  the caller's own credentials. They run against the service-role client, so a
  body `userId` would otherwise let anyone enumerate and disable another
  account's passkeys - de-enrolment, and a forced downgrade to email sign-in.
- The relying party for a registration comes from the server (`/register/challenge`
  returns `rpId`), and the `rpId` query parameter on the mobile pages is validated
  against the allow-list. Two sources of truth here produce a credential pinned to
  one RP ID while later assertions advertise another: permanently unusable, and
  invisible until the next login.

**Bootstrap - the first passkey.** There is no chicken-and-egg problem: passkey
enrolment is reached from Settings → Security in an already-authenticated app.
A new user signs in by email magic link / OTP (`EmailAuthService`), which
establishes a normal Supabase session, and enrols their first passkey from it.
Passkey *authentication* (`/auth/*`) stays unauthenticated by design - it is how a
session is obtained in the first place.

All transport payloads are decoded with `utils/base64.ts` → `decodeBase64Any`,
which accepts base64 and base64url with or without padding. Locking a decoder to
one alphabet produces failures that depend on the bytes of the individual
ceremony.

**Environment**:
- `PASSKEY_RP_ID` - **required in hosted deployments.** RP ID for ceremonies. The fallback derives it from `SUPABASE_URL`, which inside the edge runtime is the internal `kong` gateway and maps to `localhost` - not what the browser uses.
- `PASSKEY_RP_ID_ALIASES` - comma-separated additional RP IDs accepted during verification
- `PASSKEY_ALLOW_LOCALHOST` - set to `true` only for local development. Loopback RP IDs (`localhost`, `127.0.0.1`, `::1`) are rejected without it, since `localhost` is whatever host the client runs on rather than a BOSS-owned domain. A loopback `SUPABASE_URL` also counts as local.

The tests are gated by `.github/workflows/edge-functions.yml` (`deno check` + `deno test`, scoped to `supabase/functions`).

## UI Architecture

**Compose Multiplatform Structure**:
- **BossAppWithAuth** - Main authentication wrapper
- **BossApp** - Main application composable
- **LoginScreen** - Handles login with passkey integration
- **Component-based UI** using Decompose navigation
- **Dark theme** with Material Design components

**Top Bar Features** (`BossTopBar.kt`):
- Project Selector with recent history
- Workspace Management (save/load layouts)
- User Display (email)
- Sign Out, Settings

**Implemented Features**:
- Global Search - Search across the application from the top bar or double-shift shortcut
- Git Integration (#90) - Branch browser, pull/push, merge/rebase
- Run/Debug Controls (#347) - Runner terminal system with run/stop/re-run
- Performance Monitoring - Real-time CPU/memory metrics in bottom panel

## Keyboard Shortcuts System

**Overview**: Comprehensive, customizable shortcuts with context-aware bindings, preset keymaps, and conflict detection.

**Key Components**:
- `KeymapSettingsManager.kt` - Settings persistence (`~/.boss/keymap-settings.json`)
- `MenuBar` - Native OS menu with keyboard accelerators for GLOBAL shortcuts
- `KeyboardEventBus` - Priority-based event distribution
- `BossActionHandler` - Action execution

**Contexts**: GLOBAL, BROWSER, TERMINAL, EDITOR, WORKSPACE

**Presets**: BOSS Default, VS Code, IntelliJ IDEA, Emacs

**For detailed documentation**: See [KEYBOARD_SHORTCUTS.md](KEYBOARD_SHORTCUTS.md)

## Threading and Coroutines

**CRITICAL RULES**:
1. Never block the UI thread - No `Thread.sleep()`, blocking I/O, or long computations
2. Use appropriate dispatchers:
   - `Dispatchers.Main` for UI updates
   - `Dispatchers.IO` for file/network/database/browser cleanup
   - `Dispatchers.Default` for CPU-bound work
3. Use `delay()` not `Thread.sleep()`
4. Always dispose resources on background threads

**Common Pattern** (Browser/Resource Disposal):
```kotlin
CoroutineScope(Dispatchers.IO).launch {
    try {
        // Dispose resources
        delay(50)  // Allow queues to drain
        // Final cleanup
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}
```

**For detailed patterns and examples**: See [THREADING.md](THREADING.md)

## Version Management

**Centralized version management**:
- Source: `version.properties` file (auto-incremented by CI)
- Generation: `gradle/version.gradle` generates `VersionConstants.kt`
- CI Integration: GitHub Actions automatically increment versions on release
- All platforms use same version from single source
- Use `./gradlew showVersion` to see current version

## Default Applications

**Overview**: BOSS can be made the default handler for http/https links and for
the file types it opens: markdown, shell scripts, web pages and every language its
editor can highlight.

**Entry Points**:
- `Settings > Default Apps` - one row per category, with a Repair action when a
  BOSS component holds the type
- `Help > Default Apps...` - the same section, from the menu bar. This is the
  path `DefaultAppsOffer`'s "it does not ask twice" reasoning assumes exists: the
  first-run offer persists `promptShown` the moment it appears, so a force quit
  or a closed window counts as asked and the menu is how that user gets back
- the one-time first-run offer itself, which never appears twice

**Key Files**:
- `boss-file-types.json` (`composeApp/src/desktopMain/resources`) - the single
  source of truth: five categories, 83 extensions, the macOS UTIs each claims and
  the Linux MIME types each maps to
- `buildSrc/BossFileTypes.kt` - generates the `Info.plist` declarations from it
- `FileTypeCategories.kt` - the runtime reader
- `DefaultAppsManager.kt` - reads and sets defaults per category
- `MacOSFileTypeHandler.kt` / `WindowsFileTypeHandler.kt` / `LinuxFileTypeHandler.kt`
- `utils/mac/LaunchServices.kt` - the JNA binding to Launch Services
- `DefaultHandlerState.kt` - `Ours` / `OurEngine` / `Other`
- `DefaultAppsSettings.kt` - Settings > Default Apps
- `DefaultAppsOffer.desktop.kt` - the one-time first-run offer
- `DefaultBrowserManager.kt` + `DefaultBrowserSection.kt` - the browser-only
  facade behind Settings > Browser. Reads `DefaultHandlerState`, not a boolean,
  so it cannot tell a user who set BOSS as their browser that they did not

**Platform Behavior**:
- macOS: `LSSetDefaultRoleHandlerForContentType` / `LSSetDefaultHandlerForURLScheme`
  through JNA, against types the bundle declares in `CFBundleDocumentTypes` and
  `UTExportedTypeDeclarations`
- Windows: per-extension ProgIDs under `HKCU\Software\Classes` plus
  `Capabilities\FileAssociations`; the default itself can only be chosen by the
  user in `ms-settings:defaultapps`, which BOSS opens
- Linux: a `boss.desktop` entry declaring every MIME type, then `xdg-mime default`
  per type

**Open Handling Flow**: every door the OS can use is normalised to a `boss://`
link. See "Every OS open request becomes a `boss://` link" in `AGENTS.md` for the
four doors and the four bugs that section records.

1. OS passes a URL or a file to BOSS (AppleEvent on macOS, `argv` elsewhere)
2. `DeepLinkHandler` / `OsOpenArguments` turn it into `boss://url` or `boss://file`
3. `URLHandlerService` or `CLICommandHandler` validates and resolves a window
4. `URLEventBus` / `FileEventBus` reach the window, which opens the tab
5. If the plugin that renders that tab type is missing, `TabTypeAvailability`
   offers to install or enable it rather than dropping the tab

Anything arriving this way is tagged `DeepLinkOrigin.EXTERNAL`, since the OS
accepts a URL from any program; only links BOSS's own CLI parsed out of `argv`
are tagged `OPERATOR_CLI`. See the Deep Links section of `AGENTS.md`.

**The collision worth knowing about**: the branded Chromium engine in
`~/.boss/boss-chromium/BOSS.app` used to declare http, https and `public.html`
itself, under the id `ai.rever.boss.browser` and the name "BOSS". Any install
predating that fix is likely to have the engine as its default browser, which is
why the status is three-way and the button says Repair. See AGENTS.md.

## Runner Terminal System

**Overview**: Run configurations execute in terminal with run/stop/re-run controls (Issue #347).

**Key Components**:
- `RunnerTerminalService.kt` - Manages runner terminals with state tracking
- `RunnerSettingsManager.kt` - Persists runner settings (`~/.boss/runner-settings.json`)
- `RunnerTerminalEventBus.kt` - Events for opening/closing runner terminals
- `DesktopTerminalContent.kt` - Sidebar terminal with persistent state

**Terminal Targets** (configurable in Settings > Runner):
- **Sidebar Panel**: Opens in left sidebar terminal (like VS Code)
- **Main Panel**: Opens in main content area (like IntelliJ IDEA)

**Features**:
- **Run**: Execute selected configuration in terminal
- **Stop**: Send Ctrl+C (0x03) to interrupt running process (BossTerm 1.0.58+)
- **Re-run**: Stop current process, close terminal, create new one with same command

**Sidebar Terminal Integration**:
- Uses `TabbedTerminalStateRegistry` with `SIDEBAR_TERMINAL_ID` for persistent state
- `settingsOverride` with `alwaysShowTabBar = true` ensures tab bar visibility (BossTerm 1.0.59+)
- Commands sent via `sendInput()` API even before panel renders

**Key Files**:
- `DesktopRunnerTerminalService.kt` - Desktop implementation with Ctrl+C support
- `RunnerSettings.kt` (UI) - Settings UI in Settings > Runner section
- `BossTopRunBar.kt` - Run/Stop buttons in top bar

## BossTerm Library Integration

BOSS uses [BossTerm](https://github.com/kshivang/BossTerm) for terminal integration.

**IMPORTANT**: Do NOT modify the BossTerm repository directly. Instead:
1. Create a GitHub issue using `gh issue create --repo kshivang/BossTerm`
2. Or create a PR for the issue using `gh pr create --repo kshivang/BossTerm`

**BossTerm Features Used**:
- `TabbedTerminal` - Multi-tab terminal with splits for sidebar panel
- `EmbeddableTerminal` - Single terminal instance for embedded use
- `TabbedTerminalState` / `EmbeddableTerminalState` - State persistence across composition changes
- `OnboardingWizard` - First-launch welcome wizard for terminal setup
- `SettingsManager` - Terminal settings (stored in `~/.bossterm/settings.json`)

**AI Assistant Integration** (Issue #445):
- AI coding assistant context menu (Claude Code, GitHub Copilot, Cursor, etc.) is handled by BossTerm
- BOSS Console passes `onShowWelcomeWizard` callback to add "Welcome Wizard..." to context menu
- First-launch detection: checks `settings.onboardingCompleted` and auto-shows wizard
- Help menu also has "Welcome Wizard..." option for manual access

**Key Terminal Files**:
- `DesktopTerminalContent.kt` - Desktop terminal implementations with Welcome Wizard integration
- `Terminal.kt` - Common terminal panel component (expect/actual pattern)
