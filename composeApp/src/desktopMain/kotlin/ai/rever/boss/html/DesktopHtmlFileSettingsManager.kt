package ai.rever.boss.html

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

/**
 * Desktop implementation of HtmlFileSettingsManager.
 * Persists settings to ~/.boss/html-file-settings.json
 *
 * Settings are loaded asynchronously on Dispatchers.IO to avoid blocking the main thread.
 * Default settings are provided immediately via StateFlow.
 */
@Suppress("MatchingDeclarationName", "TooGenericExceptionCaught")
actual object HtmlFileSettingsManager {
    private val logger = BossLogger.forComponent("HtmlFileSettingsManager")
    private val settingsFile = BossDirectories.resolve("html-file-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _currentSettings = MutableStateFlow(HtmlFileSettings())
    actual val currentSettings: StateFlow<HtmlFileSettings> = _currentSettings.asStateFlow()

    init {
        scope.launch {
            loadSettingsAsync()
        }
    }

    private suspend fun loadSettingsAsync() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()

                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    val settings = json.decodeFromString<HtmlFileSettings>(content)
                    _currentSettings.value = settings
                    logger.debug(LogCategory.UI, "Loaded HTML file settings")
                } else {
                    val content = json.encodeToString(HtmlFileSettings.serializer(), _currentSettings.value)
                    settingsFile.writeText(content)
                    logger.debug(LogCategory.UI, "Created default HTML file settings file")
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Error loading HTML file settings", error = e)
            }
        }

    actual suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(HtmlFileSettings.serializer(), _currentSettings.value)
                settingsFile.writeText(content)
                logger.debug(LogCategory.UI, "HTML file settings saved")
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Error saving HTML file settings", error = e)
            }
        }

    actual suspend fun updateSettings(settings: HtmlFileSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    actual suspend fun setOpenMode(mode: HtmlFileOpenMode) {
        updateSettings(_currentSettings.value.copy(openMode = mode))
    }

    actual suspend fun resetToDefault() {
        updateSettings(HtmlFileSettings())
    }
}
