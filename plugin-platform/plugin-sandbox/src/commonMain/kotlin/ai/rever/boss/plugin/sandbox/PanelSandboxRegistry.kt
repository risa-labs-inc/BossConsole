package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.api.PanelId
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry that tracks which panels are managed by which sandboxes.
 *
 * This allows the panel rendering code to look up the sandbox for a panel
 * and wrap the content with an error boundary.
 *
 * Thread-safe: uses [ConcurrentHashMap] since registration happens on plugin
 * loading threads while lookups happen on the composition thread.
 */
object PanelSandboxRegistry {
    private val panelToSandbox = ConcurrentHashMap<String, PluginSandbox>()

    /**
     * Register a panel with its managing sandbox.
     *
     * @param panelId The panel ID
     * @param sandbox The sandbox managing this panel
     */
    fun register(
        panelId: PanelId,
        sandbox: PluginSandbox,
    ) {
        panelToSandbox[panelId.panelId] = sandbox
    }

    /**
     * Unregister a panel.
     *
     * @param panelId The panel ID to unregister
     */
    fun unregister(panelId: PanelId) {
        panelToSandbox.remove(panelId.panelId)
    }

    /**
     * Get the sandbox for a panel.
     *
     * @param panelId The panel ID
     * @return The managing sandbox, or null if not sandboxed
     */
    fun getSandbox(panelId: PanelId): PluginSandbox? = panelToSandbox[panelId.panelId]

    /**
     * Get the sandbox for a panel by its string ID.
     *
     * @param panelIdString The panel ID string
     * @return The managing sandbox, or null if not sandboxed
     */
    fun getSandbox(panelIdString: String): PluginSandbox? = panelToSandbox[panelIdString]

    /**
     * Clear all registrations. Used for testing or cleanup.
     */
    fun clear() {
        panelToSandbox.clear()
    }
}
