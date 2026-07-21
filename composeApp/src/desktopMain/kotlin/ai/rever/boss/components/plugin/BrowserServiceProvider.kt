package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.browser.BrowserServiceImpl

/**
 * Desktop implementation of BrowserService provider.
 *
 * Returns the BrowserServiceImpl singleton that wraps FluckEngine.
 */
actual fun getBrowserServiceInstance(): BrowserService? = BrowserServiceImpl

internal actual fun disposePluginBrowsers() {
    BrowserServiceImpl.disposeAll()
}
