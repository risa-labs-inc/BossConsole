package ai.rever.boss.plugin.browser

import androidx.compose.runtime.compositionLocalOf
import java.awt.Window

// User agent settings
object BrowserSettings {
    var userAgent: String? = null
    var customUserAgent: String? = null
    var currentProfile: String = "browser-profile"
    val availableProfiles = mutableListOf("browser-profile")

    // Browser initialization retry settings (configurable via Settings)
    var maxInitRetries: Int = 3
    var maxRecoveryAttempts: Int = 3

    // JavaScript dialog settings (configurable via Settings > Browser)
    // Due to JxBrowser threading limitations, dialogs must be auto-handled
    enum class JsConfirmBehavior { AUTO_CONFIRM, AUTO_CANCEL }
    var jsConfirmBehavior: JsConfirmBehavior = JsConfirmBehavior.AUTO_CONFIRM
    var jsPromptDefaultValue: String = ""  // Empty string or user-configured default
    var jsPromptUsePageDefault: Boolean = true  // Use page's default value if true, else use jsPromptDefaultValue

    // Secret Manager settings (configurable via Settings > Browser > Secret Manager)
    var discretePasswordFill: Boolean = true  // Hide filled passwords with blur effect for privacy

    // Tab sharing (configurable via Settings > Browser > Tab Sharing). OFF by default:
    // the co-browse share (QR) button stays hidden in the browser toolbar until the
    // user opts in. The toolbar is rendered by the fluck-browser plugin in a separate
    // classloader, so the value is mirrored to a JVM system property the plugin reads.
    const val SHOW_SHARE_BUTTON_PROP = "boss.fluck.showShareButton"
    var showShareButton: Boolean = false
        set(value) {
            field = value
            System.setProperty(SHOW_SHARE_BUTTON_PROP, value.toString())
        }
}

/**
 * CompositionLocal providing the current AWT Window for this Compose window.
 * Used by JxBrowser to get the correct window handle for BrowserViewState.
 *
 * This fixes the multi-window crash where browsers in window 2 would reference
 * window 1's handle because getValidComposeWindow() returned the first window.
 */
val LocalAwtWindow = compositionLocalOf<Window?> { null }
