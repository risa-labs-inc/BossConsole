package ai.rever.boss

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowManager

private val bossAppLogger = BossLogger.forComponent("DesktopBossApp")

/**
 * Desktop-specific implementation for setting up download tab close callback.
 * Called when BossApp initializes on desktop platform.
 */
actual fun setupDownloadTabCloseCallback(splitViewState: SplitViewState) {
    FluckEngine.setCloseMostRecentTabCallback {
        bossAppLogger.debug(LogCategory.UI, "Received request to close most recent tab")
        // Close most recent tab in all panels
        splitViewState.getAllPanels().forEach { panel ->
            val tabsComp = splitViewState.getPanelTabsComponent(panel.id)
            tabsComp?.closeMostRecentTab()
        }
    }
}

/**
 * Desktop-specific implementation for consuming pending initial tab for a window.
 * When a window is created via "Open in New Window", the tab is stored as pending
 * and consumed here when the window's BossApp initializes.
 *
 * @param windowId The window ID to get the pending tab for
 * @return The pending TabInfo if one exists, null otherwise
 */
actual fun consumePendingInitialTab(windowId: String): TabInfo? = WindowManager.consumePendingTab(windowId)

/**
 * Desktop-specific implementation for consuming pending initial project for a window.
 * When a window is created with a project via "Open in New Window", the project
 * is stored as pending and consumed here when the window's BossApp initializes.
 *
 * @param windowId The window ID to get the pending project for
 * @return The pending Project if one exists, null otherwise
 */
actual fun consumePendingInitialProject(windowId: String): Project? = WindowManager.consumePendingProject(windowId)
