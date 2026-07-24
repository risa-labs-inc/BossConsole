package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.plugin.api.InternalBrowserTabData
import ai.rever.boss.plugin.api.ScreenCaptureProvider
import ai.rever.boss.plugin.api.UrlHistoryEntry
import ai.rever.boss.plugin.api.UrlHistoryProvider
import ai.rever.boss.plugin.api.ZoomSettingsProvider
import ai.rever.boss.plugin.browser.BrowserZoomSettingsManager
import ai.rever.boss.plugin.browser.UrlHistoryManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlin.math.abs

/**
 * Desktop implementation of ZoomSettingsProvider that delegates to BrowserZoomSettingsManager.
 */
private class DesktopZoomSettingsProvider : ZoomSettingsProvider {
    override fun getZoomForDomain(domain: String): Double? {
        val zoom = BrowserZoomSettingsManager.getZoomForDomain(domain)
        // Return null if it's the default zoom level (1.0)
        return if (abs(zoom - 1.0) < 0.001) null else zoom
    }

    override fun setZoomForDomain(
        domain: String,
        zoomLevel: Double,
    ) {
        BrowserZoomSettingsManager.setZoomForDomain(domain, zoomLevel)
    }

    override fun extractDomain(url: String): String? = BrowserZoomSettingsManager.extractDomain(url)

    override fun clearZoomForDomain(domain: String) {
        BrowserZoomSettingsManager.clearDomainZoom(domain)
    }

    override suspend fun saveSettings() {
        BrowserZoomSettingsManager.saveSettings()
    }
}

/**
 * Desktop implementation of UrlHistoryProvider that delegates to UrlHistoryManager.
 */
private class DesktopUrlHistoryProvider : UrlHistoryProvider {
    override fun addUrl(
        url: String,
        title: String,
    ) {
        UrlHistoryManager.addUrl(url, title)
    }

    override fun getSuggestions(
        query: String,
        limit: Int,
    ): List<UrlHistoryEntry> =
        UrlHistoryManager.getSuggestions(query, limit).map { internal ->
            UrlHistoryEntry(
                url = internal.url,
                title = internal.title,
                domain = internal.domain,
                visitCount = internal.visitCount,
                lastVisited = internal.lastVisited,
            )
        }

    override fun deleteUrl(url: String) {
        UrlHistoryManager.deleteUrl(url)
    }

    override suspend fun saveHistory() {
        UrlHistoryManager.saveHistory()
    }
}

// Lazy singletons to avoid creating multiple instances
private val zoomSettingsProviderInstance by lazy { DesktopZoomSettingsProvider() }
private val urlHistoryProviderInstance by lazy { DesktopUrlHistoryProvider() }

/**
 * Actual implementation for creating ZoomSettingsProvider on desktop.
 */
actual fun createZoomSettingsProvider(): ZoomSettingsProvider = zoomSettingsProviderInstance

/**
 * Actual implementation for creating UrlHistoryProvider on desktop.
 */
actual fun createUrlHistoryProvider(): UrlHistoryProvider = urlHistoryProviderInstance

/**
 * Desktop implementation of ScreenCaptureProvider.
 *
 * Provides:
 * - Internal browser tab collection for screen capture picker
 * - macOS screen capture permission APIs
 */
private class DesktopScreenCaptureProvider : ScreenCaptureProvider {
    private val logger = BossLogger.forComponent("ScreenCaptureProvider")

    override fun getInternalBrowserTabs(): List<InternalBrowserTabData> {
        val internalTabs = mutableListOf<InternalBrowserTabData>()

        try {
            SplitViewStateRegistry.getAllStates().forEach { (windowId, state) ->
                state
                    .collectAllActiveTabs(null, windowId)
                    .filter { it.tabInfo is FluckTabInfo }
                    .forEachIndexed { index, activeTab ->
                        val fluckTab = activeTab.tabInfo as FluckTabInfo
                        internalTabs.add(
                            InternalBrowserTabData(
                                title = fluckTab.title,
                                url = fluckTab.currentUrl,
                                faviconCacheKey = fluckTab.faviconCacheKey,
                            ),
                        )
                    }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to collect internal tabs", error = e)
        }

        return internalTabs
    }

    override fun hasPermission(): Boolean = MacOSScreenCapture.hasPermission()

    override fun requestPermission(): Boolean = MacOSScreenCapture.requestPermission()
}

// Lazy singleton
private val screenCaptureProviderInstance by lazy { DesktopScreenCaptureProvider() }

/**
 * Actual implementation for creating ScreenCaptureProvider on desktop.
 */
actual fun createScreenCaptureProvider(): ScreenCaptureProvider = screenCaptureProviderInstance

private val coBrowseRtcProviderInstance by lazy {
    ai.rever.boss.plugin.browser
        .CoBrowseRtcProviderImpl()
}

/** WebRTC peer provider — backed by an offscreen JxBrowser page on desktop. */
actual fun createCoBrowseRtcProvider(): ai.rever.boss.plugin.api.CoBrowseRtcProvider? = coBrowseRtcProviderInstance
