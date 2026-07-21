package ai.rever.boss.components.plugin.panels.right_top

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent
import ai.rever.boss.plugin.browser.LockedBrowser
import ai.rever.boss.plugin.browser.BrowserServiceImpl
import com.teamdev.jxbrowser.browser.Browser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val browserAccessorLogger = BossLogger.forComponent("DesktopBrowserAccessor")

/**
 * Desktop implementation of browser accessor using JxBrowser
 */
actual class BrowserAccessor {
    actual fun getActiveBrowserIntegration(): BrowserIntegration? {
        // Direct implementation - don't rely on ConnectToFluckBrowser being called
        val tabId = selectedTabId ?: return null

        // Reuse the cached integration only if it is for *this* tab and still live.
        // The cache is keyed by tab id: without that check a still-available
        // integration from a previously-requested tab would be returned here,
        // silently driving the wrong browser (e.g. a plugin that opens a new
        // browser tab per run would keep driving the first run's tab).
        val cached = currentBrowserIntegration
        if (cached != null && currentIntegrationTabId == tabId && cached.isBrowserAvailable()) {
            return cached
        }

        // Try to find browser directly if we have access to split view state
        val splitViewState = lastKnownSplitViewState
        if (splitViewState != null) {
            val browser = findBrowserForTab(splitViewState, tabId)
            if (browser != null) {
                currentBrowserIntegration = DesktopBrowserIntegration(browser)
                currentIntegrationTabId = tabId
                return currentBrowserIntegration
            }
        }

        return null
    }

    actual companion object {
        var currentBrowserIntegration: BrowserIntegration? = null
        /** Tab id [currentBrowserIntegration] was resolved for (cache key). */
        var currentIntegrationTabId: String? = null
        actual var selectedTabId: String? = null
        var lastKnownSplitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null
    }
}

/**
 * Desktop browser integration using JxBrowser with thread-safe LockedBrowser wrapper
 */
class DesktopBrowserIntegration(
    internal val browser: LockedBrowser
) : BrowserIntegration {

    override suspend fun executeJavaScript(script: String): Any? = withContext(Dispatchers.Main) {
        try {
            val mainFrame = browser.mainFrame().orElse(null)
            if (mainFrame != null) {
                mainFrame.executeJavaScript<Any>(script)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun navigate(url: String) {
        withContext(Dispatchers.Main) {
            try {
                browser.navigation().loadUrl(url)
            } catch (e: Exception) {
                browserAccessorLogger.warn(LogCategory.BROWSER, "navigate failed", mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    override fun isBrowserAvailable(): Boolean {
        return try {
            !browser.isClosed
        } catch (e: Exception) {
            // Browser was disposed or became inaccessible
            false
        }
    }

    override suspend fun getCurrentUrl(): String? = withContext(Dispatchers.Main) {
        try {
            browser.url()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Direct browser lookup function - returns thread-safe LockedBrowser wrapper
 */
private fun findBrowserForTab(
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState,
    tabId: String
): LockedBrowser? {
    return try {
        // Get all active Fluck tabs
        val activeFluckTabs = splitViewState.collectAllActiveFluckTabs()

        // Find the selected tab
        val selectedTab = activeFluckTabs.find { activeTab ->
            val tabInfo = activeTab.tabInfo
            when (tabInfo) {
                is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo -> tabInfo.id == tabId
                is FluckTabComponent -> tabInfo.config.id == tabId
                else -> false
            }
        }

        if (selectedTab != null) {
            val tabInfo = selectedTab.tabInfo

            // Get browser based on tab info type
            val lockedBrowser: LockedBrowser? = when (tabInfo) {
                is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo -> {
                    val component = findFluckTabComponentById(splitViewState, tabInfo.id)
                    if (component != null) {
                        try {
                            val rawBrowser = component.browser as? Browser
                            if (rawBrowser != null && !rawBrowser.isClosed) {
                                LockedBrowser(rawBrowser, component.browserLock)
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                is FluckTabComponent -> {
                    try {
                        val rawBrowser = tabInfo.browser as? Browser
                        if (rawBrowser != null && !rawBrowser.isClosed) {
                            LockedBrowser(rawBrowser, tabInfo.browserLock)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                else -> null
            }

            if (lockedBrowser != null) return lockedBrowser

            // Fallback: for dynamic plugin browser tabs (typeId "fluck" but not FluckTabInfo),
            // look up the browser via BrowserServiceImpl active handles by matching URL
            if (tabInfo.typeId.typeId == "fluck") {
                val tabUrl = try {
                    tabInfo::class.java.methods
                        .firstOrNull { it.name == "getCurrentUrl" && it.parameterCount == 0 }
                        ?.invoke(tabInfo) as? String
                        ?: tabInfo::class.java.methods
                            .firstOrNull { it.name == "getInitialUrl" && it.parameterCount == 0 }
                            ?.invoke(tabInfo) as? String
                } catch (_: Exception) { null }

                if (!tabUrl.isNullOrBlank()) {
                    val handle = BrowserServiceImpl.getActiveHandles().firstOrNull { h ->
                        h.isValid && h.getCurrentUrl() == tabUrl
                    }
                    if (handle != null) {
                        return LockedBrowser(handle.getRawBrowser(), handle.getBrowserLock())
                    }
                }
            }
        }

        null
    } catch (e: Exception) {
        // Handle any exceptions during browser lookup
        browserAccessorLogger.warn(LogCategory.BROWSER, "Error finding browser for tab", mapOf("tabId" to tabId), error = e)
        null
    }
}

/**
 * Helper function to find FluckTabComponent by ID in the SplitViewState
 */
private fun findFluckTabComponentById(
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState, 
    tabId: String
): FluckTabComponent? {
    // Search through all panels
    val allPanels = splitViewState.getAllPanels()
    
    for (panel in allPanels) {
        val tabsComponent = panel.tabsComponent
        
        // Use the public API method to get the component
        val component = tabsComponent.getComponentById(tabId)
        
        if (component is FluckTabComponent) {
            return component
        }
    }
    
    return null
}

/**
 * Desktop implementation to store split view state
 */
actual fun storeSplitViewState(splitViewState: Any) {
    BrowserAccessor.lastKnownSplitViewState = splitViewState as? ai.rever.boss.components.window_panel.SplitViewState
}

/**
 * Desktop implementation to create FluckTabInfo from ActiveTab
 */
actual fun createFluckTabInfo(activeTab: Any): FluckTabInfo? {
    // ActiveTab is from composeApp's topofmind package
    val activeTabTyped = activeTab as? ai.rever.boss.topofmind.ActiveTab
        ?: return null

    val tabInfo = activeTabTyped.tabInfo

    // Check if this is a built-in Fluck tab
    if (tabInfo is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo) {
        return FluckTabInfo(
            id = tabInfo.id,
            title = tabInfo.title,
            url = tabInfo.currentUrl,
            panelId = activeTabTyped.panelId,
            tabComponent = tabInfo
        )
    }

    // Check if this is a dynamic plugin browser tab (typeId "fluck")
    if (tabInfo.typeId.typeId == "fluck") {
        val url = try {
            tabInfo::class.java.methods
                .firstOrNull { it.name == "getCurrentUrl" && it.parameterCount == 0 }
                ?.invoke(tabInfo) as? String
                ?: tabInfo::class.java.methods
                    .firstOrNull { it.name == "getInitialUrl" && it.parameterCount == 0 }
                    ?.invoke(tabInfo) as? String
                ?: ""
        } catch (_: Exception) { "" }

        return FluckTabInfo(
            id = tabInfo.id,
            title = tabInfo.title,
            url = url,
            panelId = activeTabTyped.panelId,
            tabComponent = tabInfo
        )
    }

    return null
}
