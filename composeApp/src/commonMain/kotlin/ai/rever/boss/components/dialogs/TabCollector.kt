package ai.rever.boss.components.dialogs

import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.topofmind.TopOfMindStateHolder

/**
 * Utility for collecting tabs from all windows.
 *
 * Used by both TopOfMindDialog and GlobalSearchDialog to ensure
 * consistent tab collection logic across the application.
 */
object TabCollector {

    /**
     * Collect all active tabs from all open windows.
     *
     * @param workspaceManager The workspace manager for resolving workspace info
     * @return List of all active tabs across all windows
     */
    fun collectAllTabs(workspaceManager: WorkspaceManager): List<ActiveTab> {
        val allWindowStates = SplitViewStateRegistry.getAllStates()
        val allTabs = mutableListOf<ActiveTab>()

        allWindowStates.forEach { (windowId, state) ->
            val windowTabs = state.collectAllActiveTabs(workspaceManager, windowId)
            allTabs.addAll(windowTabs)
        }

        return allTabs
    }

    /**
     * Refresh the global TopOfMindStateHolder with tabs from all windows.
     *
     * @param workspaceManager The workspace manager for resolving workspace info
     */
    fun refreshGlobalState(workspaceManager: WorkspaceManager) {
        val tabs = collectAllTabs(workspaceManager)
        TopOfMindStateHolder.updateActiveTabs(tabs)
    }
}
