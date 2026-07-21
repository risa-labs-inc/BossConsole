# Keyboard Shortcuts System

This document provides detailed information about BOSS's customizable keyboard shortcuts system.

## Overview

The application features a comprehensive keyboard shortcuts system (Issue #201) with context-aware bindings, preset keymaps, conflict detection, and full customization via UI or JSON editing.

## Core Features

- **Context-aware shortcuts** - Different key bindings for GLOBAL, BROWSER, TERMINAL, EDITOR, and WORKSPACE contexts
- **Priority-based event handling** - Component → Workspace → Global priority chain
- **Conflict detection** - Visual warnings when multiple shortcuts use the same key combination
- **Preset keymaps** - Pre-configured schemes: BOSS Default, VS Code, IntelliJ IDEA, Emacs
- **Import/Export** - Backup and share keymap configurations via JSON
- **UI Editor** - Visual interface for capturing and editing shortcuts
- **JSON Editing** - Direct file editing at `~/.boss/keymap-settings.json`
- **Focus mode support** - All shortcuts work in both normal and focus mode

## Architecture

### Event Flow

1. **MenuBar** (native OS level) - Handles GLOBAL context shortcuts via native menu accelerators
2. **KeyboardEventBus** - Central event distribution with priority-based handling:
   - **COMPONENT** (priority 0) - Terminal, browser, editor handle their own shortcuts first
   - **WORKSPACE** (priority 1) - Workspace-level shortcuts (panel navigation, workspace save)
   - **GLOBAL** (priority 2) - App-wide shortcuts (window management, settings, focus mode)
3. **BossActionHandler** - Executes the actual action for each shortcut

### Key Components

**Data Models** (`composeApp/src/commonMain/kotlin/ai/rever/boss/keymap/model/`):
- `ShortcutContext.kt` - Enum defining where shortcuts are active
- `KeyBinding.kt` - Individual shortcut with key, modifiers, context, category, description
- `KeymapSettings.kt` - Container for all shortcuts with preset tracking
- `KeymapActions.kt` - Registry of 20 action IDs across 8 categories

**Handler System** (`composeApp/src/commonMain/kotlin/ai/rever/boss/keymap/handler/`):
- `KeymapMatcher.kt` - Matches keyboard events to configured bindings
- `KeymapValidator.kt` - Detects conflicts and validates shortcuts
- `KeymapHandler.kt` - Context-aware event dispatcher

**Lifecycle System** (`composeApp/src/commonMain/kotlin/ai/rever/boss/keymap/lifecycle/`):
- `ShortcutLifecycleManager.kt` - Enables/disables shortcuts based on runtime conditions
- `conditions/` - Conditions like SplitNavigationCondition

**UI Components** (`composeApp/src/commonMain/kotlin/ai/rever/boss/components/settings/keymap/`):
- `EditableKeymapSettings.kt` - Main settings UI with search/filter
- `KeyCaptureDialog.kt` - Modal for capturing key presses
- `ConflictWarningBadge.kt` - Visual conflict indicators
- `PresetSelector.kt` - Preset switcher with customization badges
- `KeymapImportExport.kt` - JSON import/export dialogs

**Settings Manager** (`composeApp/src/commonMain/kotlin/ai/rever/boss/keymap/`):
- `KeymapSettingsManager.kt` - Expect/actual pattern for platform-specific persistence
- Desktop implementation saves to `~/.boss/keymap-settings.json`

## Available Actions (20 total)

### Window Management (2)
- `window.new` - Create new window
- `window.close` - Close current window

### Tab Management (2)
- `tab.new` - Open new tab dialog
- `tab.close` - Close current tab (or window if last tab)

### Browser Controls (4)
- `browser.reload` - Reload browser tab (BROWSER context only)
- `browser.zoom_reset` - Reset zoom to 100% (BROWSER context only)
- `browser.zoom_in` - Increase zoom (BROWSER context only)
- `browser.zoom_out` - Decrease zoom (BROWSER context only)

### Navigation (7)
- `panel.navigate_left` - Switch to left panel
- `panel.navigate_right` - Switch to right panel
- `panel.navigate_up` - Switch to previous panel
- `panel.navigate_down` - Switch to next panel
- `panel.split_vertical` - Split current tab vertically
- `panel.split_horizontal` - Split current tab horizontally
- `quick_switcher.open` - Open Top of Mind quick switcher

### Workspace (1)
- `workspace.save` - Save current workspace layout (WORKSPACE context)

### Tools (1)
- `codebase.open` - Open CodeBase panel

### View/UI (2)
- `view.focus_mode_toggle` - Toggle focus mode (hide/show UI bars)
- `view.settings_open` - Open application settings (works in focus mode)

### Debug (1)
- `test.external_link` - Test external link handling (debug only)

## Preset Keymaps

### BOSS Default (macOS-style)

```
Window Management:
  Cmd+N               - New window
  Cmd+Shift+W         - Close window

Tab Management:
  Cmd+T               - New tab
  Cmd+W               - Close tab

Browser Controls (in browser tabs only):
  Cmd+R               - Reload
  Cmd+0               - Reset zoom
  Cmd+=               - Zoom in
  Cmd+-               - Zoom out

Navigation:
  Cmd+Arrow Keys      - Navigate between panels
  Cmd+Shift+|         - Split current tab vertically
  Cmd+Shift+-         - Split current tab horizontally
  Ctrl+Space          - Quick switcher (Top of Mind)

Workspace:
  Cmd+Shift+S         - Save workspace

Tools:
  Cmd+O               - Open CodeBase panel

View/UI:
  Cmd+Shift+F         - Toggle focus mode
  Cmd+,               - Open settings

Debug:
  Cmd+Shift+G         - Test external link
```

### VS Code Preset

Visual Studio Code inspired shortcuts:
- Cmd+P: Quick switcher
- Cmd+Shift+E: CodeBase
- Cmd+Alt+Arrow: Panel navigation
- Cmd+Shift+|: Split vertically
- Cmd+Shift+-: Split horizontally

### IntelliJ IDEA Preset

JetBrains IDE inspired shortcuts:
- Cmd+E: Quick switcher
- Cmd+1: CodeBase
- Cmd+Alt+Arrow: Panel navigation
- Cmd+Shift+|: Split vertically
- Cmd+Shift+-: Split horizontally

### Emacs Preset

Ctrl-based shortcuts:
- Alt+X: Quick switcher
- Ctrl+B: CodeBase
- Ctrl+Arrow: Panel navigation
- Ctrl+Shift+|: Split vertically
- Ctrl+Shift+-: Split horizontally

## Integration with Focus Mode

Focus mode (Cmd+Shift+F) hides UI bars while keeping tabs visible. All keyboard shortcuts continue to work:
- Settings window (Cmd+,) renders at app top level
- Quick switcher (Ctrl+Space) works from anywhere
- All shortcuts remain functional regardless of focus mode state

## Settings Access

- **Via keyboard**: Press Cmd+, (or configured shortcut)
- **Via UI**: Click Settings button in top bar (when not in focus mode)
- **Via menu**: Settings > Keyboard Shortcuts to customize

## Context Detection

Shortcuts automatically detect the active context based on the focused tab:
- **GLOBAL** - Always active (window, tab, settings, focus mode)
- **BROWSER** - Active when browser tab is focused
- **TERMINAL** - Active when terminal tab is focused
- **EDITOR** - Active when editor tab is focused
- **WORKSPACE** - Active at workspace level

## Platform Support

- **macOS**: Cmd-based shortcuts with native key interception via AWT
- **Windows/Linux**: Ctrl replaces Cmd, same event flow
- **Display**: ⌘ symbol on macOS, "Ctrl" text on Windows/Linux

## JSON Format

Configuration file location: `~/.boss/keymap-settings.json`

```json
{
  "shortcuts": {
    "window.new": {
      "actionId": "window.new",
      "key": "N",
      "modifiers": ["Cmd"],
      "context": "GLOBAL",
      "category": "Window Management",
      "description": "Create a new application window",
      "enabled": true
    }
  },
  "presetName": "BOSS Default",
  "customized": false,
  "version": 1
}
```

## Troubleshooting

If shortcuts stop working:
1. Check `~/.boss/keymap-settings.json` exists
2. Delete the file to force recreation from preset defaults
3. Check Settings > Keyboard Shortcuts for conflicts
4. Verify the correct preset is selected

Common issues:
- **Stale settings file**: Delete `~/.boss/keymap-settings.json` and restart
- **Conflicts**: Settings UI shows visual warnings for conflicting shortcuts
- **Focus mode**: Settings window and shortcuts work in focus mode (fixed in Issue #74)
