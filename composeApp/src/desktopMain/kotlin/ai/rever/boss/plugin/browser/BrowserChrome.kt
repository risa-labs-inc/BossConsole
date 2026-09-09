package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser

/**
 * Default chrome setup for every browser BOSS creates.
 *
 * Bundles [installSuppressingContextMenu] (to prevent EDT crashes from JxBrowser's built-in menu)
 * with `FluckEngine.setupSwingPopupDismissOnPageClick` (to ensure page clicks dismiss Swing popups).
 */
internal fun installDefaultBrowserChrome(browser: Browser) {
    installSuppressingContextMenu(browser)
    FluckEngine.setupSwingPopupDismissOnPageClick(browser)
}
