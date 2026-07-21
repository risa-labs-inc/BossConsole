package ai.rever.boss.components.plugin.providers

import ai.rever.boss.cache.loadFaviconFromCache
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.topofmind.ActiveTab
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import androidx.compose.runtime.getValue

/**
 * Singleton provider for TopOfMind panel data.
 * Must be initialized in BossApp before TopOfMind panel is used.
 */
object TopOfMindDataProvider {
    private var splitViewState: SplitViewState? = null
    private var workspaceManager: WorkspaceManager? = null
    private var windowId: String = "unknown"

    /**
     * Initialize the provider with window-specific state.
     * Called from BossApp when setting up CompositionLocalProvider.
     */
    fun initialize(
        splitViewState: SplitViewState,
        workspaceManager: WorkspaceManager,
        windowId: String
    ) {
        this.splitViewState = splitViewState
        this.workspaceManager = workspaceManager
        this.windowId = windowId
    }

    /**
     * Clear the provider state.
     * Called when window is disposed.
     */
    fun clear() {
        splitViewState = null
        workspaceManager = null
        windowId = "unknown"
    }

    /**
     * Collect all active tabs from the current split view state.
     */
    fun collectAllActiveTabs(): List<ActiveTab> {
        return splitViewState?.collectAllActiveTabs(workspaceManager, windowId) ?: emptyList()
    }

    /**
     * Get all panel states for reactivity tracking.
     * Returns list of (panelId, tabCount, tabIdentities) triples.
     */
    @Composable
    fun getAllPanelStates(): List<Triple<String, Int, List<String>>> {
        val state = splitViewState ?: return emptyList()
        val allPanels = state.getAllPanels()
        return allPanels.map { panel ->
            val tabsState by panel.tabsComponent.tabsState.subscribeAsState()
            Triple(
                panel.id,
                tabsState.tabs.size,
                tabsState.tabs.map { tab -> tab.id + tab.title }
            )
        }
    }

    /**
     * Load favicon from cache.
     */
    @Composable
    fun loadFavicon(cacheKey: String?): TabIcon.Image? {
        return loadFaviconFromCache(cacheKey)
    }

    /**
     * Get URL from an ActiveTab if it's a FluckTabInfo.
     */
    fun getTabUrl(activeTab: ActiveTab): String? {
        return (activeTab.tabInfo as? FluckTabInfo)?.currentUrl
    }

    /**
     * Get favicon cache key from an ActiveTab if it's a FluckTabInfo.
     */
    fun getFaviconCacheKey(activeTab: ActiveTab): String? {
        return (activeTab.tabInfo as? FluckTabInfo)?.faviconCacheKey
    }

    /**
     * Get fallback icon for an ActiveTab.
     */
    fun getFallbackIcon(activeTab: ActiveTab): ImageVector {
        return activeTab.tabInfo.icon
    }
}
