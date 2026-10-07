package ai.rever.boss.components.plugin

/** What the store has for a plugin, as far as installing its released build goes. */
sealed class StoreVersionLookup {
    data class Available(
        val displayName: String,
        val version: String,
        /**
         * The store's download URL, recorded on install. Not decoration: it is the evidence that
         * survives on the `installed.json` row and stops an unsigned store download from later
         * reading as a local build.
         */
        val sourceUrl: String?,
    ) : StoreVersionLookup()

    /** The store has no row for this plugin - a locally built plugin that was never published. */
    data object NotPublished : StoreVersionLookup()

    /** The store could not be asked (offline, not initialised), which is not the same as absent. */
    data class Unavailable(
        val message: String,
    ) : StoreVersionLookup()
}

/**
 * Bridges the commonMain host UI to the desktopMain plugin store, for going back to the released
 * build of a plugin you are running a local or hot-reloaded copy of.
 *
 * Separate from [PluginUpdateBridge] on purpose. That one is update-shaped: everything it can do is
 * gated behind `isNewerVersion`, and the store version wanted here is usually the SAME as, or older
 * than, the local build that replaced it - so an update check reports "up to date" and its install
 * path refuses to run.
 */
expect object PluginStoreVersionBridge {
    /** Ask the store - and only the store - what it publishes for [pluginId]. */
    suspend fun lookup(pluginId: String): StoreVersionLookup

    /**
     * Download the store's [version] of [pluginId] and swap it in for whatever is running,
     * reusing [manager] to unload and load. Returns the version actually installed.
     *
     * Runs detached from the caller: a swap cancelled between the unload and the load would leave
     * the plugin gone with nothing in its place, and the caller here is a window's scope.
     */
    suspend fun installStoreVersion(
        pluginId: String,
        version: String,
        sourceUrl: String?,
        manager: DynamicPluginManager,
    ): Result<String>

    /** Install only the bytes approved for this invocation, retaining the ordinary overload above. */
    suspend fun installStoreVersion(
        pluginId: String,
        version: String,
        sourceUrl: String?,
        manager: DynamicPluginManager,
        expectedSha256: String?,
    ): Result<String>
}
