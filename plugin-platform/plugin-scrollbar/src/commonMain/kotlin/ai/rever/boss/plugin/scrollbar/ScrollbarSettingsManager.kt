package ai.rever.boss.plugin.scrollbar

import kotlinx.coroutines.flow.StateFlow

/**
 * Expect declaration for scrollbar settings manager.
 * Platform-specific implementations handle persistence and default values.
 */
expect object ScrollbarSettingsManager {
    /**
     * Current settings state flow
     */
    val currentSettings: StateFlow<ScrollbarSettings>

    /**
     * Update scrollbar settings and save to disk asynchronously.
     */
    suspend fun updateSettings(settings: ScrollbarSettings)

    /**
     * Reset settings to defaults
     */
    suspend fun resetToDefault()

    /**
     * Get default settings
     */
    fun getDefaultSettings(): ScrollbarSettings
}
