package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.plugin.api.TabInfo

/** Resolve delayed dialog requests against panes that still exist at creation time. */
internal fun placeNewTab(
    splitViewState: SplitViewState,
    tab: TabInfo,
    sourcePanelId: String?,
    fallbackTabs: BossTabsComponent,
) {
    val split = splitViewState.consumePendingSplit()
    if (split != null) {
        val target =
            splitViewState.findPanel(split.panelId)
                ?: sourcePanelId?.let(splitViewState::findPanel)
                ?: splitViewState.findPanel(splitViewState.activePanelId)
                ?: splitViewState.getAllPanels().firstOrNull()
        if (target != null) {
            splitViewState.splitPanel(
                target.id,
                split.direction.orientation,
                tabToMove = tab,
                placeBefore = split.direction.placeBefore,
            )
            return
        }
    }
    val target =
        sourcePanelId?.let { splitViewState.findPanel(it)?.tabsComponent }
            ?: splitViewState.getActiveTabsComponent()
            ?: splitViewState.getLastInteractedTabComponent()
            ?: fallbackTabs
    target.addTab(tab)
}
