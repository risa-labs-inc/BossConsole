package ai.rever.boss.components.windows

import ai.rever.boss.components.settings.search.SettingsHighlight
import androidx.compose.runtime.Composable

/**
 * Settings window composable.
 *
 * @param onClose Called when the window is closed
 * @param initialSection Optional section name to navigate to on open (e.g., "TERMINAL", "FLUCK")
 * @param focusRequest Bumped by `SettingsWindowState.open` whenever Settings is asked for while
 *   this window is already open. Each new value raises the window: deiconify if minimised, then to
 *   the front and focused. A counter rather than a flag because the request has to be repeatable -
 *   the bug this fixes was a boolean that was already set, so the second click did nothing.
 * @param sectionRequest Bumped whenever a caller names a section. Each new value applies
 *   [initialSection] to the open window, which a key on [initialSection] alone could not: asking
 *   twice for the same section leaves that string unchanged.
 */
@Composable
expect fun SettingsWindow(
    onClose: () -> Unit,
    initialSection: String? = null,
    focusRequest: Int = 0,
    sectionRequest: Int = 0,
    /** The row to point at once navigation lands, or null. See `SettingsWindowState.reveal`. */
    requestedHighlight: SettingsHighlight? = null,
    /**
     * Bumped once per request that could change [requestedHighlight], a clear included.
     *
     * The window keys its adopt effect on this rather than on the highlight's nonce, because null
     * carries no nonce - see `SettingsWindowState.highlightRequest`.
     */
    highlightRequest: Int = 0,
)
