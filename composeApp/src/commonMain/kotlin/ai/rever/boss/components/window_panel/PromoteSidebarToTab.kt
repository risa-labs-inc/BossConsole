package ai.rever.boss.components.window_panel

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.model.TabDropTarget
import ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo
import ai.rever.boss.components.registery.PanelComponentStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Performs a deferred "promote sidebar plugin to a main tab" request set on the
 * [BossDraggableComponent] (via the header's "Open as Tab" action or a drag-out onto
 * the central area). Mirrors `ProcessPendingPanelOpen`: the model records the request,
 * this composable — which has SplitView access — carries it out.
 *
 * Move semantics: opens the panel as a focused main tab (reusing the cached component
 * so state carries over), then hides it in the sidebar and marks it hosted-as-tab.
 */
@Composable
fun BossDraggableComponent.ProcessPendingPromoteToTab(
    splitViewState: SplitViewState,
    panelComponentStore: PanelComponentStore,
) {
    val request = pendingPromoteToTab
    LaunchedEffect(request) {
        if (request != null) {
            val panelId = request.panelId
            val info = panelComponentStore.getOrCreateComponent(panelId)?.panelInfo
            if (info != null) {
                // Creating the tab marks the panel hosted (PanelHostTabComponent.init).
                val tabInfo = PanelHostTabInfo(panelId, info.displayName, info.icon)
                when (val target = request.target) {
                    // Drag-out onto a panel edge → create a split holding the plugin tab.
                    is TabDropTarget.SplitPanel ->
                        splitViewState.splitPanel(target.panelId, target.orientation, tabToMove = tabInfo)
                    // Drag-out onto a panel center → add to that panel's tab bar.
                    is TabDropTarget.ExistingPanel -> {
                        splitViewState.getPanelTabsComponent(target.panelId)?.let { tabs ->
                            val index = tabs.addTab(tabInfo)
                            if (index >= 0) tabs.selectTab(index)
                            splitViewState.setActivePanel(target.panelId)
                        }
                    }
                    // No specific target (menu "Open as Tab") → the active panel.
                    else -> splitViewState.getActiveTabsComponent()?.let { tabs ->
                        val index = tabs.addTab(tabInfo)
                        if (index >= 0) tabs.selectTab(index)
                    }
                }
                // Move: collapse the panel in the sidebar.
                closePanelForItem(panelId.panelId)
            }
            clearPendingPromoteToTab()
        }
    }
}

/**
 * Focuses the existing main tab that hosts a sidebar plugin, when its sidebar icon is
 * clicked while the plugin is already open as a tab (request set via [requestFocusHostedTab]).
 * The single-instance invariant means at most one such tab exists, so the scan stops at
 * the first match.
 */
@Composable
fun BossDraggableComponent.ProcessPendingFocusHostedTab(
    splitViewState: SplitViewState,
) {
    val pending = pendingFocusHostedTab
    LaunchedEffect(pending) {
        if (pending != null) {
            var targetPanelId: String? = null
            var targetTabId: String? = null
            for (panel in splitViewState.getAllPanels()) {
                val match = panel.tabsComponent.tabsState.value.tabs
                    .firstOrNull { it is PanelHostTabInfo && it.panelId == pending }
                if (match != null) {
                    targetPanelId = panel.id
                    targetTabId = match.id
                    break
                }
            }
            if (targetPanelId != null && targetTabId != null) {
                splitViewState.setActivePanel(targetPanelId)
                splitViewState.selectTabInPanel(targetTabId, targetPanelId)
            }
            clearPendingFocusHostedTab()
        }
    }
}
