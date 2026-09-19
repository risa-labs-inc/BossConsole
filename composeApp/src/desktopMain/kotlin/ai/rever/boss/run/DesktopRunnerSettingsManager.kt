package ai.rever.boss.run

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.run.MAX_RERUN_DELAY_MS
import ai.rever.boss.plugin.run.MIN_RERUN_DELAY_MS
import ai.rever.boss.utils.loadSettingsWithBackup
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
 * Desktop implementation of RunnerSettingsManager.
 * Persists settings to ~/.boss/runner-settings.json
 *
 * Settings are loaded asynchronously on Dispatchers.IO to avoid blocking the main thread.
 * Default settings are provided immediately via StateFlow.
 *
 * Issue #347: Runner settings persistence
 */
actual object RunnerSettingsManager {
    private val logger = BossLogger.forComponent("RunnerSettingsManager")
    private val settingsFile = BossDirectories.resolve("runner-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Coroutine scope for async operations - uses SupervisorJob so failures don't cancel other operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Default settings provided immediately, updated async when file is loaded
    private val _currentSettings = MutableStateFlow(RunnerSettings())
    actual val currentSettings: StateFlow<RunnerSettings> = _currentSettings.asStateFlow()

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

                val loaded =
                    settingsFile.loadSettingsWithBackup(
                        decode = { json.decodeFromString<RunnerSettings>(it) },
                        onCorruptPrimary = { e ->
                            logger.warn(LogCategory.SYSTEM, "Corrupt runner settings, trying backup", error = e)
                        },
                        onRestoredFromBackup = {
                            logger.info(LogCategory.SYSTEM, "Restored runner settings from backup")
                        },
                    )
                when {
                    loaded != null -> {
                        _currentSettings.value = loaded
                        logger.debug(LogCategory.SYSTEM, "Loaded settings")
                    }

                    // File present but neither it nor its backup decoded: keep the defaults already set.
                    settingsFile.exists() -> {
                        logger.warn(LogCategory.SYSTEM, "Runner settings and backup unreadable, using defaults")
                    }

                    else -> {
                        // Create default settings file
                        val content = json.encodeToString(RunnerSettings.serializer(), _currentSettings.value)
                        settingsFile.writeText(content)
                        logger.debug(LogCategory.SYSTEM, "Created default settings file")
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error loading settings", error = e)
                // Keep default settings on error
            }
        }

    /**
     * Save current settings to persistent storage.
     */
    actual suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(RunnerSettings.serializer(), _currentSettings.value)
                settingsFile.writeText(content)
                logger.debug(LogCategory.SYSTEM, "Settings saved")
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error saving settings", error = e)
            }
        }

    /**
     * Update settings and persist.
     */
    actual suspend fun updateSettings(settings: RunnerSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * Reset settings to defaults.
     */
    actual suspend fun resetToDefault() {
        updateSettings(RunnerSettings())
    }

    /**
     * Update only the terminal target setting.
     */
    actual suspend fun setTerminalTarget(target: RunnerTerminalTarget) {
        updateSettings(_currentSettings.value.copy(terminalTarget = target))
    }

    /**
     * Update only the focus on run setting.
     */
    actual suspend fun setFocusOnRun(enabled: Boolean) {
        updateSettings(_currentSettings.value.copy(focusOnRun = enabled))
    }

    /**
     * Update only the notify on exit setting.
     */
    actual suspend fun setNotifyOnExit(enabled: Boolean) {
        updateSettings(_currentSettings.value.copy(notifyOnExit = enabled))
    }

    /**
     * Update only the re-run delay setting.
     */
    actual suspend fun setRerunDelayMs(delayMs: Long) {
        val clampedDelay = delayMs.coerceIn(MIN_RERUN_DELAY_MS, MAX_RERUN_DELAY_MS)
        updateSettings(_currentSettings.value.copy(rerunDelayMs = clampedDelay))
    }
}
