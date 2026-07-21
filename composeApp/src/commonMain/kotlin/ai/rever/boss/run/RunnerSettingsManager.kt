package ai.rever.boss.run

import kotlinx.coroutines.flow.StateFlow

/**
 * Manager for runner settings.
 * Handles persistence and retrieval of runner configuration.
 *
 * Issue #347: Runner settings management
 */
expect object RunnerSettingsManager {
    /**
     * Current runner settings as a reactive flow.
     */
    val currentSettings: StateFlow<RunnerSettings>

    /**
     * Save current settings to persistent storage.
     */
    suspend fun saveSettings()

    /**
     * Update settings and persist.
     */
    suspend fun updateSettings(settings: RunnerSettings)

    /**
     * Reset settings to defaults.
     */
    suspend fun resetToDefault()

    /**
     * Update only the terminal target setting.
     */
    suspend fun setTerminalTarget(target: RunnerTerminalTarget)

    /**
     * Update only the focus on run setting.
     */
    suspend fun setFocusOnRun(enabled: Boolean)

    /**
     * Update only the notify on exit setting.
     */
    suspend fun setNotifyOnExit(enabled: Boolean)

    /**
     * Update only the re-run delay setting.
     */
    suspend fun setRerunDelayMs(delayMs: Long)
}
