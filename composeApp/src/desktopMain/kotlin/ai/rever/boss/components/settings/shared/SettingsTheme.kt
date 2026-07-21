package ai.rever.boss.components.settings.shared

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkContentBackground
import BossDarkTextMuted
import BossDarkTextPrimary
import BossDarkTextSecondary
import androidx.compose.ui.graphics.Color

/**
 * Shared UI constants for settings panel - aligned with BossTerm's SettingsTheme.
 */
object SettingsTheme {
    // Getters (not stored vals) so the entire settings UI re-skins when the
    // active host theme changes — BossDark* now resolve through BossThemeController.
    val SurfaceColor: Color get() = BossDarkBackground       // Sidebar background
    val BackgroundColor: Color get() = BossDarkContentBackground // Content area background
    val AccentColor: Color get() = BossDarkAccent            // Selection/highlight color
    val BorderColor: Color get() = BossDarkBorder            // Border/divider color
    val TextPrimary: Color get() = BossDarkTextPrimary        // Primary text color
    val TextSecondary: Color get() = BossDarkTextSecondary    // Secondary text color
    val TextMuted: Color get() = BossDarkTextMuted            // Muted text color
}
