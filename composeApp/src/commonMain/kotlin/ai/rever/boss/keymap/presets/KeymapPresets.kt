package ai.rever.boss.keymap.presets

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
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

        return KeymapSettings.fromBindings(
            withStandardBrowserBindings(bindings),
            presetName = "BOSS Default",
            customized = false,
        )
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

        return KeymapSettings.fromBindings(
            withStandardBrowserBindings(bindings),
            presetName = "VS Code",
            customized = false,
        )
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

        return KeymapSettings.fromBindings(
            withStandardBrowserBindings(bindings),
            presetName = "IntelliJ IDEA",
            customized = false,
        )
    }

    /**
     * Get Emacs-style keymap.
     * Uses Ctrl-based keyboard shortcuts inspired by Emacs.
     */
    fun getEmacsPreset(): KeymapSettings = EmacsPresetDefinition.create()

    /**
     * Chords every desktop browser ships with, in the vocabulary of the keymap model.
     *
     * These live outside the per-preset lists because they are OS/browser conventions rather
     * than IDE taste: Cmd+Shift+T reopens a closed tab in Chrome, Safari, Firefox AND in the
     * VS Code and IntelliJ keymaps, so duplicating them into four hand-written lists would be
     * four places to forget one. [withStandardBrowserBindings] merges them in.
     *
     * Zoom in carries Cmd+Shift+Equals as an ALTERNATE because that is what a keyboard actually
     * reports for "Cmd+Plus" on a US layout - the unshifted Equals binding never sees it.
     */
    internal fun standardBrowserBindings(): List<KeyBinding> {
        fun tabBinding(
            actionId: String,
            key: String,
            modifiers: List<String>,
            alternates: List<KeyStroke> = emptyList(),
        ) = KeyBinding(
            actionId = actionId,
            key = key,
            modifiers = modifiers,
            alternateKeystrokes = alternates,
            context = ShortcutContext.GLOBAL,
            category = KeymapActions.Categories.TAB_MANAGEMENT,
            description = KeymapActions.getDescription(actionId),
        )

        fun browserBinding(
            actionId: String,
            key: String,
            modifiers: List<String>,
        ) = KeyBinding(
            actionId = actionId,
            key = key,
            modifiers = modifiers,
            context = ShortcutContext.BROWSER,
            category = KeymapActions.Categories.BROWSER_CONTROLS,
            description = KeymapActions.getDescription(actionId),
        )

        // Cmd+1..Cmd+8, positionally. Cmd+9 is the LAST tab, not the ninth - browser convention.
        // zip rather than indexing NUMBER_KEY_NAMES: a ninth entry in TAB_SELECT_BY_INDEX would
        // otherwise throw at object initialisation, which is a far worse failure than one action
        // silently missing its chord until the size assertion in the preset test catches it.
        val numbered =
            KeymapActions.TAB_SELECT_BY_INDEX.zip(NUMBER_KEY_NAMES) { actionId, keyName ->
                tabBinding(actionId, keyName, listOf("Cmd"))
            }

        return numbered +
            listOf(
                tabBinding(KeymapActions.TAB_REOPEN_CLOSED, "T", listOf("Cmd", "Shift")),
                tabBinding(KeymapActions.TAB_SELECT_LAST, "Nine", listOf("Cmd")),
                tabBinding(
                    KeymapActions.TAB_NEXT_POSITIONAL,
                    "DirectionRight",
                    listOf("Cmd", "Alt"),
                    alternates = listOf(KeyStroke("CloseBracket", listOf("Cmd", "Shift"))),
                ),
                tabBinding(
                    KeymapActions.TAB_PREVIOUS_POSITIONAL,
                    "DirectionLeft",
                    listOf("Cmd", "Alt"),
                    alternates = listOf(KeyStroke("OpenBracket", listOf("Cmd", "Shift"))),
                ),
                browserBinding(KeymapActions.BROWSER_BACK, "OpenBracket", listOf("Cmd")),
                browserBinding(KeymapActions.BROWSER_FORWARD, "CloseBracket", listOf("Cmd")),
                browserBinding(KeymapActions.BROWSER_DEVTOOLS, "I", listOf("Cmd", "Alt")),
                // Cmd+L for the fluck browser's Focus Address Bar. The ACTION belongs to the
                // plugin (the address bar is its UI); the BINDING has to live here, because a
                // plugin's own defaultBinding is GLOBAL in the host's v1 contract and would
                // shadow EDITOR_GO_TO_LINE - also Cmd+L, and served by the editor plugin's own
                // key handling. Only a keymap entry can say "BROWSER context", which is what
                // makes Cmd+L mean Go To Line in an editor and Focus Address Bar in a browser.
                //
                // Harmless when the plugin is absent or disabled: PluginShortcutRegistry.dispatch
                // returns false for an unowned action id, the interceptor reports the chord
                // unhandled, and it propagates as before.
                //
                // Reaches only as far as the BROWSER context does, which today means "the page
                // has focus": AWTKeyboardInterceptor.updateWindowContext has no callers, so the
                // context comes from walking the AWT focus owner for JxBrowser (see the note
                // there for why wiring it up is not a free win). Back / Forward / DevTools do
                // not notice because they also have menu items, which fire window-wide; Cmd+L
                // deliberately gets no menu item, since a window-wide accelerator for it would
                // swallow Go To Line in the editor - the exact collision this binding exists to
                // avoid. The remaining gap is Cmd+L pressed while focus is in the browser's own
                // Compose chrome, and it closes in the PLUGIN, by handling the chord from its
                // onPreviewKeyEvent the way the editor plugin already handles Go To Line.
                KeyBinding(
                    actionId = FLUCK_FOCUS_ADDRESS_BAR_ACTION,
                    key = "L",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.BROWSER,
                    category = KeymapActions.Categories.BROWSER_CONTROLS,
                    description = "Focus the browser address bar",
                ),
            )
    }

    /**
     * The fluck browser plugin's Focus Address Bar action id.
     *
     * Spelled out rather than imported because it belongs to a dynamically loaded plugin the host
     * does not compile against. The plugin pins the same string with a test, and a drift between
     * the two costs the shortcut and nothing else: an unmatched plugin action id dispatches to
     * nothing and the chord propagates.
     */

    /**
     * Plugin action ids the HOST binds on a plugin's behalf, rather than the plugin contributing
     * a default.
     *
     * The Shortcuts screen hides these when the owning plugin is not loaded - a rebindable
     * "Focus the browser address bar" on an install with no address bar is noise. Membership,
     * not the `plugin.` prefix, is the test: a user's own rebind of some other plugin's shortcut
     * is a real stored binding they must still be able to see and reset while that plugin is
     * disabled or mid-update.
     */
    internal val HOST_AUTHORED_PLUGIN_ACTIONS = setOf(FLUCK_FOCUS_ADDRESS_BAR_ACTION)

    internal const val FLUCK_FOCUS_ADDRESS_BAR_ACTION =
        "plugin.ai.rever.boss.plugin.dynamic.fluckbrowser.focus_address_bar"

    private val NUMBER_KEY_NAMES = listOf("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight")

    /**
     * [preset] plus [standardBrowserBindings], and the Cmd+Shift+Equals alternate on zoom in.
     *
     * A standard binding is DROPPED when the preset already binds that chord to something else
     * (IntelliJ's Cmd+1 opens the Project tool window, so IntelliJ users keep that and simply
     * get no Cmd+1 tab select) - a preset's own opinion outranks the convention, and silently
     * shipping a real conflict would just move the problem into the conflict badge. An action
     * the preset already binds is likewise left alone.
     *
     * Each addition is checked against the PRESET, not against additions already accepted, so a
     * future standard binding whose surviving fallback landed on another standard binding's chord
     * would ship a live conflict. What makes that safe is that [standardBrowserBindings] is
     * asserted internally conflict-free on its own, and every preset is asserted conflict-free
     * after the merge - see StandardBrowserBindingsTest.
     */
    internal fun withStandardBrowserBindings(preset: List<KeyBinding>): List<KeyBinding> {
        val withZoomAlternate = preset.map { binding -> binding.withZoomInShiftAlternate(preset) }

        val boundActions = withZoomAlternate.map { it.actionId }.toHashSet()

        val additions =
            standardBrowserBindings()
                .filter { it.actionId !in boundActions }
                .mapNotNull { candidate -> candidate.withoutChordsTakenBy(withZoomAlternate) }

        return withZoomAlternate + additions
    }

    /**
     * Zoom in, plus the Shift variant of its own chord: Cmd+Shift+Equals is what a US keyboard
     * reports for "Cmd+Plus". Every other binding is returned untouched.
     *
     * Chord-checked against [preset] like every other addition in [withStandardBrowserBindings],
     * rather than applied blind. Nothing claims Cmd+Shift+Equals in any preset today, so this
     * changes no shipped keymap - but it is the same asymmetry that would otherwise let a future
     * preset ship a conflict past the merge that exists to prevent exactly that.
     *
     * Skipped when the chord already carries Shift, which would produce ["Cmd","Shift","Shift"],
     * and when this exact variant is already an alternate. Scoped to that variant rather than to
     * "has any alternates", so a preset gaining some unrelated alternate on zoom in does not
     * silently lose Cmd+Plus.
     */
    private fun KeyBinding.withZoomInShiftAlternate(preset: List<KeyBinding>): KeyBinding {
        val shiftVariant = KeyStroke(key, modifiers + "Shift")
        val wanted =
            actionId == KeymapActions.BROWSER_ZOOM_IN &&
                modifiers.none { it.equals("Shift", ignoreCase = true) } &&
                alternateKeystrokes.none { it.signature() == shiftVariant.signature() } &&
                preset.none { it.actionId != actionId && it.claimsChord(shiftVariant, context) }
        return if (wanted) withAlternateKeystroke(shiftVariant) else this
    }

    /**
     * [this] with every chord [preset] already claims removed, or null if that leaves nothing.
     *
     * Internal because migration needs the same predicate: adding a preset's new actions to a
     * STORED keymap verbatim would ship exactly the conflicts this drops, and a customised
     * keymap is where the user has claimed chords the preset does not know about.
     *
     * Per-KEYSTROKE rather than per-binding, because these bindings carry alternates that a
     * preset's claim on the primary says nothing about: VS Code and IntelliJ both put panel
     * navigation on Cmd+Alt+Arrow, which is the primary of positional tab stepping - dropping
     * the whole binding there would take Cmd+Shift+[ and Cmd+Shift+] with it and leave those
     * presets with no way to step tabs at all. The first surviving keystroke becomes the
     * primary, since [KeyBinding.key] is what the menu bar reads for its accelerator.
     */
    internal fun KeyBinding.withoutChordsTakenBy(preset: List<KeyBinding>): KeyBinding? {
        val survivors =
            allKeystrokes.filter { keystroke ->
                preset.none { existing -> existing.claimsChord(keystroke, context) }
            }
        val primary = survivors.firstOrNull() ?: return null
        return copy(
            key = primary.key,
            modifiers = primary.modifiers,
            alternateKeystrokes = survivors.drop(1),
        )
    }

    /** Does [this] answer to [keystroke] in a context where it and [candidateContext] overlap? */
    internal fun KeyBinding.claimsChord(
        keystroke: KeyStroke,
        candidateContext: ShortcutContext,
    ): Boolean {
        val contextsOverlap =
            context == ShortcutContext.GLOBAL ||
                candidateContext == ShortcutContext.GLOBAL ||
                context == candidateContext
        if (!contextsOverlap) return false
        return allKeystrokes.any { it.signature() == keystroke.signature() }
    }

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
