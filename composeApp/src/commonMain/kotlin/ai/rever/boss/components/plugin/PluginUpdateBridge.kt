package ai.rever.boss.components.plugin

/**
 * Bridges the commonMain host UI to the desktopMain plugin update machinery
 * (PluginUpdateManager in PluginStoreSetup). The actual implementation publishes results into
 * [PluginUpdateRegistry] and performs downloads/installs.
 */
expect object PluginUpdateBridge {
    /** Check all [installed] plugins and publish compatible updates to [PluginUpdateRegistry]. */
    suspend fun refreshAll(installed: List<InstalledPluginRef>)

    /** Check a single plugin on demand; also refreshes its [PluginUpdateRegistry] entry. */
    suspend fun checkOne(ref: InstalledPluginRef): UpdateCheckOutcome

    /**
     * Download and install the latest compatible version, reusing [manager] to unload/load.
     *
     * Concurrent requests for the same plugin fail with
     * [PluginUpdateAlreadyInProgressException] without starting the update operation.
     */
    suspend fun performUpdate(
        pluginId: String,
        manager: DynamicPluginManager,
    ): Result<String>
}
