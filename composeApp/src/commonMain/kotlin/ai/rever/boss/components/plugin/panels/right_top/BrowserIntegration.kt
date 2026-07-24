package ai.rever.boss.components.plugin.panels.right_top

/**
 * Integration interface for driving a browser tab (JS execution, navigation).
 * Implemented per platform and adapted for dynamic plugins in DefaultPlugin.
 */
interface BrowserIntegration {
    /**
     * Execute JavaScript in the browser
     */
    suspend fun executeJavaScript(script: String): Any?

    /**
     * Navigate the tab to [url]. Best-effort; default is a no-op.
     */
    suspend fun navigate(url: String) {}

    /**
     * Check if browser is available
     */
    fun isBrowserAvailable(): Boolean

    /**
     * Get current URL
     */
    suspend fun getCurrentUrl(): String?
}

/**
 * Interface for accessing the active browser tab
 */
expect class BrowserAccessor() {
    /**
     * Get browser integration for the active tab
     */
    fun getActiveBrowserIntegration(): BrowserIntegration?

    companion object {
        var selectedTabId: String?
    }
}

/**
 * Data class for Fluck tab information
 */
data class FluckTabInfo(
    val id: String,
    val title: String,
    val url: String,
    val panelId: String,
    val tabComponent: Any?, // The actual FluckTabComponent
)

/**
 * Platform-specific function to create FluckTabInfo from ActiveTab
 */
expect fun createFluckTabInfo(activeTab: Any): FluckTabInfo?

/**
 * Platform-specific function to store split view state
 */
expect fun storeSplitViewState(splitViewState: Any)
