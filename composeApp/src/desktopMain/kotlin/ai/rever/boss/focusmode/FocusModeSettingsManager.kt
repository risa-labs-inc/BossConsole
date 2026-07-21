package ai.rever.boss.focusmode

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of Focus Mode settings manager.
 * Manages loading and saving of focus mode settings.
 * Follows the BOSS settings management pattern with:
 * - JSON persistence in ~/.boss/focus-mode-settings.json
 * - Automatic directory creation
 * - Synchronous load on init, asynchronous save
 * - Graceful error handling with fallback to defaults
 */
actual object FocusModeSettingsManager {
    private val logger = BossLogger.forComponent("FocusModeSettingsManager")
    private val settingsFile = BossDirectories.resolve("focus-mode-settings.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _currentSettings = MutableStateFlow(FocusModeSettings())
    actual val currentSettings: StateFlow<FocusModeSettings> = _currentSettings.asStateFlow()

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * Load settings synchronously on startup.
     * If file doesn't exist, uses default settings.
     */
    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val settings = json.decodeFromString<FocusModeSettings>(content)
                _currentSettings.value = settings
                logger.debug(LogCategory.SYSTEM, "Loaded settings", mapOf("path" to settingsFile.absolutePath))
            } else {
                // First run - create default settings file
                logger.debug(LogCategory.SYSTEM, "No settings file found, using defaults")
                val defaultSettings = FocusModeSettings()
                _currentSettings.value = defaultSettings

                // Save default settings to file
                try {
                    val content = json.encodeToString(FocusModeSettings.serializer(), defaultSettings)
                    settingsFile.writeText(content)
                    logger.debug(LogCategory.SYSTEM, "Created default settings file", mapOf("path" to settingsFile.absolutePath))
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Could not write default settings file", error = e)
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load settings, falling back to defaults", error = e)
            _currentSettings.value = FocusModeSettings()
        }
    }

    /**
     * Save current settings to disk asynchronously.
     */
    actual suspend fun saveSettings() = withContext(Dispatchers.IO) {
        try {
            val content = json.encodeToString(FocusModeSettings.serializer(), _currentSettings.value)
            settingsFile.writeText(content)
            logger.debug(LogCategory.SYSTEM, "Settings saved", mapOf("path" to settingsFile.absolutePath))
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to save settings", error = e)
        }
    }

    /**
     * Update the current settings and save to disk.
     */
    actual suspend fun updateSettings(settings: FocusModeSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * Toggle focus mode on/off.
     * Convenience method for keyboard shortcuts and UI toggles.
     */
    actual suspend fun toggleFocusMode() {
        val current = _currentSettings.value
        updateSettings(current.copy(enabled = !current.enabled))
    }

    /**
     * Enable focus mode.
     */
    actual suspend fun enableFocusMode() {
        val current = _currentSettings.value
        if (!current.enabled) {
            updateSettings(current.copy(enabled = true))
        }
    }

    /**
     * Disable focus mode.
     */
    actual suspend fun disableFocusMode() {
        val current = _currentSettings.value
        if (current.enabled) {
            updateSettings(current.copy(enabled = false))
        }
    }

    /**
     * Reset to default settings.
     */
    actual suspend fun resetToDefault() {
        updateSettings(FocusModeSettings())
    }
}
