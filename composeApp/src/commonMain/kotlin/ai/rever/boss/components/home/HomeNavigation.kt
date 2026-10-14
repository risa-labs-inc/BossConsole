package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import java.util.UUID

internal fun isHomeTab(tab: TabInfo?): Boolean = tab is FluckTabInfo && FluckTabInfo.isHomeUrl(tab.currentUrl)

/** Navigate within this window's current pane without consuming a pending New Tab split. */
internal fun goHome(
    splitView: SplitViewState,
    registry: TabRegistry,
): String? {
    val panel = splitView.getPanel(splitView.activePanelId) ?: splitView.getAllPanels().firstOrNull() ?: return null
    val component = panel.tabsComponent
    val tabs = component.tabsState.value
    val existing =
        tabs.activeIndex.takeIf { isHomeTab(tabs.tabs.getOrNull(it)) }
            ?: tabs.tabs.indexOfFirst(::isHomeTab).takeIf { it >= 0 }
    val index =
        when {
            tabs.tabs.isEmpty() -> {
                null
            }

            // An empty pane already renders Home.
            existing != null -> {
                existing
            }

            !registry.isRegistered(FluckTabType.typeId) -> {
                null
            }

            else -> {
                component
                    .addTab(
                        FluckTabInfo(
                            id = "home-${UUID.randomUUID()}",
                            typeId = FluckTabType.typeId,
                            _title = FluckTabInfo.HOME_TITLE,
                            url = "about:blank",
                        ),
                    ).takeIf { it >= 0 }
            }
        }
    return if (tabs.tabs.isNotEmpty() && index == null) {
        null
    } else {
        index?.let(component::selectTab)
        splitView.setActivePanel(panel.id)
        panel.id
    }
}
