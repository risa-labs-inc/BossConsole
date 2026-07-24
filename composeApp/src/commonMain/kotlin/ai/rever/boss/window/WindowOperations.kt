package ai.rever.boss.window

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.window.Project

/**
 * Platform-specific window operations
 *
 * Provides cross-platform API for window management operations.
 * Desktop platforms implement full multi-window support, while mobile/web
 * platforms may provide fallback behavior.
 */
expect object WindowOperations {
    /**
     * Open a tab in a new window
     *
     * On desktop: Creates a new window and moves the tab there
     * On mobile/web: May open in a new view or ignore
     *
     * @param tabInfo The tab to open in a new window
     */
    fun openTabInNewWindow(tabInfo: TabInfo)

    /**
     * Check if multi-window support is available on this platform
     *
     * @return true if the platform supports multiple windows
     */
    fun isMultiWindowSupported(): Boolean

    /**
     * Close window if it has no tabs
     *
     * On desktop: Closes the window if all panels are empty
     * On mobile/web: May do nothing
     *
     * @param windowId The window ID to potentially close
     */
    fun closeWindowIfEmpty(windowId: String)

    /**
     * Create a new empty window
     *
     * On desktop: Creates a new window instance
     * On mobile/web: May do nothing
     */
    fun createNewWindow()

    /**
     * Create a new window with an initial project
     *
     * On desktop: Creates a new window and opens the project in it
     * On mobile/web: May do nothing
     *
     * @param project The project to open in the new window
     */
    fun createNewWindowWithProject(project: Project)

    /**
     * Force close a window by ID
     *
     * On desktop: Closes the window immediately regardless of tab count
     * On mobile/web: May do nothing
     *
     * @param windowId The window ID to close
     */
    fun closeWindow(windowId: String)
}
