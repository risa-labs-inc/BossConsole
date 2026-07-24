package ai.rever.boss.components.plugin.tab_types

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.window_panel.components.side_panel.RenderPanelContent
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext

/**
 * Host-internal tab type that renders a sidebar plugin's panel inside a main tab.
 *
 * Used by the "Open as Tab" action / header drag-out. The tab reuses the SAME cached
 * panel component instance from [PanelComponentStore], so state carries over (move
 * semantics) and the panel is composed in exactly one place at a time.
 */
object PanelHostTabType : TabTypeInfo {
    override val typeId: TabTypeId = TabTypeId("panel-host")
    override val displayName: String = "Panel"
    override val icon: ImageVector = Icons.Outlined.Tab
}

data class PanelHostTabInfo(
    val panelId: PanelId,
    override val title: String,
    override val icon: ImageVector,
) : TabInfo {
    override val id: String = "panel-tab:${panelId.panelId}"
    override val typeId: TabTypeId = PanelHostTabType.typeId
}

class PanelHostTabComponent(
    override val config: TabInfo,
    componentContext: ComponentContext,
    private val store: PanelComponentStore,
    private val draggable: BossDraggableComponent,
) : TabComponentWithUI,
    ComponentContext by componentContext {
    override val tabTypeInfo: TabTypeInfo = PanelHostTabType

    private val panelId: PanelId = (config as PanelHostTabInfo).panelId

    init {
        // One more live tab instance hosting this panel.
        draggable.markHostedAsTab(panelId)
    }

    /**
     * Called by [ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent.removeTab]
     * when this tab is closed. Kept as an explicit close signal (rather than a lifecycle
     * observer) on purpose: the hosted panel component is owned by [PanelComponentStore],
     * not by this tab, and a cross-panel move transfers this component live via
     * detachTab/adoptTab — no close happens, so the hosted-as-tab count must not change.
     * When the last hosting tab closes, the sidebar icon reopens the plugin in its
     * sidebar location.
     */
    fun onClosed() {
        draggable.unmarkHostedAsTab(panelId)
    }

    @Composable
    override fun Content() {
        val component = store.getOrCreateComponent(panelId)
        RenderPanelContent(component = component, panelId = panelId)
    }
}

/**
 * Register the panel-host tab type on [tabRegistry]. Call once per window after the
 * [PanelComponentStore] and [BossDraggableComponent] are created.
 */
fun ai.rever.boss.plugin.api.TabRegistry.registerPanelHostTab(
    store: PanelComponentStore,
    draggable: BossDraggableComponent,
) {
    if (isRegistered(PanelHostTabType.typeId)) return
    registerTabType(PanelHostTabType) { config, ctx ->
        PanelHostTabComponent(config, ctx, store, draggable)
    }
}
