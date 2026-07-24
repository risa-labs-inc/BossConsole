package ai.rever.boss.window

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
 * Desktop implementation of window appearance settings manager.
 * Manages loading and saving of window appearance settings.
 * Follows the BOSS settings management pattern with:
 * - JSON persistence in ~/.boss/window-appearance-settings.json
 * - Automatic directory creation
 * - Synchronous load on init, asynchronous save
 * - Graceful error handling with fallback to defaults
 */
actual object WindowAppearanceSettingsManager {
    private val logger = BossLogger.forComponent("WindowAppearanceSettingsManager")
    private val settingsFile = BossDirectories.resolve("window-appearance-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val _currentSettings = MutableStateFlow(WindowAppearanceSettings())
    actual val currentSettings: StateFlow<WindowAppearanceSettings> = _currentSettings.asStateFlow()

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * Load settings synchronously on startup.
     * If file doesn't exist, uses platform-specific defaults and saves them.
     */
    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val settings = json.decodeFromString<WindowAppearanceSettings>(content)
                _currentSettings.value = settings
                logger.debug(LogCategory.SYSTEM, "Loaded settings", mapOf("path" to settingsFile.absolutePath))
            } else {
                // First run - create default settings file with platform-specific defaults
                val defaults = getDefaultSettings()
                _currentSettings.value = defaults

                // Save default settings to file
                try {
                    val content = json.encodeToString(WindowAppearanceSettings.serializer(), defaults)
                    settingsFile.writeText(content)
                    logger.debug(LogCategory.SYSTEM, "Created default settings", mapOf("path" to settingsFile.absolutePath))
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Could not write default settings file", error = e)
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load settings", error = e)
            _currentSettings.value = getDefaultSettings()
        }
    }

    /**
     * Save current settings to disk asynchronously.
     */
    private suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(WindowAppearanceSettings.serializer(), _currentSettings.value)
                settingsFile.writeText(content)
                logger.debug(LogCategory.SYSTEM, "Settings saved", mapOf("path" to settingsFile.absolutePath))
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Failed to save settings", error = e)
            }
        }

    /**
     * Update the current settings and save to disk asynchronously.
     */
    actual suspend fun updateSettings(settings: WindowAppearanceSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    actual fun getDefaultSettings(): WindowAppearanceSettings {
        val os = System.getProperty("os.name").lowercase()
        val isMacOS = os.contains("mac")
        // Show title bar on macOS, hide on Linux/Windows
        return WindowAppearanceSettings(showTitleBar = isMacOS)
    }
}
