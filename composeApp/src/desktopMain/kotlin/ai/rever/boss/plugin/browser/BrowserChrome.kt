package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback

private val logger = BossLogger.forComponent("BrowserChrome")

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

/**
 * Installs a suppressing [ShowContextMenuCallback] on [browser].
 *
 * Calling `tell.close()` suppresses JxBrowser's built-in Swing menu (`SuggestionsPopup`),
 * which otherwise attempts to resolve its position via `getLocationOnScreen()` on the EDT
 * and throws [java.awt.IllegalComponentStateException] if the hosting view stops showing or is disposed.
 */
@Suppress("TooGenericExceptionCaught")
internal fun installSuppressingContextMenu(browser: Browser) {
    try {
        browser.set(
            ShowContextMenuCallback::class.java,
            ShowContextMenuCallback { _, tell ->
                closeContextMenuQuietly(tell)
            },
        )
    } catch (e: Exception) {
        logger.warn(LogCategory.BROWSER, "Could not install default suppressing context-menu callback", error = e)
    }
}

/**
 * Answers Chromium, and never throws while doing it.
 *
 * `close()` can fail — the request already answered, or the browser torn down mid-callback — and it
 * is called from a `finally` on a JxBrowser thread, so an escaping exception there would be exactly
 * the kind of uncaught, off-EDT throw this file exists to remove.
 */
@Suppress("TooGenericExceptionCaught") // See installPopupWindowContextMenu - Error must propagate.
internal fun closeContextMenuQuietly(tell: ShowContextMenuCallback.Action) {
    try {
        tell.close()
    } catch (e: Exception) {
        logger.warn(LogCategory.BROWSER, "Could not answer the context-menu callback", error = e)
    }
}
