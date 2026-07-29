package ai.rever.boss.services.llm

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.plugin.api.LlmProviderSettingsAPI
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

private val logger = BossLogger.forComponent("LlmProviderAPIAccess")

/**
 * Provides access to LlmProviderSettingsAPI from the plugin system.
 *
 * This is the bridge between BossConsole host code and the plugin that owns AI
 * provider configuration (secret-manager). When that plugin is installed,
 * getPluginAPI(LlmProviderSettingsAPI::class.java) returns the provider; when it is
 * absent, it returns null and AI features degrade gracefully.
 *
 * The host deliberately holds no provider state of its own. Credentials, environment
 * resolution and the live model catalogue all live in the plugin, so this class only
 * relays — it is what backs both Settings → AI Providers and
 * `PluginContext.llmProvider`.
 *
 * This follows the same pattern as EditorAPIAccess and TerminalAPIAccess.
 */
object LlmProviderAPIAccess {
    private var cachedDefaultPlugin: DefaultPlugin? = null

    /**
     * Set the DefaultPlugin reference for API access.
     * Call this once from BossApp when creating the DefaultPlugin.
     */
    fun initialize(defaultPlugin: DefaultPlugin) {
        cachedDefaultPlugin = defaultPlugin
        logger.debug(
            LogCategory.SYSTEM,
            "LlmProviderAPIAccess initialized",
            mapOf("apiAvailable" to (getProvider() != null)),
        )
    }

    /**
     * Get the LlmProviderSettingsAPI from the plugin system.
     *
     * @return The provider if the owning plugin is installed, null otherwise
     */
    fun getProvider(): LlmProviderSettingsAPI? {
        val plugin = cachedDefaultPlugin ?: return null
        return plugin.getPluginAPI(LlmProviderSettingsAPI::class.java)
    }

    /**
     * Compose-observable provider lookup: re-reads the registry whenever any plugin
     * registers an API, so UI gated on availability (the Settings section) swaps from
     * its "not loaded yet" notice to the real panel when the owning plugin finishes
     * its asynchronous startup registration.
     */
    @Composable
    fun rememberProvider(): LlmProviderSettingsAPI? {
        val plugin = cachedDefaultPlugin ?: return null
        val registryVersion by plugin.apiRegistryVersion.collectAsState()
        return remember(registryVersion) { getProvider() }
    }

    // ==================== Composable Bridges (Settings) ====================

    /**
     * Render the plugin's panel, or nothing when it isn't available.
     *
     * Delegates to [rememberProvider], not [getProvider]: a bridge built on the latter
     * would resolve once and never recompose, so a panel would stay invisible when the
     * owning plugin registers late — the exact failure [rememberProvider] exists to
     * prevent.
     */
    @Composable
    fun LlmProviderSettingsPanel(modifier: Modifier) {
        val provider = rememberProvider() ?: return
        if (provider.supportsSettingsPanel) {
            provider.LlmProviderSettingsPanel(modifier)
        }
    }
}
