package ai.rever.boss.run

import kotlinx.coroutines.flow.StateFlow

/**
 * Expect declaration for RunConfigurationManager.
 * Manages run configurations with persistence.
 * Platform-specific implementations handle file I/O.
 */
expect object RunConfigurationManager {
    /**
     * Current run configuration settings.
     */
    val currentSettings: StateFlow<RunConfigurationSettings>

    /**
     * List of auto-detected run configurations from project scan.
     */
    val detectedConfigurations: StateFlow<List<RunConfiguration>>

    /**
     * Whether a project scan is currently in progress.
     */
    val isScanning: StateFlow<Boolean>

    /**
     * Last error that occurred during scanning or configuration operations.
     * Null if no error. UI should observe this to display errors to users.
     */
    val lastError: StateFlow<String?>

    /**
     * Scan a project directory for runnable entry points.
     * Clears previous detected configs before scanning.
     */
    suspend fun scanProject(projectPath: String)

    /**
     * Clear the last error.
     */
    suspend fun clearError()

    /**
     * Add a new run configuration.
     */
    suspend fun addConfiguration(config: RunConfiguration)

    /**
     * Remove a run configuration by ID.
     */
    suspend fun removeConfiguration(configId: String)

    /**
     * Update an existing run configuration.
     */
    suspend fun updateConfiguration(config: RunConfiguration)

    /**
     * Clear all detected configurations.
     */
    suspend fun clearDetected()

    /**
     * Save current settings to disk.
     */
    suspend fun saveSettings()
}
