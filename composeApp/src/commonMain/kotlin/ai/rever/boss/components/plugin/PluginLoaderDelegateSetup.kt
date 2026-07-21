package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginContext

/**
 * Expect declaration for PluginLoaderDelegate registration.
 *
 * This allows platform-specific implementations to register the
 * PluginLoaderDelegate so that dynamic plugins can access it.
 */
expect object PluginLoaderDelegateSetup {
    /**
     * Register the PluginLoaderDelegate with the plugin context.
     *
     * This must be called early in the application lifecycle, before
     * dynamic plugins attempt to access the delegate.
     *
     * @param context Plugin context for registration
     * @param dynamicPluginManager The dynamic plugin manager
     */
    fun register(
        context: PluginContext,
        dynamicPluginManager: DynamicPluginManager
    )
}
