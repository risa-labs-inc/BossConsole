package ai.rever.boss.keymap.model

/**
 * Registry of all keyboard shortcut action IDs in BOSS.
 *
 * Action IDs follow the pattern: "category.action"
 * Examples: "window.new", "tab.close", "browser.reload"
 */
object KeymapActions {
    // Window Management Actions
    const val WINDOW_NEW = "window.new"
    const val WINDOW_CLOSE = "window.close"

    // Tab Management Actions
    const val TAB_NEW = "tab.new"
    const val TAB_CLOSE = "tab.close"
    const val TAB_NEXT = "tab.next"
    const val TAB_PREVIOUS = "tab.previous"
    const val TAB_REOPEN_CLOSED = "tab.reopen_closed"

    // Positional tab stepping, distinct from TAB_NEXT/TAB_PREVIOUS: those follow the
    // configured TabSwitchMode (MRU by default, with the cycle overlay). Cmd+Opt+Arrow is a
    // browser chord and browsers always step in tab-bar order, so it needs its own action
    // rather than a second binding on a mode-driven one.
    const val TAB_NEXT_POSITIONAL = "tab.next_positional"
    const val TAB_PREVIOUS_POSITIONAL = "tab.previous_positional"

    // Cmd+1..Cmd+8 select by position; Cmd+9 selects the LAST tab whatever its index,
    // which is the browser convention (not "the ninth tab").
    const val TAB_SELECT_1 = "tab.select_1"
    const val TAB_SELECT_2 = "tab.select_2"
    const val TAB_SELECT_3 = "tab.select_3"
    const val TAB_SELECT_4 = "tab.select_4"
    const val TAB_SELECT_5 = "tab.select_5"
    const val TAB_SELECT_6 = "tab.select_6"
    const val TAB_SELECT_7 = "tab.select_7"
    const val TAB_SELECT_8 = "tab.select_8"
    const val TAB_SELECT_LAST = "tab.select_last"

    /** Ordered Cmd+1..Cmd+8 action ids; index 0 is the first tab. */
    val TAB_SELECT_BY_INDEX =
        listOf(
            TAB_SELECT_1,
            TAB_SELECT_2,
            TAB_SELECT_3,
            TAB_SELECT_4,
            TAB_SELECT_5,
            TAB_SELECT_6,
            TAB_SELECT_7,
            TAB_SELECT_8,
        )

    // Browser Control Actions
    const val BROWSER_RELOAD = "browser.reload"
    const val BROWSER_ZOOM_RESET = "browser.zoom_reset"
    const val BROWSER_ZOOM_IN = "browser.zoom_in"
    const val BROWSER_ZOOM_OUT = "browser.zoom_out"
    const val BROWSER_FIND = "browser.find"
    const val BROWSER_BACK = "browser.back"
    const val BROWSER_FORWARD = "browser.forward"
    const val BROWSER_DEVTOOLS = "browser.devtools"

    // Navigation Actions
    const val PANEL_NAVIGATE_LEFT = "panel.navigate_left"
    const val PANEL_NAVIGATE_RIGHT = "panel.navigate_right"
    const val PANEL_NAVIGATE_UP = "panel.navigate_up"
    const val PANEL_NAVIGATE_DOWN = "panel.navigate_down"
    const val PANEL_SPLIT_VERTICAL = "panel.split_vertical"
    const val PANEL_SPLIT_HORIZONTAL = "panel.split_horizontal"
    const val QUICK_SWITCHER_OPEN = "quick_switcher.open"

    // Workspace Actions
    const val WORKSPACE_SAVE = "workspace.save"

    // Editor Actions
    const val EDITOR_SAVE = "editor.save"
    const val EDITOR_SAVE_ALL = "editor.save_all"
    const val EDITOR_FIND = "editor.find"
    const val EDITOR_REPLACE = "editor.replace"
    const val EDITOR_FIND_NEXT = "editor.find_next"
    const val EDITOR_FIND_PREVIOUS = "editor.find_previous"
    const val EDITOR_GO_TO_LINE = "editor.go_to_line"

    // Panel/Tool Actions
    const val CODEBASE_OPEN = "codebase.open"

    // Search Actions
    const val GLOBAL_SEARCH_OPEN = "search.open"

    // View/UI Actions
    const val FOCUS_MODE_TOGGLE = "view.focus_mode_toggle"
    const val SETTINGS_OPEN = "view.settings_open"

    // Help Actions
    const val HELP_SHORTCUTS = "help.shortcuts"

    // Test/Debug Actions
    const val TEST_EXTERNAL_LINK = "test.external_link"

    /**
     * Category definitions for organizing shortcuts in the UI.
     */
    object Categories {
        const val WINDOW_MANAGEMENT = "Window Management"
        const val TAB_MANAGEMENT = "Tab Management"
        const val BROWSER_CONTROLS = "Browser Controls"
        const val NAVIGATION = "Navigation"
        const val WORKSPACE = "Workspace"
        const val EDITOR = "Editor"
        const val TOOLS = "Tools"
        const val SEARCH = "Search"
        const val VIEW = "View/UI"
        const val HELP = "Help"
        const val DEBUG = "Debug"
    }

    /**
     * Human-readable descriptions for each action.
     */
    val descriptions =
        mapOf(
            WINDOW_NEW to "Create a new application window",
            WINDOW_CLOSE to "Close the current window",
            TAB_NEW to "Open new tab dialog",
            TAB_CLOSE to "Close the current tab (or window if last tab)",
            TAB_NEXT to "Switch to the next tab in the active panel",
            TAB_PREVIOUS to "Switch to the previous tab in the active panel",
            TAB_REOPEN_CLOSED to "Reopen the most recently closed tab",
            TAB_NEXT_POSITIONAL to "Switch to the next tab in tab-bar order",
            TAB_PREVIOUS_POSITIONAL to "Switch to the previous tab in tab-bar order",
            TAB_SELECT_1 to "Switch to the 1st tab",
            TAB_SELECT_2 to "Switch to the 2nd tab",
            TAB_SELECT_3 to "Switch to the 3rd tab",
            TAB_SELECT_4 to "Switch to the 4th tab",
            TAB_SELECT_5 to "Switch to the 5th tab",
            TAB_SELECT_6 to "Switch to the 6th tab",
            TAB_SELECT_7 to "Switch to the 7th tab",
            TAB_SELECT_8 to "Switch to the 8th tab",
            TAB_SELECT_LAST to "Switch to the last tab",
            BROWSER_RELOAD to "Reload the current browser tab",
            BROWSER_ZOOM_RESET to "Reset browser zoom to 100%",
            BROWSER_ZOOM_IN to "Increase browser zoom level",
            BROWSER_ZOOM_OUT to "Decrease browser zoom level",
            BROWSER_FIND to "Find text on page",
            BROWSER_BACK to "Go back in browser history",
            BROWSER_FORWARD to "Go forward in browser history",
            BROWSER_DEVTOOLS to "Open browser developer tools",
            PANEL_NAVIGATE_LEFT to "Switch to the left/previous panel",
            PANEL_NAVIGATE_RIGHT to "Switch to the right/next panel",
            PANEL_NAVIGATE_UP to "Switch to the previous panel (upward)",
            PANEL_NAVIGATE_DOWN to "Switch to the next panel (downward)",
            PANEL_SPLIT_VERTICAL to "Split current tab vertically",
            PANEL_SPLIT_HORIZONTAL to "Split current tab horizontally",
            QUICK_SWITCHER_OPEN to "Open quick switcher (Top of Mind)",
            WORKSPACE_SAVE to "Save the current workspace layout",
            EDITOR_SAVE to "Save the current file",
            EDITOR_SAVE_ALL to "Save all open files",
            EDITOR_FIND to "Open find dialog",
            EDITOR_REPLACE to "Open find and replace dialog",
            EDITOR_FIND_NEXT to "Find next occurrence",
            EDITOR_FIND_PREVIOUS to "Find previous occurrence",
            EDITOR_GO_TO_LINE to "Go to specific line number",
            CODEBASE_OPEN to "Open CodeBase panel",
            GLOBAL_SEARCH_OPEN to "Open global search (Double-Shift)",
            FOCUS_MODE_TOGGLE to "Toggle Focus Mode (hide/show UI bars)",
            SETTINGS_OPEN to "Open application settings",
            HELP_SHORTCUTS to "Show keyboard shortcuts help dialog",
            TEST_EXTERNAL_LINK to "Test external link handling (debug)",
        )

    /**
     * Category mapping for each action.
     */
    val categories =
        mapOf(
            WINDOW_NEW to Categories.WINDOW_MANAGEMENT,
            WINDOW_CLOSE to Categories.WINDOW_MANAGEMENT,
            TAB_NEW to Categories.TAB_MANAGEMENT,
            TAB_CLOSE to Categories.TAB_MANAGEMENT,
            TAB_NEXT to Categories.TAB_MANAGEMENT,
            TAB_PREVIOUS to Categories.TAB_MANAGEMENT,
            TAB_REOPEN_CLOSED to Categories.TAB_MANAGEMENT,
            TAB_NEXT_POSITIONAL to Categories.TAB_MANAGEMENT,
            TAB_PREVIOUS_POSITIONAL to Categories.TAB_MANAGEMENT,
            TAB_SELECT_1 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_2 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_3 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_4 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_5 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_6 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_7 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_8 to Categories.TAB_MANAGEMENT,
            TAB_SELECT_LAST to Categories.TAB_MANAGEMENT,
            BROWSER_RELOAD to Categories.BROWSER_CONTROLS,
            BROWSER_ZOOM_RESET to Categories.BROWSER_CONTROLS,
            BROWSER_ZOOM_IN to Categories.BROWSER_CONTROLS,
            BROWSER_ZOOM_OUT to Categories.BROWSER_CONTROLS,
            BROWSER_FIND to Categories.BROWSER_CONTROLS,
            BROWSER_BACK to Categories.BROWSER_CONTROLS,
            BROWSER_FORWARD to Categories.BROWSER_CONTROLS,
            BROWSER_DEVTOOLS to Categories.BROWSER_CONTROLS,
            PANEL_NAVIGATE_LEFT to Categories.NAVIGATION,
            PANEL_NAVIGATE_RIGHT to Categories.NAVIGATION,
            PANEL_NAVIGATE_UP to Categories.NAVIGATION,
            PANEL_NAVIGATE_DOWN to Categories.NAVIGATION,
            PANEL_SPLIT_VERTICAL to Categories.NAVIGATION,
            PANEL_SPLIT_HORIZONTAL to Categories.NAVIGATION,
            QUICK_SWITCHER_OPEN to Categories.NAVIGATION,
            WORKSPACE_SAVE to Categories.WORKSPACE,
            EDITOR_SAVE to Categories.EDITOR,
            EDITOR_SAVE_ALL to Categories.EDITOR,
            EDITOR_FIND to Categories.EDITOR,
            EDITOR_REPLACE to Categories.EDITOR,
            EDITOR_FIND_NEXT to Categories.EDITOR,
            EDITOR_FIND_PREVIOUS to Categories.EDITOR,
            EDITOR_GO_TO_LINE to Categories.EDITOR,
            CODEBASE_OPEN to Categories.TOOLS,
            GLOBAL_SEARCH_OPEN to Categories.SEARCH,
            FOCUS_MODE_TOGGLE to Categories.VIEW,
            SETTINGS_OPEN to Categories.VIEW,
            HELP_SHORTCUTS to Categories.HELP,
            TEST_EXTERNAL_LINK to Categories.DEBUG,
        )

    /**
     * Context mapping for each action.
     */
    val contexts =
        mapOf(
            WINDOW_NEW to ShortcutContext.GLOBAL,
            WINDOW_CLOSE to ShortcutContext.GLOBAL,
            TAB_NEW to ShortcutContext.GLOBAL,
            TAB_CLOSE to ShortcutContext.GLOBAL,
            TAB_NEXT to ShortcutContext.GLOBAL,
            TAB_PREVIOUS to ShortcutContext.GLOBAL,
            TAB_REOPEN_CLOSED to ShortcutContext.GLOBAL,
            TAB_NEXT_POSITIONAL to ShortcutContext.GLOBAL,
            TAB_PREVIOUS_POSITIONAL to ShortcutContext.GLOBAL,
            TAB_SELECT_1 to ShortcutContext.GLOBAL,
            TAB_SELECT_2 to ShortcutContext.GLOBAL,
            TAB_SELECT_3 to ShortcutContext.GLOBAL,
            TAB_SELECT_4 to ShortcutContext.GLOBAL,
            TAB_SELECT_5 to ShortcutContext.GLOBAL,
            TAB_SELECT_6 to ShortcutContext.GLOBAL,
            TAB_SELECT_7 to ShortcutContext.GLOBAL,
            TAB_SELECT_8 to ShortcutContext.GLOBAL,
            TAB_SELECT_LAST to ShortcutContext.GLOBAL,
            BROWSER_RELOAD to ShortcutContext.BROWSER,
            BROWSER_ZOOM_RESET to ShortcutContext.BROWSER,
            BROWSER_ZOOM_IN to ShortcutContext.BROWSER,
            BROWSER_ZOOM_OUT to ShortcutContext.BROWSER,
            BROWSER_FIND to ShortcutContext.BROWSER,
            BROWSER_BACK to ShortcutContext.BROWSER,
            BROWSER_FORWARD to ShortcutContext.BROWSER,
            BROWSER_DEVTOOLS to ShortcutContext.BROWSER,
            PANEL_NAVIGATE_LEFT to ShortcutContext.GLOBAL,
            PANEL_NAVIGATE_RIGHT to ShortcutContext.GLOBAL,
            PANEL_NAVIGATE_UP to ShortcutContext.GLOBAL,
            PANEL_NAVIGATE_DOWN to ShortcutContext.GLOBAL,
            PANEL_SPLIT_VERTICAL to ShortcutContext.GLOBAL,
            PANEL_SPLIT_HORIZONTAL to ShortcutContext.GLOBAL,
            QUICK_SWITCHER_OPEN to ShortcutContext.GLOBAL,
            WORKSPACE_SAVE to ShortcutContext.WORKSPACE,
            EDITOR_SAVE to ShortcutContext.EDITOR,
            EDITOR_SAVE_ALL to ShortcutContext.EDITOR,
            EDITOR_FIND to ShortcutContext.EDITOR,
            EDITOR_REPLACE to ShortcutContext.EDITOR,
            EDITOR_FIND_NEXT to ShortcutContext.EDITOR,
            EDITOR_FIND_PREVIOUS to ShortcutContext.EDITOR,
            EDITOR_GO_TO_LINE to ShortcutContext.EDITOR,
            CODEBASE_OPEN to ShortcutContext.GLOBAL,
            GLOBAL_SEARCH_OPEN to ShortcutContext.GLOBAL,
            FOCUS_MODE_TOGGLE to ShortcutContext.GLOBAL,
            SETTINGS_OPEN to ShortcutContext.GLOBAL,
            HELP_SHORTCUTS to ShortcutContext.GLOBAL,
            TEST_EXTERNAL_LINK to ShortcutContext.GLOBAL,
        )

    /**
     * Get all registered action IDs.
     */
    fun getAllActionIds(): List<String> =
        listOf(
            WINDOW_NEW,
            WINDOW_CLOSE,
            TAB_NEW,
            TAB_CLOSE,
            TAB_NEXT,
            TAB_PREVIOUS,
            TAB_REOPEN_CLOSED,
            TAB_NEXT_POSITIONAL,
            TAB_PREVIOUS_POSITIONAL,
            TAB_SELECT_1,
            TAB_SELECT_2,
            TAB_SELECT_3,
            TAB_SELECT_4,
            TAB_SELECT_5,
            TAB_SELECT_6,
            TAB_SELECT_7,
            TAB_SELECT_8,
            TAB_SELECT_LAST,
            BROWSER_RELOAD,
            BROWSER_ZOOM_RESET,
            BROWSER_ZOOM_IN,
            BROWSER_ZOOM_OUT,
            BROWSER_FIND,
            BROWSER_BACK,
            BROWSER_FORWARD,
            BROWSER_DEVTOOLS,
            PANEL_NAVIGATE_LEFT,
            PANEL_NAVIGATE_RIGHT,
            PANEL_NAVIGATE_UP,
            PANEL_NAVIGATE_DOWN,
            PANEL_SPLIT_VERTICAL,
            PANEL_SPLIT_HORIZONTAL,
            QUICK_SWITCHER_OPEN,
            WORKSPACE_SAVE,
            EDITOR_SAVE,
            EDITOR_SAVE_ALL,
            EDITOR_FIND,
            EDITOR_REPLACE,
            EDITOR_FIND_NEXT,
            EDITOR_FIND_PREVIOUS,
            EDITOR_GO_TO_LINE,
            CODEBASE_OPEN,
            GLOBAL_SEARCH_OPEN,
            FOCUS_MODE_TOGGLE,
            SETTINGS_OPEN,
            HELP_SHORTCUTS,
            TEST_EXTERNAL_LINK,
        )

    /**
     * Get description for an action ID.
     */
    fun getDescription(actionId: String): String = descriptions[actionId] ?: "Unknown action"

    /**
     * Get category for an action ID.
     */
    fun getCategory(actionId: String): String = categories[actionId] ?: Categories.TOOLS

    /**
     * Get context for an action ID.
     */
    fun getContext(actionId: String): ShortcutContext = contexts[actionId] ?: ShortcutContext.GLOBAL
}
