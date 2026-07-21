package ai.rever.boss.startup

import kotlinx.coroutines.flow.StateFlow

/**
 * Manager for startup-related settings.
 * Persists settings to ~/.boss/startup-settings.json
 */
expect object StartupSettingsManager {
    /**
     * Current startup settings as a StateFlow for reactive updates.
     */
    val currentSettings: StateFlow<StartupSettings>

    /**
     * Load settings from disk. Called automatically on first access.
     */
    suspend fun loadSettings()

    /**
     * Save current settings to disk.
     */
    suspend fun saveSettings()

    /**
     * Update settings and persist to disk.
     */
    suspend fun updateSettings(settings: StartupSettings)

    /**
     * Set workspace load timeout.
     */
    suspend fun setWorkspaceLoadTimeout(timeoutMs: Long)

    /**
     * Reset all settings to defaults.
     */
    suspend fun resetToDefault()
}
