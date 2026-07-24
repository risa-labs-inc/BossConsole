package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.browser.BrowserService

/**
 * Platform-specific provider for BrowserService.
 *
 * On desktop platforms with JxBrowser support, this returns an implementation scoped
 * to [windowId] so window teardown only disposes browsers owned by that window.
 * On other platforms, this returns null.
 */
expect fun getBrowserServiceInstance(windowId: String?): BrowserService?

/**
 * Dispose browsers created by dynamic plugins in [windowId] via BrowserService.
 *
 * Called during window close to synchronously clean up plugin-created
 * JxBrowser instances before AWT window destruction.
 * No-op on non-desktop platforms.
 */
internal expect fun disposePluginBrowsers(windowId: String)
