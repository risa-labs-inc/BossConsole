package ai.rever.boss.components.plugin.panels.right_top


/**
 * WASM implementation of browser accessor
 */
actual class BrowserAccessor {
    actual fun getActiveBrowserIntegration(): BrowserIntegration? {
        // WASM implementation not yet available
        return null
    }
    
    actual companion object {
        actual var selectedTabId: String? = null
    }
}

/**
 * WASM implementation to store split view state
 */
actual fun storeSplitViewState(splitViewState: Any) {
    // WASM implementation not yet available
}

/**
 * WASM implementation to create FluckTabInfo from ActiveTab
 */
actual fun createFluckTabInfo(activeTab: Any): FluckTabInfo? {
    // WASM implementation not yet available
    return null
}
