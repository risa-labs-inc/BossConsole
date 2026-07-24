package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.DynamicPluginManager

/**
 * Result of plugin installation containing both successful and failed installations.
 */
data class PluginInstallResult(
    val installedIds: List<String>,
    val failedPlugins: List<Pair<String, String>>, // pluginId to error message
) {
    val hasFailures: Boolean get() = failedPlugins.isNotEmpty()
    val allSucceeded: Boolean get() = failedPlugins.isEmpty()
}

/**
 * Platform-specific integration for the plugin install wizard.
 *
 * This provides access to platform-specific functionality like fetching
 * available plugins from the repository and installing them.
 */
expect object PluginWizardIntegration {
    /**
     * Get the list of available plugins for the wizard.
     *
     * @return List of plugins formatted for the wizard UI
     */
    suspend fun getAvailablePlugins(): List<WizardPluginInfo>

    /**
     * Install the selected plugins.
     *
     * @param dynamicPluginManager The plugin manager to use for installation
     * @param plugins List of plugins to install (includes GitHub URL info for GitHub-sourced plugins)
     * @param onProgress Progress callback (0.0 to 1.0, status message)
     * @return Result containing installation result with both successful and failed plugin IDs
     */
    suspend fun installPlugins(
        dynamicPluginManager: DynamicPluginManager,
        plugins: List<WizardPluginInfo>,
        onProgress: (Float, String) -> Unit,
    ): Result<PluginInstallResult>
}
