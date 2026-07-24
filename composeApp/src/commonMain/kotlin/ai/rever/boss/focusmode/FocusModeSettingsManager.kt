package ai.rever.boss.focusmode

import kotlinx.coroutines.flow.StateFlow

/**
 * Expect declaration for FocusModeSettingsManager.
 * Platform-specific implementations handle file I/O and persistence.
 */
expect object FocusModeSettingsManager {
    val currentSettings: StateFlow<FocusModeSettings>

    suspend fun saveSettings()

    suspend fun updateSettings(settings: FocusModeSettings)

    suspend fun toggleFocusMode()

    suspend fun enableFocusMode()

    suspend fun disableFocusMode()

    suspend fun resetToDefault()
}
