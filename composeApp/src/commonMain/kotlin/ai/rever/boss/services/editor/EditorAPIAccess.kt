package ai.rever.boss.services.editor

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.plugin.api.EditorTabPluginAPI
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

private val logger = BossLogger.forComponent("EditorAPIAccess")

/**
 * Provides access to EditorTabPluginAPI from the plugin system.
 *
 * This is the bridge between BossConsole host code and the editor-tab plugin.
 * When the editor-tab plugin is installed, getPluginAPI(EditorTabPluginAPI::class.java)
 * returns the provider. When uninstalled, it returns null and editor features
 * gracefully degrade.
 *
 * This follows the same pattern as TerminalAPIAccess.
 */
object EditorAPIAccess {
    private var cachedDefaultPlugin: DefaultPlugin? = null

    /**
     * Set the DefaultPlugin reference for API access.
     * Call this once from BossApp when creating the DefaultPlugin.
     */
    fun initialize(defaultPlugin: DefaultPlugin) {
        cachedDefaultPlugin = defaultPlugin
        logger.debug(
            LogCategory.SYSTEM,
            "EditorAPIAccess initialized",
            mapOf("apiAvailable" to (getProvider() != null)),
        )
    }

    /**
     * Get the EditorTabPluginAPI from the plugin system.
     *
     * @return The provider if the editor-tab plugin is installed, null otherwise
     */
    fun getProvider(): EditorTabPluginAPI? {
        val plugin = cachedDefaultPlugin ?: return null
        return plugin.getPluginAPI(EditorTabPluginAPI::class.java)
    }

    /**
     * Compose-observable provider lookup: re-reads the registry whenever any
     * plugin registers an API, so UI gated on availability (the Settings
     * sections) swaps from its "not loaded yet" notice to the real panel when
     * the editor-tab plugin finishes its asynchronous startup registration.
     */
    @Composable
    fun rememberProvider(): EditorTabPluginAPI? {
        val plugin = cachedDefaultPlugin ?: return null
        val registryVersion by plugin.apiRegistryVersion.collectAsState()
        return remember(registryVersion) { getProvider() }
    }

    // ==================== Composable Bridges (Settings) ====================

    @Composable
    fun EditorSettingsPanel(modifier: Modifier) {
        getProvider()?.EditorSettingsPanel(modifier)
    }

    @Composable
    fun LspSettingsPanel(modifier: Modifier) {
        getProvider()?.LspSettingsPanel(modifier)
    }
}
