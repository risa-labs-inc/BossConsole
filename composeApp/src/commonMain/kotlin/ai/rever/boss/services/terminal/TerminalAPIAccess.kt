package ai.rever.boss.services.terminal

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.plugin.api.PendingSidebarCommand
import ai.rever.boss.plugin.api.SIDEBAR_TERMINAL_ID
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val logger = BossLogger.forComponent("TerminalAPIAccess")

/**
 * Provides access to TerminalTabPluginAPI from the plugin system.
 *
 * This is the bridge between BossConsole host code and the terminal-tab plugin.
 * When the terminal-tab plugin is installed, getPluginAPI(TerminalTabPluginAPI::class.java)
 * returns the provider. When uninstalled, it returns null and terminal features
 * gracefully degrade.
 *
 * This follows the same pattern as BookmarkAPIAccess.
 */
object TerminalAPIAccess {
    private var cachedDefaultPlugin: DefaultPlugin? = null

    /**
     * Set the DefaultPlugin reference for API access.
     * Call this once from BossApp when creating the DefaultPlugin.
     */
    fun initialize(defaultPlugin: DefaultPlugin) {
        cachedDefaultPlugin = defaultPlugin

        // Wire up runner terminal callbacks
        val api = getProvider()
        if (api != null) {
            wireRunnerCallbacks(api)
        }

        logger.debug(LogCategory.SYSTEM, "TerminalAPIAccess initialized", mapOf("apiAvailable" to (api != null)))
    }

    /**
     * Get the TerminalTabPluginAPI from the plugin system.
     *
     * @return The provider if the terminal-tab plugin is installed, null otherwise
     */
    fun getProvider(): TerminalTabPluginAPI? {
        val plugin = cachedDefaultPlugin ?: return null
        return plugin.getPluginAPI(TerminalTabPluginAPI::class.java)
    }

    // ==================== Convenience Methods (Graceful Degradation) ====================

    /**
     * Check if terminal state exists for the given window and terminal ID.
     */
    fun hasTerminalState(windowId: String, terminalId: String): Boolean {
        return getProvider()?.hasTerminalState(windowId, terminalId) ?: false
    }

    /**
     * Remove terminal state for the given window and terminal ID.
     */
    fun removeTerminalState(windowId: String, terminalId: String) {
        getProvider()?.removeTerminalState(windowId, terminalId)
    }

    /**
     * Remove all terminal states for a specific window.
     */
    fun removeAllForWindow(windowId: String): Int {
        return getProvider()?.removeAllForWindow(windowId) ?: 0
    }

    /**
     * Reset all terminal states.
     */
    fun resetAllTerminals(): Int {
        return getProvider()?.resetAllTerminals() ?: 0
    }

    /**
     * Send a command to a running terminal.
     */
    fun sendCommand(windowId: String, terminalId: String, command: String): Boolean {
        return getProvider()?.sendCommand(windowId, terminalId, command) ?: false
    }

    /**
     * Send Ctrl+C to a terminal.
     */
    fun sendInterrupt(windowId: String, terminalId: String): Boolean {
        return getProvider()?.sendInterrupt(windowId, terminalId) ?: false
    }

    /**
     * Send raw input bytes to a terminal.
     */
    fun sendInput(windowId: String, terminalId: String, bytes: ByteArray): Boolean {
        return getProvider()?.sendInput(windowId, terminalId, bytes) ?: false
    }

    /**
     * Close the active tab in a terminal.
     */
    fun closeActiveTab(windowId: String, terminalId: String): Boolean {
        return getProvider()?.closeActiveTab(windowId, terminalId) ?: false
    }

    /**
     * Open or reuse a sidebar terminal tab.
     */
    fun newSidebarTab(
        windowId: String,
        command: String,
        workingDirectory: String? = null,
        configId: String? = null,
        isRerun: Boolean = false
    ): Boolean {
        return getProvider()?.newSidebarTab(windowId, command, workingDirectory, configId, isRerun) ?: false
    }

    /**
     * Register a sidebar tab ID for a config.
     */
    fun registerSidebarTabId(windowId: String, configId: String, tabId: String) {
        getProvider()?.registerSidebarTabId(windowId, configId, tabId)
    }

    /**
     * Remove sidebar config tracking.
     */
    fun removeSidebarConfigTracking(windowId: String, configId: String) {
        getProvider()?.removeSidebarConfigTracking(windowId, configId)
    }

    /**
     * Clear all sidebar config tracking for a window.
     */
    fun clearSidebarConfigTrackingForWindow(windowId: String) {
        getProvider()?.clearSidebarConfigTrackingForWindow(windowId)
    }

    /**
     * Get config ID for a sidebar tab.
     */
    fun getConfigIdForSidebarTab(windowId: String, tabId: String): String? {
        return getProvider()?.getConfigIdForSidebarTab(windowId, tabId)
    }

    /**
     * Set a pending sidebar command.
     */
    fun setPendingSidebarCommand(windowId: String, command: String, workingDirectory: String?, configId: String? = null) {
        getProvider()?.setPendingSidebarCommand(windowId, command, workingDirectory, configId)
    }

    /**
     * Consume a pending sidebar command.
     */
    fun consumePendingSidebarCommand(windowId: String): PendingSidebarCommand? {
        return getProvider()?.consumePendingSidebarCommand(windowId)
    }

    /**
     * Get the reset generation StateFlow.
     */
    val resetGeneration: StateFlow<Int>
        get() = getProvider()?.resetGeneration ?: MutableStateFlow(0)

    // ==================== Composable Bridges (Settings & Onboarding) ====================

    @Composable
    fun TerminalSettingsPanel(modifier: Modifier) {
        getProvider()?.TerminalSettingsPanel(modifier)
    }

    @Composable
    fun TerminalOnboardingWizard(onDismiss: () -> Unit, onComplete: () -> Unit) {
        getProvider()?.TerminalOnboardingWizard(onDismiss, onComplete)
    }

    // ==================== Internal: Wire Runner Callbacks ====================

    /**
     * Wire up callbacks from the terminal plugin to RunnerTerminalService.
     * This connects the plugin's terminal events to the host's runner tracking.
     */
    private fun wireRunnerCallbacks(api: TerminalTabPluginAPI) {
        try {
            // Use reflection to set callbacks on the impl class
            // The TerminalTabPluginAPIImpl has callback properties we can set
            val implClass = api::class.java

            val terminalRemovedField = implClass.getMethod("setOnRunnerTerminalRemoved", Function2::class.java)
            terminalRemovedField.invoke(api, { windowId: String, terminalId: String ->
                try {
                    ai.rever.boss.run.RunnerTerminalService.removeTerminal(windowId, terminalId)
                } catch (e: Exception) {
                    logger.warn(LogCategory.TERMINAL, "Failed to notify RunnerTerminalService of terminal removal", error = e)
                }
            })

            val configRemovedField = implClass.getMethod("setOnRunnerConfigRemoved", Function2::class.java)
            configRemovedField.invoke(api, { windowId: String, configId: String ->
                try {
                    ai.rever.boss.run.RunnerTerminalService.removeConfig(windowId, configId)
                } catch (e: Exception) {
                    logger.warn(LogCategory.TERMINAL, "Failed to notify RunnerTerminalService of config removal", error = e)
                }
            })

            logger.debug(LogCategory.SYSTEM, "Wired runner terminal callbacks")
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not wire runner callbacks (plugin may be different version)", error = e)
        }
    }
}
