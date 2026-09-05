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
- `KeymapActions.kt` - Registry of 47 action IDs across 11 categories

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

## Available Actions (47 total)

### Window Management (2)
- `window.new` - Create new window
- `window.close` - Close current window

### Tab Management (16)
- `tab.new` - Open new tab dialog
- `tab.close` - Close current tab (or window if last tab)
- `tab.next` / `tab.previous` - Step tabs following the configured tab-switch mode (MRU by
  default, with the switcher overlay). Held-modifier driven: the MRU cycle commits when the
  modifier is released.
- `tab.reopen_closed` - Reopen the most recently closed tab. Works for every tab type; the
  history is per window and holds 25 entries
- `tab.next_positional` / `tab.previous_positional` - Step in tab-bar order regardless of the
  tab-switch mode, and start no MRU cycle. Separate actions because these chords are discrete,
  so an MRU cycle armed by one would never be committed
- `tab.select_1` .. `tab.select_8` - Select the tab at that position
- `tab.select_last` - Select the last tab, whatever its index (the browser meaning of Cmd+9)

### Browser Controls (8)
All BROWSER context only.
- `browser.reload` - Reload browser tab
- `browser.zoom_reset` - Reset zoom to 100%
- `browser.zoom_in` - Increase zoom
- `browser.zoom_out` - Decrease zoom
- `browser.find` - Find text on page
- `browser.back` / `browser.forward` - Browser history
- `browser.devtools` - Open developer tools

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

### Editor (7)
All EDITOR context only. Several exist so the chord is listed and rebindable while the editor
plugin serves it from its own key handling, `editor.go_to_line` among them; the AWT interceptor
has no dispatch case for those and deliberately leaves the event to propagate.
- `editor.save` / `editor.save_all` - Save the current file / all files
- `editor.find` / `editor.replace` - Find, find and replace
- `editor.find_next` / `editor.find_previous` - Step matches
- `editor.go_to_line` - Go to line number

### Tools (1)
- `codebase.open` - Open CodeBase panel

### Search (1)
- `search.open` - Open global search (Double-Shift)

### View/UI (2)
- `view.focus_mode_toggle` - Toggle focus mode (hide/show UI bars)
- `view.settings_open` - Open application settings (works in focus mode)

### Help (1)
- `help.shortcuts` - Show the keyboard shortcuts help dialog

### Debug (1)
- `test.external_link` - Test external link handling (debug only)

### Plugin-contributed actions

Plugins can contribute their own global actions via `PluginContext.registerShortcutActionProvider`,
under ids namespaced `plugin.<pluginId>.<name>`. These are GLOBAL only and are not listed above,
because the set depends on which plugins are installed. A host binding always wins a chord
collision, and a user rebind stored under the plugin's action id supersedes the plugin's default.

The fluck browser contributes `Focus Address Bar` on Cmd+L, which is why `editor.go_to_line`
shares that chord: the interceptor stops at a matched host binding rather than falling through to
the plugin pass, so Cmd+L is Go To Line in an editor and Focus Address Bar in a browser.

## Preset Keymaps

### BOSS Default (macOS-style)

```
Window Management:
  Cmd+N               - New window
  Cmd+Shift+W         - Close window

Tab Management:
  Cmd+T               - New tab
  Cmd+W               - Close tab
  Cmd+Shift+T         - Reopen closed tab
  Ctrl+Tab            - Next tab (MRU by default; see Tab switch mode)
  Ctrl+Shift+Tab      - Previous tab
  Cmd+Opt+Right/Left  - Next/previous tab in tab-bar order
  Cmd+Shift+] / [     - Same, alternate chords
  Cmd+1 .. Cmd+8      - Select tab by position
  Cmd+9               - Select the last tab

Browser Controls (in browser tabs only):
  Cmd+R               - Reload
  Cmd+0               - Reset zoom
  Cmd+= / Cmd+Shift+= - Zoom in (the second is what a US layout reports for Cmd+Plus)
  Cmd+-               - Zoom out
  Cmd+F               - Find on page
  Cmd+[ / Cmd+]       - Back / forward
  Cmd+Opt+I           - Developer tools

Navigation:
  Cmd+Arrow Keys      - Navigate between panels (only with a split open)
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

The standard browser chords above are shared by every preset rather than written out per preset,
via `KeymapPresets.standardBrowserBindings()`. Merging is per keystroke: a chord the preset
already claims is dropped and the first surviving keystroke becomes the primary. So VS Code and
IntelliJ, which both put panel navigation on Cmd+Opt+Arrow, get positional tab stepping on
Cmd+Shift+[ and Cmd+Shift+] instead; IntelliJ keeps Cmd+1 on the Project tool window and gets no
Cmd+1 tab select.

Existing installs pick these up through `KeymapSettingsManager.migrateSettings`, which adds
actions missing from a stored keymap and tops up new alternates on bindings whose primary still
matches the preset. A rebound chord is left alone. Chords are compared order- and
case-insensitively on both halves, so a hand-edited `["Shift","Cmd"]` reads the same as
`["Cmd","Shift"]`.

Migration is chord-checked the same way the merge is: a new action whose chords a stored keymap
already claims is dropped rather than added as a live conflict, which matters here because one
migration lands twenty chords onto a keymap the user may have customised.

Host bindings beat plugin defaults, and this is where that starts to bite: the new Cmd+1..Cmd+9
entries permanently shadow any plugin GLOBAL default on those chords, and a host binding the
interceptor matches but does not dispatch now stops there rather than falling through to the
plugin pass. That is the documented rule working as intended, but Cmd+1..9 are popular plugin
chords, and this is the change that closes them. A plugin wanting one has to be rebound by the
user, which puts it in the keymap where it wins the earlier pass.

### What these chords take from other surfaces

One rule, applied everywhere: a chord is claimed when the action can act, and left alone when it
cannot. It is NOT gated on focus, because the mechanism that fires these window-wide is a native
menu accelerator, and a Compose `MenuBar` accelerator ignores the binding's `ShortcutContext`.
Two consequences worth knowing before filing a bug:

- **Cmd+[ and Cmd+]** become browser history whenever a browser is the visible surface of the
  active main panel. With a browser there and focus in a sidebar editor, they navigate history
  rather than outdent and indent. Narrowing further needs a focus signal the menu layer does not
  have.
- **Cmd+Opt+Left/Right** (and the Cmd+Shift+Bracket alternates) step tabs whenever the active
  panel has two or more, whatever surface has focus - so a terminal or editor does not see them
  in a multi-tab panel. This is the same rule as above, not a different one: the action can act,
  so the chord is claimed. Cmd+1..Cmd+8 in a two-tab panel is the mirror image - the action
  cannot act, so the chord goes through.

A chord that cannot act is not claimed. Cmd+1..Cmd+8 with fewer tabs than that, Cmd+9 with no
tabs, Cmd+Shift+T with an empty history, tab stepping in a single-tab panel and panel navigation
in a single-panel window all propagate to whatever has focus instead of being swallowed. This
matters because a native menu accelerator fires window-wide whatever the binding's context, so an
always-enabled item would take Cmd+[ from an editor, where it is outdent. Browsers do consume
Cmd+1..9 unconditionally; BOSS does not, because those chords reach surfaces a browser has no
equivalent of.

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

## Remote plugin surfaces are keystroke sinks while focused

A remote (out-of-process) plugin surface taps keys the host did not claim and forwards them to the
plugin as `UIEvent.key`. Three things bound that, and one thing does not:

- **The host keymap wins.** `AWTKeyboardInterceptor` runs at the `KeyboardFocusManager`, upstream of
  Compose, and consumes what it dispatches - so a bound shortcut never reaches a plugin surface. The
  tap re-checks the keymap itself as a backstop, using `KeymapMatcher.hasSystemModifier` so it declines
  only keys the interceptor would actually have acted on. (Declining *everything* bound would lose keys
  the host never dispatches, such as `Shift+/`.)
- **The focused widget gets first refusal.** `onKeyEvent` fires on the way *up* from the focus target,
  so a widget that handles a key keeps it: typing into a remote text field produces a text-change event,
  not a key per character. The boundary is consumption, though - keys a widget ignores still reach the
  plugin while that surface has focus.
- **The tap never consumes.** It always returns `false`, so a plugin cannot swallow a shortcut or trap
  the user in a panel.

**What is not bounded is which plugin may listen.** `Modifier.forwardUnclaimedKeys` ends in
`.focusable()`, so a remote surface is a focus target in its own right - a surface made only of labels
can still take focus and receive every unclaimed key-down, including plain typing when no widget inside
it holds focus. There is no plugin capability model gating that today. The intended fix is a declared
opt-in (a `wants_keys` flag on `UIRegistration`), which makes both the focus stop and the tap something
a plugin asks for; it needs the same per-connection plugin identity as attributing `UnregisterUI`.
