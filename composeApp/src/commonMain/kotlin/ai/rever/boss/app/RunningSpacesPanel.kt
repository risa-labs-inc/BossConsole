package ai.rever.boss.app

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.window_panel.SplitViewState
import kotlinx.coroutines.launch

/** Only a successful title-bar selection with several running Spaces opens the installed panel. */
internal fun openRunningSpacesPanel(
    state: BossAppState,
    splitViewState: SplitViewState,
) {
    if (splitViewState.liveWorkspaceIds.size <= 1) return
    val panel = state.panelRegistry.getAllPanels().firstOrNull { it.id.panelId == "top-of-mind" } ?: return
    state.coroutineScope.launch { PanelEventBus.openPanel(panel.id, state.windowId) }
}
