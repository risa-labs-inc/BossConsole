package ai.rever.boss.performance

import kotlinx.coroutines.flow.StateFlow

/**
 * Expect declaration for PerformanceSettingsManager.
 * Platform-specific implementations handle file I/O and persistence.
 */
expect object PerformanceSettingsManager {
    val currentSettings: StateFlow<PerformanceSettings>
    suspend fun saveSettings()
    suspend fun updateSettings(settings: PerformanceSettings)
    suspend fun toggleMonitoring()
    suspend fun toggleIndicator()
    suspend fun resetToDefault()
}
