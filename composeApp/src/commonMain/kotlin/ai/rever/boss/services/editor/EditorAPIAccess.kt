package ai.rever.boss.services.editor

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.plugin.api.BufferChange
import ai.rever.boss.plugin.api.BufferSnapshot
import ai.rever.boss.plugin.api.EditResult
import ai.rever.boss.plugin.api.EditorTabPluginAPI
import ai.rever.boss.plugin.api.FocusedDocument
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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

    // ==================== Auto Save ====================

    /**
     * Observable auto save state for menu rendering, or null when there is nothing to render a
     * control for: no editor-tab plugin installed, or one that predates the setting.
     *
     * Callers should hide the control on null rather than showing an unchecked one, which would
     * be indistinguishable from auto save being off and would do nothing when clicked.
     */
    @Composable
    fun rememberAutoSaveEnabled(): State<Boolean>? {
        val provider = rememberProvider()
        val enabled = remember(provider) { provider?.autoSaveEnabled() }
        return enabled?.collectAsState()
    }

    /** Turns auto save on or off in the editor plugin. No-op when it is not installed. */
    fun setAutoSaveEnabled(enabled: Boolean) {
        getProvider()?.setAutoSaveEnabled(enabled)
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

    // ==================== Live buffer model (boss-plugin-api 1.0.87, D3) ====================
    //
    // Passthroughs for the editor-tab plugin's buffer API. Each returns the
    // "plugin predates this method" default (null / false) when the installed
    // editor-tab plugin does not implement the member, so host callers gate on
    // null exactly like every other EditorAPIAccess surface.

    /** Snapshot of the live buffer for [path], or null when the file has no open buffer (or the plugin predates the method). */
    suspend fun readBuffer(path: String): BufferSnapshot? = getProvider()?.readBuffer(path)

    /** Apply an undoable edit to the live buffer; see [EditorTabPluginAPI.applyEdit] for the versioning contract. */
    suspend fun applyEdit(
        path: String,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        newText: String,
        expectedVersion: Long,
    ): EditResult? = getProvider()?.applyEdit(path, startLine, startCol, endLine, endCol, newText, expectedVersion)

    /** Observe changes to the buffer at [path]; null when unsupported. */
    fun observeChanges(path: String): kotlinx.coroutines.flow.Flow<BufferChange>? = getProvider()?.observeChanges(path)

    /** The focused editor document with its selection, or null. */
    suspend fun focusedDocument(): FocusedDocument? = getProvider()?.focusedDocument()

    /** Open (or focus) an editor tab for [path], optionally at [line]. */
    suspend fun openEditor(
        path: String,
        line: Int? = null,
    ): Boolean = getProvider()?.openEditor(path, line) ?: false

    /** Open [path] in a split pane of the current editor tab. */
    suspend fun openSplit(path: String): Boolean = getProvider()?.openSplit(path) ?: false
}
