package ai.rever.boss.components.plugin.tab_types

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.window_panel.components.side_panel.RenderPanelContent
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext

private val panelHostTabLogger = BossLogger.forComponent("PanelHostTab")

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

/**
 * The panel a panel-host tab renders, or null when [config] did not come from the host.
 *
 * `SplitViewOperations.openTab` resolves a factory purely by [TabInfo.typeId], so any plugin
 * can hand this one a [TabInfo] of its own carrying [PanelHostTabType]'s id — and until
 * `openPanelAsTab` existed, one had a reason to try. Reading the panel id with a checked cast
 * instead of `as` is what keeps that a dead tab and a log line rather than a
 * ClassCastException thrown from inside the host. Top-level so the rule is testable without a
 * ComponentContext or a PanelComponentStore.
 */
fun panelIdFor(config: TabInfo): PanelId? = (config as? PanelHostTabInfo)?.panelId

class PanelHostTabComponent(
    override val config: TabInfo,
    componentContext: ComponentContext,
    private val store: PanelComponentStore,
    private val draggable: BossDraggableComponent,
) : TabComponentWithUI,
    ComponentContext by componentContext {
    override val tabTypeInfo: TabTypeInfo = PanelHostTabType

    private val panelId: PanelId? = panelIdFor(config)

    init {
        // One more live tab instance hosting this panel.
        if (panelId != null) {
            draggable.markHostedAsTab(panelId)
        } else {
            panelHostTabLogger.warn(
                LogCategory.UI,
                "Ignoring a panel-host tab whose config is not a PanelHostTabInfo",
                mapOf("tabId" to config.id, "configClass" to config::class.java.name),
            )
        }
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
        // Nothing was marked for a config we could not read, so nothing to unmark.
        panelId?.let { draggable.unmarkHostedAsTab(it) }
    }

    @Composable
    override fun Content() {
        val id = panelId ?: return
        val component = store.getOrCreateComponent(id)
        RenderPanelContent(component = component, panelId = id)
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
