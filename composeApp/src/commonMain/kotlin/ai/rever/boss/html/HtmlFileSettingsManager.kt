package ai.rever.boss.html

import kotlinx.coroutines.flow.StateFlow

/**
 * Manager for HTML file open settings.
 * Uses expect/actual pattern for platform-specific persistence.
 */
expect object HtmlFileSettingsManager {
    /** Current settings as a reactive StateFlow */
    val currentSettings: StateFlow<HtmlFileSettings>

    /** Save current settings to disk */
    suspend fun saveSettings()

    /** Update settings and persist to disk */
    suspend fun updateSettings(settings: HtmlFileSettings)

    /** Set the open mode preference */
    suspend fun setOpenMode(mode: HtmlFileOpenMode)

    /** Reset settings to default (ALWAYS_ASK) */
    suspend fun resetToDefault()
}
