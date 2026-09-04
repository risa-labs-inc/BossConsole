package ai.rever.boss.components.registery

import ai.rever.boss.plugin.sandbox.PanelSandboxRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume

class PanelComponentStore(
    private val registry: PanelRegistry,
) {
    private val logger = BossLogger.forComponent("PanelComponentStore")
    private val panelLifecycles = mutableMapOf<PanelId, LifecycleRegistry>()

    // Map of active components by panel ID
    val activeComponents: SnapshotStateMap<PanelId, PanelComponentWithUI> = mutableStateMapOf()

    // Get or create a component for a panel
    fun getOrCreateComponent(panelId: PanelId): PanelComponentWithUI? {
        // Nothing for a plugin that is mid-unload. Called from composition by both panel hosts
        // (SidePanel and the panel-host tab), so this is also what keeps a detached panel
        // detached: without it the next frame would re-instantiate the component from factories
        // that are about to be closed. See PanelComponentStoreRegistry.detachPanels.
        val owner = PanelSandboxRegistry.getSandbox(panelId)?.pluginId
        if (owner != null && PanelComponentStoreRegistry.isSuspended(owner)) return null

        // Return existing component if available
        activeComponents[panelId]?.let { return it }

        val component = createComponent(panelId) ?: return null

        // Store and return
        activeComponents[panelId] = component
        return component
    }

    // Remove a component when panel is closed
    fun removeComponent(panelId: PanelId) {
        activeComponents.remove(panelId)
        destroyLifecycle(panelId, panelLifecycles.remove(panelId))
    }

    // Destroys every active panel lifecycle when the owning window closes.
    // Must run on the UI thread because activeComponents is Compose snapshot state.
    fun dispose() {
        val lifecycles = panelLifecycles.toList()
        activeComponents.clear()
        panelLifecycles.clear()

        lifecycles.forEach { (panelId, lifecycle) ->
            destroyLifecycle(panelId, lifecycle)
        }
    }

    /**
     * Reset a panel by destroying and recreating its component.
     *
     * This method implements the "component recreation" reset strategy:
     * 1. Call onBeforeReset() on the current component for cleanup
     * 2. Remove the old component and destroy its lifecycle
     * 3. Create a fresh component instance
     * 4. Call onInitialized() on the new component
     * 5. Store and activate the new component
     *
     * This ensures the panel starts with completely fresh state,
     * as if it were just opened for the first time.
     *
     * All in-memory data will be lost during reset. Components that need
     * to persist data should save it to persistent storage before reset.
     *
     * @param panelId The panel to reset
     * @return true if reset was successful, false if panel doesn't exist
     */
    fun resetComponent(panelId: PanelId): Boolean {
        // Get current component
        val currentComponent = activeComponents[panelId]
        if (currentComponent == null) {
            logger.warn(LogCategory.UI, "Cannot reset panel - not active", mapOf("panelId" to panelId.panelId))
            return false
        }

        logger.debug(LogCategory.UI, "Resetting panel", mapOf("panelId" to panelId.panelId))

        // Cleanup hook on the OLD component, isolated so its failure can't keep
        // the stale instance cached: after a plugin hot reload this calls into a
        // closed classloader, where even class resolution throws an Error.
        try {
            currentComponent.onBeforeReset()
        } catch (t: Throwable) {
            logger.warn(LogCategory.UI, "onBeforeReset failed during panel reset (continuing)", mapOf("panelId" to panelId.panelId), t)
        }

        // Remove the old component and notify its lifecycle subscribers.
        removeComponent(panelId)

        try {
            val newComponent = createComponent(panelId)
            if (newComponent == null) {
                logger.warn(
                    LogCategory.UI,
                    "Failed to create new component",
                    mapOf("panelId" to panelId.panelId),
                )
                return false
            }

            // Call initialization hook
            newComponent.onInitialized()

            // Store and activate new component
            activeComponents[panelId] = newComponent

            logger.info(LogCategory.UI, "Successfully reset panel", mapOf("panelId" to panelId.panelId))
            return true
        } catch (t: Throwable) {
            destroyLifecycle(panelId, panelLifecycles.remove(panelId))
            logger.error(LogCategory.UI, "Error resetting panel", mapOf("panelId" to panelId.panelId), error = t)
            return false
        }
    }

    private fun createComponent(panelId: PanelId): PanelComponentWithUI? {
        val lifecycle = LifecycleRegistry()
        val component =
            registry.createComponent(
                panelId,
                DefaultComponentContext(lifecycle),
            ) ?: return null

        // LifecycleRegistry starts below CREATED. Advancing it ensures that
        // destroy() delivers onDestroy to the component's subscribers.
        lifecycle.resume()
        panelLifecycles[panelId] = lifecycle
        return component
    }

    private fun destroyLifecycle(
        panelId: PanelId,
        lifecycle: LifecycleRegistry?,
    ) {
        if (lifecycle == null) return

        try {
            lifecycle.destroy()
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.UI,
                "Panel lifecycle destroy failed (continuing)",
                mapOf("panelId" to panelId.panelId),
                t,
            )
        }
    }
}
