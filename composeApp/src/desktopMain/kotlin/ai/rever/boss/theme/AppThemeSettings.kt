package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossThemes
import kotlinx.serialization.Serializable

/**
 * Persisted host theme preference. `appThemeId` matches a [BossThemes] id
 * ("operator", "daylight", "clean"). Stored at ~/.boss/app-theme-settings.json.
 */
@Serializable
data class AppThemeSettings(
    val appThemeId: String = BossThemes.DEFAULT_ID,
)
