package ai.rever.boss.window

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal providing the browser profile ID for the current Compose window.
 * This determines which browser session (cookies, storage, etc.) is used by browsers opened in this window.
 */
val LocalBrowserProfileId =
    compositionLocalOf<String> {
        ai.rever.boss.plugin.browser.BrowserSettings.currentProfile
    }
