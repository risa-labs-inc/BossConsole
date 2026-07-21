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

**Disabled Features** (commented out with tracking issues):
- Global Search (#92)
- Lanager Plugin (#93)

**Implemented Features**:
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

## Default Browser Support

**Overview**: BOSS can be set as the default system browser to handle http:// and https:// URLs.

**Key Files**:
- `DefaultBrowserManager.kt` (common/desktop) - Cross-platform interface
- `MacOSDefaultBrowserHandler.kt` - macOS implementation
- `WindowsDefaultBrowserHandler.kt` - Windows implementation
- `LinuxDefaultBrowserHandler.kt` - Linux implementation
- `URLHandlerService.kt` - Handles incoming http/https URLs
- `DefaultBrowserSection.kt` - Settings UI

**Platform Behavior**:
- macOS: Automatic registration via Info.plist, Swift scripts
- Windows: Registry keys, user must manually select in Settings
- Linux: .desktop file with xdg-settings/xdg-mime

**URL Handling Flow**:
1. OS passes URL to BOSS via protocol handler
2. `DeepLinkHandler` checks protocol
3. If http/https: forwards to `URLHandlerService`
4. If boss://: processes as authentication deep link
5. Creates new browser tab in active window

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
