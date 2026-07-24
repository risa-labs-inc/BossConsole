package ai.rever.boss.startup

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of StartupSettingsManager.
 * Persists settings to ~/.boss/startup-settings.json
 *
 * Settings are loaded asynchronously on Dispatchers.IO to avoid blocking the main thread.
 * Default settings are provided immediately via StateFlow.
 */
actual object StartupSettingsManager {
    private val logger = BossLogger.forComponent("StartupSettingsManager")
    private val settingsFile = BossDirectories.resolve("startup-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Coroutine scope for async operations - uses SupervisorJob so failures don't cancel other operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Default settings provided immediately, updated async when file is loaded
    private val _currentSettings = MutableStateFlow(StartupSettings())
    actual val currentSettings: StateFlow<StartupSettings> = _currentSettings.asStateFlow()

    init {
        // Load settings asynchronously to avoid blocking main thread
        scope.launch {
            loadSettingsAsync()
        }
    }

    /**
     * Load settings asynchronously on Dispatchers.IO.
     * Creates parent directories and default settings file if needed.
     */
    private suspend fun loadSettingsAsync() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()

                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    val settings = json.decodeFromString<StartupSettings>(content)
                    _currentSettings.value = settings
                    logger.debug(LogCategory.SYSTEM, "Loaded settings")
                } else {
                    // Create default settings file
                    val content = json.encodeToString(StartupSettings.serializer(), _currentSettings.value)
                    settingsFile.writeText(content)
                    logger.debug(LogCategory.SYSTEM, "Created default settings file")
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error loading settings", error = e)
                // Keep default settings on error
            }
        }

    /**
     * Load settings from disk. Called automatically on first access.
     */
    actual suspend fun loadSettings() = loadSettingsAsync()

    /**
     * Save current settings to persistent storage.
     */
    actual suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(StartupSettings.serializer(), _currentSettings.value)
                settingsFile.writeText(content)
                logger.debug(LogCategory.SYSTEM, "Settings saved")
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error saving settings", error = e)
            }
        }

    /**
     * Update settings and persist.
     */
    actual suspend fun updateSettings(settings: StartupSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * Set workspace load timeout.
     */
    actual suspend fun setWorkspaceLoadTimeout(timeoutMs: Long) {
        updateSettings(_currentSettings.value.copy(workspaceLoadTimeoutMs = timeoutMs))
    }

    /**
     * Reset settings to defaults.
     */
    actual suspend fun resetToDefault() {
        updateSettings(StartupSettings())
    }
}
