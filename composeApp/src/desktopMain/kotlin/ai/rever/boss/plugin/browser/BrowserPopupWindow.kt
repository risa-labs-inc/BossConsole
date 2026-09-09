package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import javax.swing.SwingUtilities

private val logger = BossLogger.forComponent("BrowserPopupWindow")

/**
 * Opens a Swing window ([javax.swing.JFrame]) to display a popup browser (e.g. OAuth / payment).
 * Used by both `BrowserHandleImpl` and `BrowserFunctions`.
 */
@Suppress("TooGenericExceptionCaught")
internal fun showPopupInWindow(
    popupBrowser: Browser,
    bounds: com.teamdev.jxbrowser.ui.Rect,
) {
    SwingUtilities.invokeLater {
        try {
            val frame = javax.swing.JFrame()
            val subscriptions = mutableListOf<com.teamdev.jxbrowser.event.Subscription>()

            frame.title = "Popup"
            frame.defaultCloseOperation = javax.swing.JFrame.DISPOSE_ON_CLOSE
            frame.iconImages = ai.rever.boss.window.BossWindowIcon.images
            frame.setLocation(bounds.origin().x(), bounds.origin().y())
            frame.setSize(bounds.size().width(), bounds.size().height())

            NativeFileDialogs.installOn(popupBrowser)

            val browserView =
                com.teamdev.jxbrowser.view.swing.BrowserView
                    .newInstance(popupBrowser)
            frame.contentPane.add(browserView)

            installPopupWindowChrome(popupBrowser, browserView)

            subscriptions +=
                popupBrowser.on(com.teamdev.jxbrowser.browser.event.TitleChanged::class.java) { event ->
                    SwingUtilities.invokeLater { frame.title = event.title() }
                }

            subscriptions +=
                popupBrowser.on(com.teamdev.jxbrowser.browser.event.BrowserClosed::class.java) {
                    SwingUtilities.invokeLater {
                        subscriptions.forEach { runCatching { it.unsubscribe() } }
                        frame.dispose()
                    }
                }

            frame.addWindowListener(
                object : java.awt.event.WindowAdapter() {
                    override fun windowClosing(e: java.awt.event.WindowEvent?) {
                        subscriptions.forEach { runCatching { it.unsubscribe() } }
                        if (!popupBrowser.isClosed) {
                            popupBrowser.close()
                        }
                    }
                },
            )

            frame.isVisible = true
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error creating popup window", error = e)
            if (!popupBrowser.isClosed) {
                popupBrowser.close()
            }
        }
    }
}

