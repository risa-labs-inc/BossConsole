package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.api.TabTypeId
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry that tracks which tab types are managed by which sandboxes.
 *
 * This allows the tab rendering code to look up the sandbox for a tab
 * and wrap the content with an error boundary.
 *
 * Thread-safe: uses [ConcurrentHashMap] since registration happens on plugin
 * loading threads while lookups happen on the composition thread.
 */
object TabSandboxRegistry {

    private val tabTypeToSandbox = ConcurrentHashMap<String, PluginSandbox>()

    /**
     * Register a tab type with its managing sandbox.
     *
     * @param typeId The tab type ID
     * @param sandbox The sandbox managing this tab type
     */
    fun register(typeId: TabTypeId, sandbox: PluginSandbox) {
        tabTypeToSandbox[typeId.typeId] = sandbox
    }

    /**
     * Unregister a tab type.
     *
     * @param typeId The tab type ID to unregister
     */
    fun unregister(typeId: TabTypeId) {
        tabTypeToSandbox.remove(typeId.typeId)
    }

    /**
     * Get the sandbox for a tab type.
     *
     * @param typeId The tab type ID
     * @return The managing sandbox, or null if not sandboxed
     */
    fun getSandbox(typeId: TabTypeId): PluginSandbox? {
        return tabTypeToSandbox[typeId.typeId]
    }

    /**
     * Get the sandbox for a tab type by its string ID.
     *
     * @param typeIdString The tab type ID string
     * @return The managing sandbox, or null if not sandboxed
     */
    fun getSandbox(typeIdString: String): PluginSandbox? {
        return tabTypeToSandbox[typeIdString]
    }

    /**
     * Clear all registrations. Used for testing or cleanup.
     */
    fun clear() {
        tabTypeToSandbox.clear()
    }
}
