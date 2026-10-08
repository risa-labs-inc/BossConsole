package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.CleanupRunner
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
        var popupCleanup: BrowserPopupCleanup? = null
        try {
            val frame = JFrame()
            val subscriptions = mutableListOf<Subscription>()
            val cleanup =
                BrowserPopupCleanup(
                    unsubscribe = {
                        val releases = subscriptions.map { subscription -> { subscription.unsubscribe() } }
                        subscriptions.clear()
                        releases
                    },
                    detachView = { frame.contentPane.removeAll() },
                    disposeWindow = { frame.dispose() },
                    closeBrowser = { if (!popupBrowser.isClosed) popupBrowser.close() },
                )
            popupCleanup = cleanup

            frame.title = "Popup"
            frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
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
                    SwingUtilities.invokeLater { if (frame.isDisplayable) frame.title = event.title() }
                }

            subscriptions +=
                popupBrowser.on(BrowserClosed::class.java) {
                    SwingUtilities.invokeLater { cleanup.close() }
                }

            frame.addWindowListener(
                object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent?) {
                        cleanup.close()
                    }
                },
            )

            frame.isVisible = true
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error creating popup window", error = e)
            if (popupCleanup != null) {
                popupCleanup.close()
            } else {
                CleanupRunner("BrowserPopupWindow").run("close popup after window creation failure") {
                    if (!popupBrowser.isClosed) popupBrowser.close()
                }
            }
        }
    }
}
