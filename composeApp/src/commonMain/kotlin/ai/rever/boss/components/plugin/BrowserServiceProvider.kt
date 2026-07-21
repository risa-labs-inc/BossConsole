package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.browser.BrowserService

/**
 * Platform-specific provider for BrowserService.
 *
 * On desktop platforms with JxBrowser support, this returns the actual implementation.
 * On other platforms, this returns null.
 */
expect fun getBrowserServiceInstance(): BrowserService?

/**
 * Dispose all browsers created by dynamic plugins via BrowserService.
 *
 * Called during window close to synchronously clean up plugin-created
 * JxBrowser instances before AWT window destruction.
 * No-op on non-desktop platforms.
 */
internal expect fun disposePluginBrowsers()
