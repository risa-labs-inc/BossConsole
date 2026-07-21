package ai.rever.boss.keymap.presets

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext

/**
 * Definitions for Emacs-style keymap preset.
 * Emacs uses Ctrl-based keyboard shortcuts.
 * Note: Emacs traditionally uses multi-key sequences (C-x C-f), but we simplify
 * to single-key combinations for better UX in a desktop environment.
 */
object EmacsPresetDefinition {
    fun create(): KeymapSettings {
        val bindings = listOf(
            // Window Management - Emacs: C-x 5 2 (new frame), simplified to Ctrl+Shift+N
            KeyBinding(
                actionId = KeymapActions.WINDOW_NEW,
                key = "N",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                description = KeymapActions.getDescription(KeymapActions.WINDOW_NEW)
            ),
            KeyBinding(
                actionId = KeymapActions.WINDOW_CLOSE,
                key = "W",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.WINDOW_MANAGEMENT,
                description = KeymapActions.getDescription(KeymapActions.WINDOW_CLOSE)
            ),
            // Tab Management - Emacs: C-x C-f (find file), C-x k (kill buffer)
            KeyBinding(
                actionId = KeymapActions.TAB_NEW,
                key = "F",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.TAB_MANAGEMENT,
                description = KeymapActions.getDescription(KeymapActions.TAB_NEW)
            ),
            KeyBinding(
                actionId = KeymapActions.TAB_CLOSE,
                key = "K",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.TAB_MANAGEMENT,
                description = KeymapActions.getDescription(KeymapActions.TAB_CLOSE)
            ),
            KeyBinding(
                actionId = KeymapActions.TAB_NEXT,
                key = "Tab",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.TAB_MANAGEMENT,
                description = KeymapActions.getDescription(KeymapActions.TAB_NEXT)
            ),
            KeyBinding(
                actionId = KeymapActions.TAB_PREVIOUS,
                key = "Tab",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.TAB_MANAGEMENT,
                description = KeymapActions.getDescription(KeymapActions.TAB_PREVIOUS)
            ),
            // Browser Controls - Emacs: C-l (recenter/redraw) for reload
            KeyBinding(
                actionId = KeymapActions.BROWSER_RELOAD,
                key = "L",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.BROWSER,
                category = KeymapActions.Categories.BROWSER_CONTROLS,
                description = KeymapActions.getDescription(KeymapActions.BROWSER_RELOAD)
            ),
            KeyBinding(
                actionId = KeymapActions.BROWSER_ZOOM_RESET,
                key = "Zero",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.BROWSER,
                category = KeymapActions.Categories.BROWSER_CONTROLS,
                description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_RESET)
            ),
            KeyBinding(
                actionId = KeymapActions.BROWSER_ZOOM_IN,
                key = "Equals",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.BROWSER,
                category = KeymapActions.Categories.BROWSER_CONTROLS,
                description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_IN)
            ),
            KeyBinding(
                actionId = KeymapActions.BROWSER_ZOOM_OUT,
                key = "Minus",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.BROWSER,
                category = KeymapActions.Categories.BROWSER_CONTROLS,
                description = KeymapActions.getDescription(KeymapActions.BROWSER_ZOOM_OUT)
            ),
            // Emacs: C-s (isearch-forward) for browser find
            KeyBinding(
                actionId = KeymapActions.BROWSER_FIND,
                key = "S",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.BROWSER,
                category = KeymapActions.Categories.BROWSER_CONTROLS,
                description = KeymapActions.getDescription(KeymapActions.BROWSER_FIND)
            ),
            // Navigation - Emacs: C-x o (other window)
            KeyBinding(
                actionId = KeymapActions.PANEL_NAVIGATE_LEFT,
                key = "DirectionLeft",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.NAVIGATION,
                description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_LEFT)
            ),
            KeyBinding(
                actionId = KeymapActions.PANEL_NAVIGATE_RIGHT,
                key = "DirectionRight",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.NAVIGATION,
                description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_RIGHT)
            ),
            KeyBinding(
                actionId = KeymapActions.PANEL_NAVIGATE_UP,
                key = "DirectionUp",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.NAVIGATION,
                description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_UP)
            ),
            KeyBinding(
                actionId = KeymapActions.PANEL_NAVIGATE_DOWN,
                key = "DirectionDown",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.NAVIGATION,
                description = KeymapActions.getDescription(KeymapActions.PANEL_NAVIGATE_DOWN)
            ),
            // Emacs: C-x 2 (split vertically), C-x 3 (split horizontally) - simplified
            KeyBinding(
                actionId = KeymapActions.PANEL_SPLIT_VERTICAL,
                key = "Backslash",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.NAVIGATION,
                description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_VERTICAL)
            ),
            KeyBinding(
                actionId = KeymapActions.PANEL_SPLIT_HORIZONTAL,
                key = "Minus",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.NAVIGATION,
                description = KeymapActions.getDescription(KeymapActions.PANEL_SPLIT_HORIZONTAL)
            ),
            // Emacs: M-x (execute command) - use Alt+X for quick switcher
            KeyBinding(
                actionId = KeymapActions.QUICK_SWITCHER_OPEN,
                key = "X",
                modifiers = listOf("Alt"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.NAVIGATION,
                description = KeymapActions.getDescription(KeymapActions.QUICK_SWITCHER_OPEN)
            ),
            // Workspace - Emacs: C-x C-s (save)
            KeyBinding(
                actionId = KeymapActions.WORKSPACE_SAVE,
                key = "S",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.WORKSPACE,
                category = KeymapActions.Categories.WORKSPACE,
                description = KeymapActions.getDescription(KeymapActions.WORKSPACE_SAVE)
            ),
            // Tools - Emacs: C-x b (switch buffer) for codebase
            KeyBinding(
                actionId = KeymapActions.CODEBASE_OPEN,
                key = "B",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.TOOLS,
                description = KeymapActions.getDescription(KeymapActions.CODEBASE_OPEN)
            ),
            // Search - Emacs: C-x C-f (find file), use Ctrl+Shift+P for consistency
            KeyBinding(
                actionId = KeymapActions.GLOBAL_SEARCH_OPEN,
                key = "P",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.SEARCH,
                description = KeymapActions.getDescription(KeymapActions.GLOBAL_SEARCH_OPEN)
            ),
            // View/UI - Emacs-style with Ctrl modifier
            KeyBinding(
                actionId = KeymapActions.FOCUS_MODE_TOGGLE,
                key = "F",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.VIEW,
                description = KeymapActions.getDescription(KeymapActions.FOCUS_MODE_TOGGLE)
            ),
            KeyBinding(
                actionId = KeymapActions.SETTINGS_OPEN,
                key = "Comma",
                modifiers = listOf("Ctrl"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.VIEW,
                description = KeymapActions.getDescription(KeymapActions.SETTINGS_OPEN)
            ),
            // Help - Emacs: C-h ? (help) - simplified to ? (Shift+/)
            KeyBinding(
                actionId = KeymapActions.HELP_SHORTCUTS,
                key = "Slash",
                modifiers = listOf("Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.HELP,
                description = KeymapActions.getDescription(KeymapActions.HELP_SHORTCUTS)
            ),
            // Debug
            KeyBinding(
                actionId = KeymapActions.TEST_EXTERNAL_LINK,
                key = "G",
                modifiers = listOf("Ctrl", "Shift"),
                context = ShortcutContext.GLOBAL,
                category = KeymapActions.Categories.DEBUG,
                description = KeymapActions.getDescription(KeymapActions.TEST_EXTERNAL_LINK)
            )
        )

        return KeymapSettings.fromBindings(bindings, presetName = "Emacs", customized = false)
    }
}
