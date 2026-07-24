package ai.rever.boss.components.windows

import androidx.compose.runtime.Composable

/**
 * Settings window composable.
 *
 * @param onClose Called when the window is closed
 * @param initialSection Optional section name to navigate to on open (e.g., "TERMINAL", "FLUCK")
 */
@Composable
expect fun SettingsWindow(
    onClose: () -> Unit,
    initialSection: String? = null,
)
