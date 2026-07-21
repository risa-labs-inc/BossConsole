package ai.rever.boss.window

import ai.rever.boss.window.Project
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Desktop implementation of window operations
 *
 * Provides full multi-window support using WindowManager
 */
actual object WindowOperations {
    private val logger = BossLogger.forComponent("WindowOperations")

    /**
     * Open a tab in a new window
     *
     * Creates a new window and stores the tab info as a pending initial tab.
     * The new window's BossApp will consume the pending tab during initialization.
     *
     * @param tabInfo The tab to open in the new window
     */
    actual fun openTabInNewWindow(tabInfo: TabInfo) {
        logger.debug(LogCategory.UI, "Creating new window with tab", mapOf("title" to tabInfo.title))
        WindowManager.createNewWindowWithTab(tabInfo)
    }

    /**
     * Desktop platforms support multiple windows
     *
     * @return Always returns true for desktop
     */
    actual fun isMultiWindowSupported(): Boolean = true

    /**
     * Close window if it has no tabs
     *
     * Closes the window if all panels are empty
     *
     * @param windowId The window ID to potentially close
     */
    @Deprecated("Tabs are managed by BossApp/SplitViewState, not WindowManager")
    @Suppress("DEPRECATION")
    actual fun closeWindowIfEmpty(windowId: String) {
        WindowManager.closeWindowIfEmpty(windowId)
    }

    /**
     * Create a new empty window
     */
    actual fun createNewWindow() {
        WindowManager.createNewWindow()
    }

    /**
     * Create a new window with an initial project
     *
     * Creates a new window and stores the project as a pending initial project.
     * The new window's BossApp will consume the pending project during initialization.
     *
     * @param project The project to open in the new window
     */
    actual fun createNewWindowWithProject(project: Project) {
        logger.debug(LogCategory.UI, "Creating new window with project", mapOf("name" to project.name))
        WindowManager.createNewWindowWithProject(project)
    }

    /**
     * Force close a window by ID
     *
     * @param windowId The window ID to close
     */
    actual fun closeWindow(windowId: String) {
        WindowManager.closeWindow(windowId)
    }
}
