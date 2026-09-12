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
    var jsPromptDefaultValue: String = "" // Empty string or user-configured default
    var jsPromptUsePageDefault: Boolean = true // Use page's default value if true, else use jsPromptDefaultValue

    // Secret Manager settings (configurable via Settings > Browser > Secret Manager).
    //
    // Mirrored to a JVM system property for the same reason [SHOW_SHARE_BUTTON_PROP] is: the
    // credential fill moved out of the host and into the fluck-browser plugin, which loads in a
    // separate classloader and cannot read this object. Without the mirror the Settings toggle
    // would still flip a field nothing reads - a switch that silently does nothing.
    const val DISCRETE_PASSWORD_FILL_PROP = "boss.fluck.discretePasswordFill"
    var discretePasswordFill: Boolean = true // Hide filled passwords with blur effect for privacy
        set(value) {
            field = value
            System.setProperty(DISCRETE_PASSWORD_FILL_PROP, value.toString())
        }

    // Offer a generated password on a signup or change-password field, and save it to Secret
    // Manager when the user takes it. ON by default: a password manager that has to be switched on
    // first is one most people never find, and the alternative is the user inventing a password
    // that never gets stored anywhere.
    const val SUGGEST_PASSWORDS_PROP = "boss.fluck.suggestPasswords"
    var suggestPasswords: Boolean = true
        set(value) {
            field = value
            System.setProperty(SUGGEST_PASSWORDS_PROP, value.toString())
        }

    // Offer to save or update a credential the user typed, after a login that looks like it
    // worked. ON by default, for the same reason. Note what turning this OFF does: the plugin
    // uninstalls its page-event script entirely, so nothing observes a submit at all.
    const val OFFER_TO_SAVE_PASSWORDS_PROP = "boss.fluck.offerToSavePasswords"
    var offerToSavePasswords: Boolean = true
        set(value) {
            field = value
            System.setProperty(OFFER_TO_SAVE_PASSWORDS_PROP, value.toString())
        }

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

    // Downloads (configurable via Settings > Browser > Downloads). ON by default: BOSS asks
    // for consent before saving a file with a recognized executable extension. FluckEngine
    // reads this field directly at download time rather than caching a copy, so toggling it
    // here takes effect for the next download without an application restart. No system
    // property mirror is needed - unlike the toolbar toggles above, the download handler
    // lives in the host itself, not in a separately classloaded plugin. @Volatile because the
    // write happens on the Compose UI thread and the read on the JxBrowser download callback
    // thread, with no happens-before edge between them: the annotation is what makes the
    // "applies to the next download" claim safe rather than a race.
    @Volatile
    var warnForExecutables: Boolean = true

    init {
        // Publish the defaults up front. A Kotlin property initializer does not run through the
        // custom setter, so without this the properties stay unset until the settings file is
        // loaded - and a first launch with no settings file, or a failed load, would leave the
        // plugin reading nothing. It defaults `discretePasswordFill` back to true on its own, but
        // relying on two places to agree on a privacy default is how they drift apart.
        System.setProperty(DISCRETE_PASSWORD_FILL_PROP, discretePasswordFill.toString())
        System.setProperty(SHOW_SHARE_BUTTON_PROP, showShareButton.toString())
        // These two matter more than the others here: they default to ON, so a plugin reading an
        // absent property has to guess which way an unset value falls. Publishing them removes the
        // guess.
        System.setProperty(SUGGEST_PASSWORDS_PROP, suggestPasswords.toString())
        System.setProperty(OFFER_TO_SAVE_PASSWORDS_PROP, offerToSavePasswords.toString())
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
