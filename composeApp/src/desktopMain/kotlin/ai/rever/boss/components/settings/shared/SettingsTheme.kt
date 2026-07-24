package ai.rever.boss.components.settings.shared

import ai.rever.boss.plugin.ui.BossThemeController
import androidx.compose.ui.graphics.Color

/**
 * Shared UI constants for settings panel - aligned with BossTerm's SettingsTheme.
 */
object SettingsTheme {
    // Getters (not stored vals) so the entire settings UI re-skins when the
    // active host theme changes — each member reads the current semantic token.
    val SurfaceColor: Color get() = BossThemeController.current.colors.panel          // Sidebar background
    val BackgroundColor: Color get() = BossThemeController.current.colors.ink         // Content area background
    val AccentColor: Color get() = BossThemeController.current.colors.signal          // Selection/highlight color
    val BorderColor: Color get() = BossThemeController.current.colors.line            // Border/divider color
    val TextPrimary: Color get() = BossThemeController.current.colors.textPrimary     // Primary text color
    val TextSecondary: Color get() = BossThemeController.current.colors.textSecondary // Secondary text color
    val TextMuted: Color get() = BossThemeController.current.colors.textMuted         // Muted text color
}
