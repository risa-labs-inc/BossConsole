package ai.rever.boss.components.dialogs

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Desktop
import java.net.URI

private val urlOpenerLogger = BossLogger.forComponent("DesktopUrlOpener")

/**
 * Desktop implementation of URL opening
 * Opens URL in system browser (later can be enhanced to open in Fluck)
 */
actual fun openUrlInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI.create(url))
                if (url.contains("/register/mobile")) {
                    urlOpenerLogger.debug(LogCategory.BROWSER, "Opened WebAuthn registration URL in system browser")
                } else if (url.contains("/auth/mobile")) {
                    urlOpenerLogger.debug(LogCategory.BROWSER, "Opened WebAuthn authentication URL in system browser")
                } else {
                    urlOpenerLogger.debug(LogCategory.BROWSER, "Opened URL in system browser")
                }
            } else {
                urlOpenerLogger.warn(LogCategory.BROWSER, "Desktop browse not supported")
            }
        } else {
            urlOpenerLogger.warn(LogCategory.BROWSER, "Desktop not supported")
        }
    } catch (e: Exception) {
        urlOpenerLogger.warn(LogCategory.BROWSER, "Failed to open URL in browser", error = e)
    }
}
