package ai.rever.boss.components.windows

import androidx.compose.runtime.Composable

/**
 * Settings window composable.
 *
 * @param onClose Called when the window is closed
 * @param initialSection Optional section name to navigate to on open (e.g., "TERMINAL", "FLUCK")
 * @param focusRequest Bumped by `BossAppState.openSettings` whenever Settings is asked for while
 *   this window is already open. Each new value raises the window: deiconify if minimised, then to
 *   the front and focused. A counter rather than a flag because the request has to be repeatable -
 *   the bug this fixes was a boolean that was already set, so the second click did nothing.
 */
@Composable
expect fun SettingsWindow(
    onClose: () -> Unit,
    initialSection: String? = null,
    focusRequest: Int = 0,
)
