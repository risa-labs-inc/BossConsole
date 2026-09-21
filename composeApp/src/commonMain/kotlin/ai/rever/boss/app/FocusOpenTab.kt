package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig

/** Focus a live tab in this window, including a Space running behind the current one. */
internal fun SplitViewState.focusOpenTab(
    tabId: String,
    workspaceManager: WorkspaceManager,
): Boolean = focusOpenTab(tabId, workspaceManager.workspaces.value, workspaceManager::loadWorkspace)

/**
 * Resolve the tab at click time: a search result's panel may have moved or its Space may have
 * changed. Panel ids repeat between Spaces, so panel existence alone cannot identify the target.
 * Switching restores live components, never a saved layout or its startup commands.
 */
@Suppress("ReturnCount")
internal fun SplitViewState.focusOpenTab(
    tabId: String,
    savedSpaces: List<LayoutWorkspace>,
    onWorkspaceFocused: (LayoutWorkspace) -> Unit,
): Boolean {
    // A fresh window may have tabs before it has a Space id. Match the tab, not the pane id.
    getAllPanels()
        .firstOrNull { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .any { it.id == tabId }
        }?.let { panel ->
            selectTabInPanel(tabId, panel.id)
            return true
        }
    val location = findTabLocation(tabId) ?: return false

    val liveTabs = collectAllActiveTabs()

    fun nameFor(id: String?): String =
        savedSpaces.firstOrNull { it.id == id }?.name
            ?: liveTabs.firstOrNull { it.workspaceId == id }?.workspaceName.orEmpty()

    val target =
        savedSpaces.firstOrNull { it.id == location.workspaceId }
            ?: LayoutWorkspace(
                id = location.workspaceId,
                name = nameFor(location.workspaceId),
                description = "",
                layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
            )
    val leavingName = nameFor(currentWorkspaceId)
    // Select while preserved so restoration brings both its tab and its pane into view.
    if (!selectTabAnywhere(tabId)) return false
    if (!switchToLiveWorkspace(location.workspaceId, leavingName)) return false
    onWorkspaceFocused(target)
    return true
}
