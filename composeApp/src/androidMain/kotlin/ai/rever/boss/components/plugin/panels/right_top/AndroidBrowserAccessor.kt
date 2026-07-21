package ai.rever.boss.components.plugin.panels.right_top


/**
 * Android implementation of browser accessor
 */
actual class BrowserAccessor {
    actual fun getActiveBrowserIntegration(): BrowserIntegration? {
        // Android implementation not yet available
        return null
    }
    
    actual companion object {
        actual var selectedTabId: String? = null
    }
}

/**
 * Android implementation to store split view state
 */
actual fun storeSplitViewState(splitViewState: Any) {
    // Android implementation not yet available
}

/**
 * Android implementation to create FluckTabInfo from ActiveTab
 */
actual fun createFluckTabInfo(activeTab: Any): FluckTabInfo? {
    // Android implementation not yet available
    return null
}
