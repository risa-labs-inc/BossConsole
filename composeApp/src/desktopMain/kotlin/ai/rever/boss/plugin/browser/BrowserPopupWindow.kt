package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.BossWindowIcon
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.event.BrowserClosed
import com.teamdev.jxbrowser.browser.event.TitleChanged
import com.teamdev.jxbrowser.event.Subscription
import com.teamdev.jxbrowser.ui.Rect
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

private val logger = BossLogger.forComponent("BrowserPopupWindow")

/**
 * Opens a Swing window ([JFrame]) to display a popup browser (e.g. OAuth / payment).
 * Used by both `BrowserHandleImpl` and `BrowserFunctions`.
 */
@Suppress("TooGenericExceptionCaught")
internal fun openBrowserPopupWindow(
    popupBrowser: Browser,
    bounds: Rect,
) {
    SwingUtilities.invokeLater {
        try {
            val frame = JFrame()
            val subscriptions = mutableListOf<Subscription>()

            frame.title = "Popup"
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.iconImages = BossWindowIcon.images
            frame.setLocation(bounds.origin().x(), bounds.origin().y())
            frame.setSize(bounds.size().width(), bounds.size().height())

            // Claim file-dialog callbacks before BrowserView installs its Swing JFileChooser defaults.
            NativeFileDialogs.installOn(popupBrowser)

            val browserView =
                com.teamdev.jxbrowser.view.swing.BrowserView
                    .newInstance(popupBrowser)
            frame.contentPane.add(browserView)

            // A disposed popup has no screen location: the built-in SuggestionsPopup can crash
            // the EDT while positioning itself (BossConsole-Releases#17).
            installPopupWindowChrome(popupBrowser, browserView)

            subscriptions +=
                popupBrowser.on(TitleChanged::class.java) { event ->
                    SwingUtilities.invokeLater { frame.title = event.title() }
                }

            subscriptions +=
                popupBrowser.on(BrowserClosed::class.java) {
                    SwingUtilities.invokeLater {
                        subscriptions.forEach { runCatching { it.unsubscribe() } }
                        frame.dispose()
                    }
                }

            frame.addWindowListener(
                object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent?) {
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
