package ai.rever.boss.components.plugin.panels.right_top

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent
import ai.rever.boss.plugin.browser.BoundedBrowserCall
import ai.rever.boss.plugin.browser.BrowserServiceImpl
import ai.rever.boss.plugin.browser.LockedBrowser
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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
    internal val browser: LockedBrowser,
) : BrowserIntegration {
    /**
     * One per integration, not one per process.
     *
     * Sharing a single instance across every tab was the wrong trade: the motivating case is a
     * renderer parked on a modal `window.prompt`, which never returns, so the shared thread stays
     * held for as long as that dialog is open and *every* plugin call in the process then costs a
     * full deadline and answers null - on tabs that are perfectly healthy, for a dialog nobody knows
     * is open. Per-integration puts the blast radius back to the one tab that stopped answering,
     * which is what the per-handle instance in BrowserHandleImpl already achieves.
     *
     * Affordable because [BoundedBrowserCall]'s thread is created on first call and retires when
     * idle: these accessors are rebuilt on every tab switch and nothing disposes them, so an
     * instance that is churned through without making a call costs nothing at all. Explicitly
     * shutting the previous one down where the cache is replaced was the other option and was
     * rejected - a plugin can still be holding that integration, and its calls would start
     * answering null underneath it.
     *
     * Never shut down, and that is not an admitted leak: with `allowCoreThreadTimeOut` the worker
     * exits after its idle window and the executor becomes garbage along with the integration that
     * held it. An instance that never made a call never started a thread in the first place.
     */
    private val accessorCall = BoundedBrowserCall("boss-plugin-browser-call-${System.identityHashCode(browser)}")

    /**
     * Evaluate [script] in the tab this accessor is pointed at, or null if the renderer did not
     * answer in time.
     *
     * This is the *plugin-facing* path - `DefaultPlugin.getBrowserIntegration` resolves it and
     * `BrowserIntegrationAdapter.executeJavaScript` hands it out - so it is a shipped API onto the
     * exact freeze [BoundedBrowserCall] describes. It ran on `Dispatchers.Main`, which meant a
     * plugin driving a tab parked on a modal `window.prompt` took the EDT, and AppKit's main thread
     * with it.
     */
    override suspend fun executeJavaScript(script: String): Any? =
        accessorCall.call(
            onError = { e ->
                browserAccessorLogger.debug(
                    LogCategory.BROWSER,
                    "executeJavaScript failed - returning null",
                    mapOf("error" to e.toString()),
                )
            },
        ) {
            browser.mainFrame().orElse(null)?.executeJavaScript<Any>(script)
        }

    // Stays on Main: `loadUrl` is answered by the browser process, which page JS cannot block, so
    // it is not the hazard executeJavaScript above is and gains nothing from queueing behind one.
    override suspend fun navigate(url: String) {
        withContext(Dispatchers.Main) {
            try {
                browser.navigation().loadUrl(url)
            } catch (e: Exception) {
                browserAccessorLogger.warn(LogCategory.BROWSER, "navigate failed", mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    override fun isBrowserAvailable(): Boolean =
        try {
            !browser.isClosed
        } catch (e: Exception) {
            // Browser was disposed or became inaccessible
            browserAccessorLogger.debug(
                LogCategory.BROWSER,
                "Browser liveness check failed - treating as unavailable",
                mapOf("error" to e.toString()),
            )
            false
        }

    override suspend fun getCurrentUrl(): String? =
        withContext(Dispatchers.Main) {
            try {
                browser.url()
            } catch (e: Exception) {
                browserAccessorLogger.debug(
                    LogCategory.BROWSER,
                    "Could not read current URL - browser likely disposed",
                    mapOf("error" to e.toString()),
                )
                null
            }
        }
}

/**
 * The one candidate matching a tab's URL, reporting rather than hiding an ambiguity.
 *
 * Pure and separate from [findBrowserForTab] so it can be tested: the rest of that function
 * needs a live SplitViewState and real JxBrowser handles, which is why none of it has ever had a
 * test, and the selection is the part with actual logic in it.
 *
 * [onAmbiguous] is called with the match count when more than one candidate matches, and the
 * first is still returned. Two tabs on one URL are genuinely indistinguishable here - nothing
 * ties a browser handle to the tab that owns it, so this resolves by iteration order and can
 * hand back the wrong tab. That is pre-existing and not fixable at this layer: closing it needs
 * the tab id at browser-creation time, and `BrowserConfig` does not carry one. Reported rather
 * than silently guessed, because the symptom otherwise is a plugin driving a page the user is
 * not looking at, with nothing in the log to say so.
 */
internal fun <T> resolveSingleByUrl(
    candidates: List<T>,
    matches: (T) -> Boolean,
    onAmbiguous: (Int) -> Unit,
): T? {
    val hits = candidates.filter(matches)
    if (hits.size > 1) onAmbiguous(hits.size)
    return hits.firstOrNull()
}

/**
 * Direct browser lookup function - returns thread-safe LockedBrowser wrapper
 */
private fun findBrowserForTab(
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState,
    tabId: String,
): LockedBrowser? {
    return try {
        // Get all active Fluck tabs
        val activeFluckTabs = splitViewState.collectAllActiveFluckTabs()

        // Find the selected tab
        val selectedTab =
            activeFluckTabs.find { activeTab ->
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
            val lockedBrowser: LockedBrowser? =
                when (tabInfo) {
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
                                browserAccessorLogger.debug(
                                    LogCategory.BROWSER,
                                    "Browser handle unavailable for Fluck tab - skipping",
                                    mapOf("error" to e.toString()),
                                )
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
                            browserAccessorLogger.debug(
                                LogCategory.BROWSER,
                                "Browser handle unavailable for tab component - skipping",
                                mapOf("error" to e.toString()),
                            )
                            null
                        }
                    }

                    else -> {
                        null
                    }
                }

            if (lockedBrowser != null) return lockedBrowser

            // Fallback: for dynamic plugin browser tabs (typeId "fluck" but not FluckTabInfo),
            // look up the browser via BrowserServiceImpl active handles by matching URL
            if (tabInfo.typeId.typeId == "fluck") {
                val tabUrl =
                    try {
                        tabInfo::class.java.methods
                            .firstOrNull { it.name == "getCurrentUrl" && it.parameterCount == 0 }
                            ?.invoke(tabInfo) as? String
                            ?: tabInfo::class.java.methods
                                .firstOrNull { it.name == "getInitialUrl" && it.parameterCount == 0 }
                                ?.invoke(tabInfo) as? String
                    } catch (_: Exception) {
                        null
                    }

                if (!tabUrl.isNullOrBlank()) {
                    // Matched against each handle's navigation-fed URL, never a live read.
                    // This used to call getCurrentUrl() per candidate, which is a blocking IPC
                    // round trip into Chromium - so one lookup cost a round trip per open
                    // browser, on a path panels poll, and a handle whose transport had gone
                    // made each one a failure that had to fail first. See
                    // BrowserHandleImpl.lastKnownUrl for why the cached value is the better
                    // comparison and not merely the cheaper one.
                    val handle =
                        resolveSingleByUrl(
                            candidates = BrowserServiceImpl.getActiveHandles(),
                            matches = { it.isAtUrl(tabUrl) },
                            onAmbiguous = { count ->
                                browserAccessorLogger.warn(
                                    LogCategory.BROWSER,
                                    "Multiple browsers share this tab's URL - resolving by iteration order",
                                    mapOf("tabId" to tabId, "candidates" to count.toString()),
                                )
                            },
                        )
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
    tabId: String,
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
    val activeTabTyped =
        activeTab as? ai.rever.boss.topofmind.ActiveTab
            ?: return null

    val tabInfo = activeTabTyped.tabInfo

    // Check if this is a built-in Fluck tab
    if (tabInfo is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo) {
        return FluckTabInfo(
            id = tabInfo.id,
            title = tabInfo.title,
            url = tabInfo.currentUrl,
            panelId = activeTabTyped.panelId,
            tabComponent = tabInfo,
        )
    }

    // Check if this is a dynamic plugin browser tab (typeId "fluck")
    if (tabInfo.typeId.typeId == "fluck") {
        val url =
            try {
                tabInfo::class.java.methods
                    .firstOrNull { it.name == "getCurrentUrl" && it.parameterCount == 0 }
                    ?.invoke(tabInfo) as? String
                    ?: tabInfo::class.java.methods
                        .firstOrNull { it.name == "getInitialUrl" && it.parameterCount == 0 }
                        ?.invoke(tabInfo) as? String
                    ?: ""
            } catch (_: Exception) {
                ""
            }

        return FluckTabInfo(
            id = tabInfo.id,
            title = tabInfo.title,
            url = url,
            panelId = activeTabTyped.panelId,
            tabComponent = tabInfo,
        )
    }

    return null
}
