package ai.rever.boss.components.registery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Global registry of each window's [PanelComponentStore] — the panel
 * counterpart of [ai.rever.boss.components.window_panel.SplitViewStateRegistry].
 *
 * An open sidebar panel keeps its already-instantiated component cached in its
 * window's store, so a plugin reload that swaps the registry factories is
 * invisible to it (and the stale component pins the pre-reload classloader).
 * This registry lets the plugin (re)install path reach every window's store
 * and reset the affected slots (issue #856).
 */
object PanelComponentStoreRegistry {
    private val stores = MutableStateFlow<Map<String, PanelComponentStore>>(emptyMap())

    /** Register a window's store. Should be called when the window is created. */
    fun register(windowId: String, store: PanelComponentStore) {
        stores.update { it + (windowId to store) }
    }

    /** Unregister a window's store. Should be called when the window is closed. */
    fun unregister(windowId: String) {
        stores.update { it - windowId }
    }

    /** All currently registered stores, one per open window. */
    fun getAllStores(): Collection<PanelComponentStore> {
        return stores.value.values
    }

    /**
     * Reset every OPEN slot showing one of [panelIds], across all windows;
     * slots showing other panels are left untouched. Returns the number of
     * slots reset.
     *
     * MUST run on the UI thread: [PanelComponentStore.activeComponents] is
     * snapshot state also mutated by composition, and resets invoke plugin
     * panel factories — both race an off-UI caller. The production caller
     * (PluginLoaderDelegateImpl.refreshPluginPanels) dispatches via
     * SwingUtilities.invokeLater; do the same.
     */
    fun resetPanels(panelIds: Set<PanelId>): Int {
        var reset = 0
        getAllStores().forEach { store ->
            store.activeComponents.keys.filter { it in panelIds }.forEach { panelId ->
                if (store.resetComponent(panelId)) reset++
            }
        }
        return reset
    }
}
