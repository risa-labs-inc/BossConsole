package ai.rever.boss.components.registery

import ai.rever.boss.plugin.sandbox.PanelSandboxRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume

class PanelComponentStore(
    private val rootContext: ComponentContext,
    private val registry: PanelRegistry,
) {
    private val logger = BossLogger.forComponent("PanelComponentStore")

    // Map of active components by panel ID
    val activeComponents: SnapshotStateMap<PanelId, PanelComponentWithUI> = mutableStateMapOf()

    // Per-panel lifecycle registries. Each panel component gets its own ComponentContext whose
    // lifecycle is destroyed when the panel is closed or reset, so components that clean up in
    // lifecycle.doOnDestroy (e.g. plugin panels disposing CoroutineScopes, stopping poll loops)
    // actually get destroyed. Previously all panel components shared the window's root context,
    // whose LifecycleRegistry is never destroyed — closing a panel leaked every resource the
    // component registered in doOnDestroy (issue #213).
    //
    // Mirrors BossTabsComponent.tabLifecycles for tabs. Plain map on purpose: all panel
    // mutations happen on the UI thread (see PanelComponentStoreRegistry KDoc), matching
    // Essenty's lifecycle threading expectations.
    private val panelLifecycles = mutableMapOf<PanelId, LifecycleRegistry>()

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

        // Create a per-panel lifecycle so doOnDestroy callbacks actually fire on close/reset.
        val panelLifecycle = LifecycleRegistry()
        val panelContext = DefaultComponentContext(panelLifecycle)

        // Create new component with its own context (not the shared root)
        val component = registry.createComponent(panelId, panelContext) ?: return null

        // Drive the lifecycle to RESUMED: subscribers added in the component's init get
        // their up-callbacks replayed, and destroy() from CREATED would otherwise be a
        // silent no-op (Essenty only fires onDestroy from CREATED or above).
        panelLifecycle.resume()

        // Store and return
        panelLifecycles[panelId] = panelLifecycle
        activeComponents[panelId] = component
        return component
    }

    // Remove a component when panel is closed
    fun removeComponent(panelId: PanelId) {
        activeComponents.remove(panelId)
        // Destroy the panel's own lifecycle so components that clean up in
        // lifecycle.doOnDestroy (plugin panels disposing scopes, stopping pollers)
        // release their resources — without this a closed panel's callbacks are
        // dead code and every close/reopen stacks another set of invisible workers.
        panelLifecycles.remove(panelId)?.destroy()
    }

    /**
     * Reset a panel by destroying and recreating its component.
     *
     * This method implements the "component recreation" reset strategy:
     * 1. Call onBeforeReset() on the current component for cleanup
     * 2. Destroy the outgoing component's per-panel lifecycle (fires its doOnDestroy)
     * 3. Remove component from activeComponents
     * 4. Create a fresh component instance with a new per-panel lifecycle
     * 5. Call onInitialized() on the new component
     * 6. Store and activate the new component
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

        // Destroy the outgoing panel's lifecycle so its doOnDestroy callbacks fire.
        // Done after onBeforeReset so the existing hook keeps its current meaning and
        // ordering (onBeforeReset = "save state before I go away", doOnDestroy =
        // "release resources now that I'm gone"). Isolated: a throwing doOnDestroy
        // subscriber must not prevent the reset from continuing.
        try {
            panelLifecycles.remove(panelId)?.destroy()
        } catch (t: Throwable) {
            logger.warn(LogCategory.UI, "lifecycle destroy failed during panel reset (continuing)", mapOf("panelId" to panelId.panelId), t)
        }

        // Remove from active components
        activeComponents.remove(panelId)

        try {
            // Create a fresh per-panel lifecycle for the new component
            val panelLifecycle = LifecycleRegistry()
            val panelContext = DefaultComponentContext(panelLifecycle)

            // Create new component instance
            val newComponent = registry.createComponent(panelId, panelContext)
            if (newComponent == null) {
                logger.warn(LogCategory.UI, "Failed to create new component", mapOf("panelId" to panelId.panelId))
                return false
            }

            // Drive the lifecycle to RESUMED (same contract as getOrCreateComponent)
            panelLifecycle.resume()

            // Call initialization hook
            newComponent.onInitialized()

            // Store and activate new component
            panelLifecycles[panelId] = panelLifecycle
            activeComponents[panelId] = newComponent

            logger.info(LogCategory.UI, "Successfully reset panel", mapOf("panelId" to panelId.panelId))
            return true
        } catch (t: Throwable) {
            logger.error(LogCategory.UI, "Error resetting panel", mapOf("panelId" to panelId.panelId), error = t)
            return false
        }
    }

    /**
     * Destroy all panel lifecycles in this store. Called on window close so every
     * open panel's doOnDestroy callbacks fire — mirrors
     * BossTabsComponent.disposeAllTabsBlocking for tabs.
     */
    fun disposeAll() {
        panelLifecycles.values.toList().forEach { it.destroy() }
        panelLifecycles.clear()
        activeComponents.clear()
    }
}
