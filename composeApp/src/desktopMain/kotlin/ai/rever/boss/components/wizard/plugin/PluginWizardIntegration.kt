package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.DynamicPluginManager

/**
 * Desktop implementation of the plugin wizard integration.
 *
 * Provides access to plugin repository functionality for the wizard.
 */
actual object PluginWizardIntegration {
    /**
     * Get the list of available plugins for the wizard.
     *
     * @return List of plugins formatted for the wizard UI
     */
    actual suspend fun getAvailablePlugins(): List<WizardPluginInfo> {
        return PluginListProvider.getAvailablePlugins()
    }

    /**
     * Install the selected plugins.
     *
     * @param dynamicPluginManager The plugin manager to use for installation
     * @param plugins List of plugins to install (includes GitHub URL info for GitHub-sourced plugins)
     * @param onProgress Progress callback (0.0 to 1.0, status message)
     * @return Result containing installation result with both successful and failed plugin IDs
     */
    actual suspend fun installPlugins(
        dynamicPluginManager: DynamicPluginManager,
        plugins: List<WizardPluginInfo>,
        onProgress: (Float, String) -> Unit
    ): Result<PluginInstallResult> {
        val service = PluginInstallService.create(dynamicPluginManager)
        return service.installPlugins(plugins, onProgress)
    }
}
