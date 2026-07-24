package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.browser.BrowserService

/**
 * iOS implementation of BrowserService provider.
 *
 * JxBrowser is not available on iOS, so this returns null.
 */
actual fun getBrowserServiceInstance(windowId: String?): BrowserService? = null

internal actual fun disposePluginBrowsers(windowId: String) {}
