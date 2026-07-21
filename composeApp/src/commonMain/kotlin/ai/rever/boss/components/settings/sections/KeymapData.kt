package ai.rever.boss.components.settings.sections

data class KeyboardShortcut(
    val action: String,
    val key: String,
    val modifiers: List<String>,
    val category: ShortcutCategory,
    val description: String
)

enum class ShortcutCategory {
    WINDOW_MANAGEMENT,
    TAB_MANAGEMENT,
    BROWSER_CONTROLS,
    NAVIGATION,
    WORKSPACE,
    OTHER
}

val ShortcutCategory.displayName: String
    get() = when (this) {
        ShortcutCategory.WINDOW_MANAGEMENT -> "Window Management"
        ShortcutCategory.TAB_MANAGEMENT -> "Tab Management"
        ShortcutCategory.BROWSER_CONTROLS -> "Browser Controls"
        ShortcutCategory.NAVIGATION -> "Navigation"
        ShortcutCategory.WORKSPACE -> "Workspace"
        ShortcutCategory.OTHER -> "Other"
    }

fun getKeyboardShortcuts(): List<KeyboardShortcut> {
    return listOf(
        // Window Management
        KeyboardShortcut(
            action = "New Window",
            key = "N",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.WINDOW_MANAGEMENT,
            description = "Opens a new BOSS Console window"
        ),
        KeyboardShortcut(
            action = "Close Window",
            key = "W",
            modifiers = listOf("Cmd", "Shift"),
            category = ShortcutCategory.WINDOW_MANAGEMENT,
            description = "Closes the current window"
        ),

        // Tab Management
        KeyboardShortcut(
            action = "New Browser Tab",
            key = "T",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.TAB_MANAGEMENT,
            description = "Opens a new Fluck browser tab"
        ),
        KeyboardShortcut(
            action = "Close Tab",
            key = "W",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.TAB_MANAGEMENT,
            description = "Closes the current tab (or window if last tab)"
        ),

        // Browser Controls
        KeyboardShortcut(
            action = "Reload Page",
            key = "R",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.BROWSER_CONTROLS,
            description = "Reloads the current browser page"
        ),
        KeyboardShortcut(
            action = "Reset Zoom",
            key = "0",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.BROWSER_CONTROLS,
            description = "Resets browser zoom to 100%"
        ),
        KeyboardShortcut(
            action = "Zoom In",
            key = "+ or =",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.BROWSER_CONTROLS,
            description = "Increases browser zoom level"
        ),
        KeyboardShortcut(
            action = "Zoom Out",
            key = "-",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.BROWSER_CONTROLS,
            description = "Decreases browser zoom level"
        ),

        // Navigation
        KeyboardShortcut(
            action = "Navigate Left",
            key = "←",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.NAVIGATION,
            description = "Navigate to the previous panel (left)"
        ),
        KeyboardShortcut(
            action = "Navigate Right",
            key = "→",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.NAVIGATION,
            description = "Navigate to the next panel (right)"
        ),
        KeyboardShortcut(
            action = "Quick Switcher",
            key = "Space",
            modifiers = listOf("Ctrl"),
            category = ShortcutCategory.NAVIGATION,
            description = "Opens quick switcher for tab navigation"
        ),

        // Workspace
        KeyboardShortcut(
            action = "Save Workspace",
            key = "S",
            modifiers = listOf("Cmd", "Shift"),
            category = ShortcutCategory.WORKSPACE,
            description = "Saves the current workspace layout"
        ),

        // Other
        KeyboardShortcut(
            action = "Open CodeBase",
            key = "O",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.OTHER,
            description = "Opens the CodeBase plugin to browse files"
        )
    )
}

fun getModifierSymbol(modifier: String): String {
    return when (modifier.lowercase()) {
        "cmd", "command" -> if (isMacOS()) "⌘" else "Ctrl"
        "ctrl", "control" -> "Ctrl"
        "shift" -> "⇧"
        "alt", "option" -> if (isMacOS()) "⌥" else "Alt"
        else -> modifier
    }
}

fun isMacOS(): Boolean {
    return System.getProperty("os.name").contains("Mac", ignoreCase = true)
}
