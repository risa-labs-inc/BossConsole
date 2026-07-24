package ai.rever.boss.performance

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
 * Desktop implementation of Performance settings manager.
 * Follows the BOSS settings management pattern with:
 * - JSON persistence in ~/.boss/performance-settings.json
 * - Automatic directory creation
 * - Synchronous load on init, asynchronous save
 * - Graceful error handling with fallback to defaults
 */
actual object PerformanceSettingsManager {
    private val logger = BossLogger.forComponent("PerformanceSettingsManager")
    private val settingsFile = BossDirectories.resolve("performance-settings.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _currentSettings = MutableStateFlow(PerformanceSettings())
    actual val currentSettings: StateFlow<PerformanceSettings> = _currentSettings.asStateFlow()

    init {
        settingsFile.parentFile?.mkdirs()
        loadSettingsSync()
    }

    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val settings = json.decodeFromString<PerformanceSettings>(content)
                // Validate loaded settings to handle potentially corrupted files
                _currentSettings.value = settings.validated()
            } else {
                _currentSettings.value = PerformanceSettings()
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load performance settings - using defaults", error = e)
            _currentSettings.value = PerformanceSettings()
        }
    }

    actual suspend fun saveSettings() = withContext(Dispatchers.IO) {
        try {
            val content = json.encodeToString(PerformanceSettings.serializer(), _currentSettings.value)
            settingsFile.writeText(content)
        } catch (e: Exception) {
            // Settings save failed - not critical, will use in-memory settings
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to persist performance settings - keeping in-memory only",
                error = e,
            )
        }
    }

    actual suspend fun updateSettings(settings: PerformanceSettings) {
        // Validate settings to ensure values are within valid ranges
        _currentSettings.value = settings.validated()
        saveSettings()
    }

    actual suspend fun toggleMonitoring() {
        val current = _currentSettings.value
        updateSettings(current.copy(enabled = !current.enabled))
    }

    actual suspend fun toggleIndicator() {
        val current = _currentSettings.value
        updateSettings(current.copy(showIndicator = !current.showIndicator))
    }

    actual suspend fun resetToDefault() {
        updateSettings(PerformanceSettings())
    }
}
