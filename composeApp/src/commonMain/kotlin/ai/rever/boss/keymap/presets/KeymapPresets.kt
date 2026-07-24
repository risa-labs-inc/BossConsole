package ai.rever.boss.keymap.presets

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext

/**
 * Preset keyboard shortcut configurations.
 * Provides default keymaps and popular IDE-style presets.
 */
object KeymapPresets {
    /**
     * Get the default BOSS keymap (matches current hardcoded shortcuts).
     */
    fun getBOSSDefault(): KeymapSettings {
        val bindings =
            listOf(
                // Window Management
                KeyBinding(
                    actionId = KeymapActions.WINDOW_NEW,
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.WINDOW_NEW),
                ),
                KeyBinding(
                    actionId = KeymapActions.WINDOW_CLOSE,
                    key = "W",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.WINDOW_CLOSE),
                ),
                // Tab Management
                KeyBinding(
                    actionId = KeymapActions.TAB_NEW,
                    key = "T",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_NEW),
                ),
                KeyBinding(
                    actionId = KeymapActions.TAB_CLOSE,
                    key = "W",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_CLOSE),
                ),
                KeyBinding(
                    actionId = KeymapActions.TAB_NEXT,
                    key = "Tab",
                    modifiers = listOf("Ctrl"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_NEXT),
                ),
                KeyBinding(
                    actionId = KeymapActions.TAB_PREVIOUS,
                    key = "Tab",
                    modifiers = listOf("Ctrl", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_PREVIOUS),
                ),
                // Browser Controls
                KeyBinding(
                    actionId = KeymapActions.BROWSER_RELOAD,
                    key = "R",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_RELOAD),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_RESET,
                    key = "Zero",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_RESET),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_IN,
                    key = "Equals",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_IN),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_OUT,
                    key = "Minus",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_OUT),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_FIND,
                    key = "F",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_FIND),
                ),
                // Navigation
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_LEFT,
                    key = "DirectionLeft",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_LEFT),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_RIGHT,
                    key = "DirectionRight",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_RIGHT),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_UP,
                    key = "DirectionUp",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_UP),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_DOWN,
                    key = "DirectionDown",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_DOWN),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_SPLIT_VERTICAL,
                    key = "Backslash",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_VERTICAL),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_SPLIT_HORIZONTAL,
                    key = "Minus",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_HORIZONTAL),
                ),
                KeyBinding(
                    actionId = KeymapActions.QUICK_SWITCHER_OPEN,
                    key = "Spacebar",
                    modifiers = listOf("Ctrl"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.QUICK_SWITCHER_OPEN),
                ),
                // Workspace
                KeyBinding(
                    actionId = KeymapActions.WORKSPACE_SAVE,
                    key = "S",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.WORKSPACE,
                    category = KeymapActions.Categories.WORKSPACE,
                    description = KeymapActions.getDescription(KeymapActions.WORKSPACE_SAVE),
                ),
                // Editor
                KeyBinding(
                    actionId = KeymapActions.EDITOR_SAVE,
                    key = "S",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_SAVE),
                ),
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND,
                    key = "F",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND),
                ),
                KeyBinding(
                    actionId = KeymapActions.EDITOR_REPLACE,
                    key = "H",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_REPLACE),
                ),
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND_NEXT,
                    key = "G",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND_NEXT),
                ),
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND_PREVIOUS,
                    key = "G",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND_PREVIOUS),
                ),
                KeyBinding(
                    actionId = KeymapActions.EDITOR_GO_TO_LINE,
                    key = "L",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_GO_TO_LINE),
                ),
                // Tools
                KeyBinding(
                    actionId = KeymapActions.CODEBASE_OPEN,
                    key = "O",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TOOLS,
                    description = KeymapActions.getDescription(KeymapActions.CODEBASE_OPEN),
                ),
                // Search
                KeyBinding(
                    actionId = KeymapActions.GLOBAL_SEARCH_OPEN,
                    key = "P",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.SEARCH,
                    description = KeymapActions.getDescription(KeymapActions.GLOBAL_SEARCH_OPEN),
                ),
                // View/UI
                KeyBinding(
                    actionId = KeymapActions.FOCUS_MODE_TOGGLE,
                    key = "F",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.VIEW,
                    description = KeymapActions.getDescription(KeymapActions.FOCUS_MODE_TOGGLE),
                ),
                KeyBinding(
                    actionId = KeymapActions.SETTINGS_OPEN,
                    key = "Comma",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.VIEW,
                    description = KeymapActions.getDescription(KeymapActions.SETTINGS_OPEN),
                ),
                // Help
                KeyBinding(
                    actionId = KeymapActions.HELP_SHORTCUTS,
                    key = "Slash",
                    modifiers = listOf("Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.HELP,
                    description = KeymapActions.getDescription(KeymapActions.HELP_SHORTCUTS),
                ),
                // Debug
                KeyBinding(
                    actionId = KeymapActions.TEST_EXTERNAL_LINK,
                    key = "G",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.DEBUG,
                    description = KeymapActions.getDescription(KeymapActions.TEST_EXTERNAL_LINK),
                ),
            )

        return KeymapSettings.fromBindings(bindings, presetName = "BOSS Default", customized = false)
    }

    /**
     * Get VS Code-style keymap.
     * Based on Visual Studio Code's default keyboard shortcuts.
     */
    fun getVSCodePreset(): KeymapSettings {
        val bindings =
            listOf(
                // Window Management - VS Code uses Cmd+Shift+N for new window
                KeyBinding(
                    actionId = KeymapActions.WINDOW_NEW,
                    key = "N",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.WINDOW_NEW),
                ),
                KeyBinding(
                    actionId = KeymapActions.WINDOW_CLOSE,
                    key = "W",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.WINDOW_CLOSE),
                ),
                // Tab Management - VS Code uses Cmd+N for new file, Cmd+W for close
                KeyBinding(
                    actionId = KeymapActions.TAB_NEW,
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_NEW),
                ),
                KeyBinding(
                    actionId = KeymapActions.TAB_CLOSE,
                    key = "W",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_CLOSE),
                ),
                // VS Code uses Ctrl+Tab / Ctrl+Shift+Tab to switch between editor tabs
                KeyBinding(
                    actionId = KeymapActions.TAB_NEXT,
                    key = "Tab",
                    modifiers = listOf("Ctrl"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_NEXT),
                ),
                KeyBinding(
                    actionId = KeymapActions.TAB_PREVIOUS,
                    key = "Tab",
                    modifiers = listOf("Ctrl", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_PREVIOUS),
                ),
                // Browser Controls
                KeyBinding(
                    actionId = KeymapActions.BROWSER_RELOAD,
                    key = "R",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_RELOAD),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_RESET,
                    key = "Zero",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_RESET),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_IN,
                    key = "Equals",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_IN),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_OUT,
                    key = "Minus",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_OUT),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_FIND,
                    key = "F",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_FIND),
                ),
                // Navigation - VS Code uses Cmd+Alt+Arrow for editor group navigation
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_LEFT,
                    key = "DirectionLeft",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_LEFT),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_RIGHT,
                    key = "DirectionRight",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_RIGHT),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_UP,
                    key = "DirectionUp",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_UP),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_DOWN,
                    key = "DirectionDown",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_DOWN),
                ),
                // Split panel shortcuts
                KeyBinding(
                    actionId = KeymapActions.PANEL_SPLIT_VERTICAL,
                    key = "Backslash",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_VERTICAL),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_SPLIT_HORIZONTAL,
                    key = "Minus",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_HORIZONTAL),
                ),
                // VS Code uses Cmd+P for quick open (quick switcher)
                KeyBinding(
                    actionId = KeymapActions.QUICK_SWITCHER_OPEN,
                    key = "P",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.QUICK_SWITCHER_OPEN),
                ),
                // Workspace - VS Code uses Cmd+K S for save workspace
                KeyBinding(
                    actionId = KeymapActions.WORKSPACE_SAVE,
                    key = "S",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.WORKSPACE,
                    category = KeymapActions.Categories.WORKSPACE,
                    description = KeymapActions.getDescription(KeymapActions.WORKSPACE_SAVE),
                ),
                // Editor - Cmd+S to save current file
                KeyBinding(
                    actionId = KeymapActions.EDITOR_SAVE,
                    key = "S",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_SAVE),
                ),
                // VS Code uses Cmd+F for Find
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND,
                    key = "F",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND),
                ),
                // VS Code uses Cmd+H for Replace (Alt+Cmd+F is also used)
                KeyBinding(
                    actionId = KeymapActions.EDITOR_REPLACE,
                    key = "H",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_REPLACE),
                ),
                // VS Code uses F3/Cmd+G for Find Next
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND_NEXT,
                    key = "G",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND_NEXT),
                ),
                // VS Code uses Shift+F3/Cmd+Shift+G for Find Previous
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND_PREVIOUS,
                    key = "G",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND_PREVIOUS),
                ),
                // VS Code uses Ctrl+G for Go to Line
                KeyBinding(
                    actionId = KeymapActions.EDITOR_GO_TO_LINE,
                    key = "G",
                    modifiers = listOf("Ctrl"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_GO_TO_LINE),
                ),
                // Tools - VS Code uses Cmd+Shift+E for Explorer (sidebar)
                KeyBinding(
                    actionId = KeymapActions.CODEBASE_OPEN,
                    key = "E",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TOOLS,
                    description = KeymapActions.getDescription(KeymapActions.CODEBASE_OPEN),
                ),
                // Search - VS Code uses Cmd+Shift+P for Command Palette, we use for global search
                KeyBinding(
                    actionId = KeymapActions.GLOBAL_SEARCH_OPEN,
                    key = "P",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.SEARCH,
                    description = KeymapActions.getDescription(KeymapActions.GLOBAL_SEARCH_OPEN),
                ),
                // View/UI - VS Code doesn't have built-in zen mode toggle, use same as BOSS
                KeyBinding(
                    actionId = KeymapActions.FOCUS_MODE_TOGGLE,
                    key = "F",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.VIEW,
                    description = KeymapActions.getDescription(KeymapActions.FOCUS_MODE_TOGGLE),
                ),
                KeyBinding(
                    actionId = KeymapActions.SETTINGS_OPEN,
                    key = "Comma",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.VIEW,
                    description = KeymapActions.getDescription(KeymapActions.SETTINGS_OPEN),
                ),
                // Help - VS Code uses ? for keyboard shortcuts cheatsheet
                KeyBinding(
                    actionId = KeymapActions.HELP_SHORTCUTS,
                    key = "Slash",
                    modifiers = listOf("Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.HELP,
                    description = KeymapActions.getDescription(KeymapActions.HELP_SHORTCUTS),
                ),
                // Debug - Keep same as BOSS
                KeyBinding(
                    actionId = KeymapActions.TEST_EXTERNAL_LINK,
                    key = "G",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.DEBUG,
                    description = KeymapActions.getDescription(KeymapActions.TEST_EXTERNAL_LINK),
                ),
            )

        return KeymapSettings.fromBindings(bindings, presetName = "VS Code", customized = false)
    }

    /**
     * Get IntelliJ IDEA-style keymap.
     * Based on IntelliJ IDEA's default macOS keyboard shortcuts.
     */
    fun getIntelliJPreset(): KeymapSettings {
        val bindings =
            listOf(
                // Window Management - IntelliJ uses Cmd+Shift+N for search, we'll use for new window
                KeyBinding(
                    actionId = KeymapActions.WINDOW_NEW,
                    key = "N",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.WINDOW_NEW),
                ),
                KeyBinding(
                    actionId = KeymapActions.WINDOW_CLOSE,
                    key = "W",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.WINDOW_CLOSE),
                ),
                // Tab Management - IntelliJ uses Cmd+N for "New..."
                KeyBinding(
                    actionId = KeymapActions.TAB_NEW,
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_NEW),
                ),
                KeyBinding(
                    actionId = KeymapActions.TAB_CLOSE,
                    key = "W",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_CLOSE),
                ),
                // IntelliJ uses Ctrl+Tab (Switcher) to move between tabs
                KeyBinding(
                    actionId = KeymapActions.TAB_NEXT,
                    key = "Tab",
                    modifiers = listOf("Ctrl"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_NEXT),
                ),
                KeyBinding(
                    actionId = KeymapActions.TAB_PREVIOUS,
                    key = "Tab",
                    modifiers = listOf("Ctrl", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TAB_MANAGEMENT,
                    description = KeymapActions.getDescription(KeymapActions.TAB_PREVIOUS),
                ),
                // Browser Controls - IntelliJ uses Cmd+R for Run, we'll use for reload
                KeyBinding(
                    actionId = KeymapActions.BROWSER_RELOAD,
                    key = "R",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_RELOAD),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_RESET,
                    key = "Zero",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_RESET),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_IN,
                    key = "Equals",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_IN),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_ZOOM_OUT,
                    key = "Minus",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_OUT),
                ),
                KeyBinding(
                    actionId = KeymapActions.BROWSER_FIND,
                    key = "F",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = KeymapActions.getDescription(KeymapActions.BROWSER_FIND),
                ),
                // Navigation - IntelliJ uses Cmd+Alt+Arrow for navigation
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_LEFT,
                    key = "DirectionLeft",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_LEFT),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_RIGHT,
                    key = "DirectionRight",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_RIGHT),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_UP,
                    key = "DirectionUp",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_UP),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_NAVIGATE_DOWN,
                    key = "DirectionDown",
                    modifiers = listOf("Cmd", "Alt"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_DOWN),
                ),
                // Split panel shortcuts
                KeyBinding(
                    actionId = KeymapActions.PANEL_SPLIT_VERTICAL,
                    key = "Backslash",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_VERTICAL),
                ),
                KeyBinding(
                    actionId = KeymapActions.PANEL_SPLIT_HORIZONTAL,
                    key = "Minus",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_HORIZONTAL),
                ),
                // IntelliJ uses Cmd+E for Recent Files (like quick switcher)
                KeyBinding(
                    actionId = KeymapActions.QUICK_SWITCHER_OPEN,
                    key = "E",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.NAVIGATION,
                    description = KeymapActions.getDescription(KeymapActions.QUICK_SWITCHER_OPEN),
                ),
                // Workspace - IntelliJ uses Cmd+Shift+S for save all (workspace)
                KeyBinding(
                    actionId = KeymapActions.WORKSPACE_SAVE,
                    key = "S",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.WORKSPACE,
                    category = KeymapActions.Categories.WORKSPACE,
                    description = KeymapActions.getDescription(KeymapActions.WORKSPACE_SAVE),
                ),
                // Editor - IntelliJ uses Cmd+S for save current file
                KeyBinding(
                    actionId = KeymapActions.EDITOR_SAVE,
                    key = "S",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_SAVE),
                ),
                // IntelliJ uses Cmd+F for Find
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND,
                    key = "F",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND),
                ),
                // IntelliJ uses Cmd+R for Replace (we use Cmd+H to avoid conflict with Run)
                KeyBinding(
                    actionId = KeymapActions.EDITOR_REPLACE,
                    key = "R",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_REPLACE),
                ),
                // IntelliJ uses Cmd+G / F3 for Find Next
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND_NEXT,
                    key = "G",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND_NEXT),
                ),
                // IntelliJ uses Cmd+Shift+G / Shift+F3 for Find Previous
                KeyBinding(
                    actionId = KeymapActions.EDITOR_FIND_PREVIOUS,
                    key = "G",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_FIND_PREVIOUS),
                ),
                // IntelliJ uses Cmd+L for Go to Line
                KeyBinding(
                    actionId = KeymapActions.EDITOR_GO_TO_LINE,
                    key = "L",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.EDITOR,
                    category = KeymapActions.Categories.EDITOR,
                    description = KeymapActions.getDescription(KeymapActions.EDITOR_GO_TO_LINE),
                ),
                // Tools - IntelliJ uses Cmd+1 for Project tool window
                KeyBinding(
                    actionId = KeymapActions.CODEBASE_OPEN,
                    key = "One",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.TOOLS,
                    description = KeymapActions.getDescription(KeymapActions.CODEBASE_OPEN),
                ),
                // Search - IntelliJ uses Cmd+Shift+N for "Go to File", we use Cmd+Shift+P for consistency
                KeyBinding(
                    actionId = KeymapActions.GLOBAL_SEARCH_OPEN,
                    key = "P",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.SEARCH,
                    description = KeymapActions.getDescription(KeymapActions.GLOBAL_SEARCH_OPEN),
                ),
                // View/UI - IntelliJ uses Cmd+Shift+F12 for hide all tool windows, we'll use Cmd+Shift+F
                KeyBinding(
                    actionId = KeymapActions.FOCUS_MODE_TOGGLE,
                    key = "F",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.VIEW,
                    description = KeymapActions.getDescription(KeymapActions.FOCUS_MODE_TOGGLE),
                ),
                KeyBinding(
                    actionId = KeymapActions.SETTINGS_OPEN,
                    key = "Comma",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.VIEW,
                    description = KeymapActions.getDescription(KeymapActions.SETTINGS_OPEN),
                ),
                // Help - IntelliJ style
                KeyBinding(
                    actionId = KeymapActions.HELP_SHORTCUTS,
                    key = "Slash",
                    modifiers = listOf("Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.HELP,
                    description = KeymapActions.getDescription(KeymapActions.HELP_SHORTCUTS),
                ),
                // Debug
                KeyBinding(
                    actionId = KeymapActions.TEST_EXTERNAL_LINK,
                    key = "G",
                    modifiers = listOf("Cmd", "Shift"),
                    context = ShortcutContext.GLOBAL,
                    category = KeymapActions.Categories.DEBUG,
                    description = KeymapActions.getDescription(KeymapActions.TEST_EXTERNAL_LINK),
                ),
            )

        return KeymapSettings.fromBindings(bindings, presetName = "IntelliJ IDEA", customized = false)
    }

    /**
     * Get Emacs-style keymap.
     * Uses Ctrl-based keyboard shortcuts inspired by Emacs.
     */
    fun getEmacsPreset(): KeymapSettings = EmacsPresetDefinition.create()

    /**
     * Get all available preset names.
     */
    fun getAvailablePresets(): List<String> =
        listOf(
            "BOSS Default",
            "VS Code",
            "IntelliJ IDEA",
            "Emacs",
        )
}
