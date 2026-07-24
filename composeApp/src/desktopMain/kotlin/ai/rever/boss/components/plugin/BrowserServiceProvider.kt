package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserServiceImpl

/**
 * Desktop implementation of BrowserService provider.
 *
 * Returns a window-scoped view of the BrowserServiceImpl singleton that wraps
 * FluckEngine. The wrapper keeps plugin browser ownership isolated per window.
 */
actual fun getBrowserServiceInstance(windowId: String?): BrowserService? =
    windowId?.let(::WindowScopedBrowserService) ?: BrowserServiceImpl

private class WindowScopedBrowserService(
    private val windowId: String
) : BrowserService by BrowserServiceImpl {
    override suspend fun createBrowser(config: BrowserConfig): BrowserHandle? =
        BrowserServiceImpl.createBrowserForWindow(windowId, config)

    override fun getActiveBrowserCount(): Int =
        BrowserServiceImpl.getActiveBrowserCountForWindow(windowId)
}

internal actual fun disposePluginBrowsers(windowId: String) {
    BrowserServiceImpl.disposeAllForWindow(windowId)
}
