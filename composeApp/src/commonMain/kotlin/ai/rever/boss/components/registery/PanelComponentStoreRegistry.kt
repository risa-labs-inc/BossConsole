package ai.rever.boss.components.registery

import ai.rever.boss.plugin.sandbox.PanelSandboxRegistry
import androidx.compose.runtime.mutableStateMapOf
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
    fun register(
        windowId: String,
        store: PanelComponentStore,
    ) {
        stores.update { it + (windowId to store) }
    }

    /** Unregister a window's store. Should be called when the window is closed. */
    fun unregister(windowId: String) {
        stores.update { it - windowId }
    }

    /** All currently registered stores, one per open window. */
    fun getAllStores(): Collection<PanelComponentStore> = stores.value.values

    /**
     * The store for [windowId], or null if that window isn't registered (or never was).
     *
     * The per-window counterpart of [getAllStores], mirroring
     * [ai.rever.boss.components.window_panel.SplitViewStateRegistry.getState] — for a caller that
     * has resolved one target window (e.g. [ai.rever.boss.utils.WindowFocusManager]) rather than
     * needing every window.
     */
    fun getStore(windowId: String): PanelComponentStore? = stores.value[windowId]

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

    /**
     * Plugins whose panels must not be composed right now.
     *
     * Snapshot state, and read from [PanelComponentStore.getOrCreateComponent] during
     * composition, so suspending recomposes the open slots that are showing the plugin instead of
     * leaving a stale component on screen.
     */
    private val suspendedPlugins = mutableStateMapOf<String, Unit>()

    /** Whether [pluginId]'s panels are currently suspended. */
    fun isSuspended(pluginId: String): Boolean = suspendedPlugins.containsKey(pluginId)

    /**
     * Take [pluginId]'s panels out of every window's composition and keep them out until
     * [resumePanels].
     *
     * The unload path's panel counterpart of closing its tabs, and for the same reason: a panel
     * left composed while the classloader closes runs its `onDispose` against a dead loader.
     * Closing tabs was never enough - nothing else removes a panel before the unload, so the
     * disposal wait could only expire (see PluginLoaderDelegateImpl.teardownPluginTabs).
     *
     * Suspending is what makes the removal stick: [SidePanel] and the panel-host tab both call
     * [PanelComponentStore.getOrCreateComponent] *during* composition, so a bare
     * `removeComponent` would be undone by the very next frame, which would re-instantiate the
     * plugin's component from the factories that are about to go away.
     *
     * Deliberately NOT hiding the slot. The panel stays open and empty for the length of the
     * unload, so a reload puts the new build back where the user left it - hiding it would need
     * the far side to guess which slots to reopen, and #856 is the bug that comes from guessing.
     *
     * MUST run on the UI thread, like [resetPanels]: it mutates snapshot state that composition
     * also reads.
     *
     * @return the number of cached components dropped, for logging.
     */
    fun detachPanels(pluginId: String): Int {
        suspendedPlugins[pluginId] = Unit
        var detached = 0
        getAllStores().forEach { store ->
            store.activeComponents.keys
                .filter { PanelSandboxRegistry.getSandbox(it)?.pluginId == pluginId }
                .forEach { panelId ->
                    store.removeComponent(panelId)
                    detached++
                }
        }
        return detached
    }

    /**
     * Let [pluginId]'s panels compose again.
     *
     * Every path that ends an unload calls this, whether or not the plugin came back: a suspension
     * that outlives its unload is a slot that stays blank for the rest of the session, which is a
     * worse failure than the stale component this replaced. See
     * PluginLoaderDelegateImpl.refreshPluginPanels, which resumes before it resets.
     */
    fun resumePanels(pluginId: String) {
        suspendedPlugins.remove(pluginId)
    }

    /** Test-only: drop every suspension. */
    fun clearSuspensions() {
        suspendedPlugins.clear()
    }
}
