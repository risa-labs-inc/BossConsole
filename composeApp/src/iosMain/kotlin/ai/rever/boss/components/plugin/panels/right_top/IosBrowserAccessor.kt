package ai.rever.boss.components.plugin.panels.right_top


/**
 * iOS implementation of browser accessor
 */
actual class BrowserAccessor {
    actual fun getActiveBrowserIntegration(): BrowserIntegration? {
        // iOS implementation not yet available
        return null
    }
    
    actual companion object {
        actual var selectedTabId: String? = null
    }
}

/**
 * iOS implementation to store split view state
 */
actual fun storeSplitViewState(splitViewState: Any) {
    // iOS implementation not yet available
}

/**
 * iOS implementation to create FluckTabInfo from ActiveTab
 */
actual fun createFluckTabInfo(activeTab: Any): FluckTabInfo? {
    // iOS implementation not yet available
    return null
}
