package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.callback.BrowserCallback
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.callback.Advisable

/**
 * Claims JxBrowser's context-menu callback on Compose-hosted browsers that have no native menu.
 *
 * Without an explicit callback, the view installs JxBrowser's Swing menu. That menu can ask a
 * detached heavyweight component for its screen location and throw on the EDT. Full browser tabs
 * and popup windows install richer menus elsewhere; this fallback exists for auth and plugin-owned
 * Compose surfaces that have no Swing component to anchor one to.
 */
object BrowserContextMenuFallback {
    private val logger = BossLogger.forComponent("BrowserContextMenuFallback")

    /** Install before constructing the Compose BrowserView. Safe to call more than once. */
    @Suppress("TooGenericExceptionCaught")
    fun installOn(browser: Advisable<BrowserCallback>) {
        try {
            registerOn(browser)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not install context-menu fallback", error = e)
        }
    }

    /** Ungated registration seam for headless tests. */
    internal fun registerOn(target: Advisable<BrowserCallback>) {
        if (target.get(ShowContextMenuCallback::class.java).isPresent) return
        target.set(
            ShowContextMenuCallback::class.java,
            ShowContextMenuCallback { _, action ->
                action.close()
            },
        )
    }
}
