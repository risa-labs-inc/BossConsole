package ai.rever.boss

import ai.rever.boss.components.registery.TabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.window.Project

// Platform-specific download tab close callback setup
expect fun setupDownloadTabCloseCallback(splitViewState: SplitViewState)

// Platform-specific function to consume pending initial tab for a window
// Returns the TabInfo if there's a pending tab for this window, null otherwise
expect fun consumePendingInitialTab(windowId: String): TabInfo?

/**
 * Platform-specific function to consume pending initial project for a window.
 * When a window is created with a project via "Open in New Window", the project
 * is stored as pending and consumed here when the window initializes.
 *
 * @param windowId The window ID to get the pending project for
 * @return The pending Project if one exists, null otherwise
 */
expect fun consumePendingInitialProject(windowId: String): Project?
