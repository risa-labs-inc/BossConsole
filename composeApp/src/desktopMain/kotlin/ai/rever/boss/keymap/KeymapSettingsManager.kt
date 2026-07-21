package ai.rever.boss.keymap

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.presets.KeymapPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of KeymapSettingsManager.
 * Manages loading and saving of keyboard shortcut settings.
 * Follows the BOSS settings management pattern with:
 * - JSON persistence in ~/.boss/keymap-settings.json
 * - Automatic directory creation
 * - Synchronous load on init, asynchronous save
 * - Graceful error handling with fallback to defaults
 */
actual object KeymapSettingsManager {
    private val logger = BossLogger.forComponent("KeymapSettingsManager")
    private val settingsFile = BossDirectories.resolve("keymap-settings.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _currentSettings = MutableStateFlow<KeymapSettings>(KeymapPresets.getBOSSDefault())
    actual val currentSettings: StateFlow<KeymapSettings> = _currentSettings.asStateFlow()

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * Load settings synchronously on startup.
     * If file doesn't exist, uses default keymap.
     * Applies migration to add any new actions from presets.
     */
    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val loaded = json.decodeFromString<KeymapSettings>(content)
                logger.debug(LogCategory.SYSTEM, "Loaded keymap settings", mapOf("path" to settingsFile.absolutePath))

                // Apply migration to add any new actions from preset
                val migrated = migrateSettings(loaded)

                // Save if migration made changes
                if (migrated != loaded) {
                    try {
                        val migratedContent = json.encodeToString(KeymapSettings.serializer(), migrated)
                        settingsFile.writeText(migratedContent)
                        logger.debug(LogCategory.SYSTEM, "Migrated keymap settings saved")
                    } catch (e: Exception) {
                        logger.warn(LogCategory.SYSTEM, "Could not save migrated keymap settings", error = e)
                    }
                }

                _currentSettings.value = migrated
            } else {
                // First run - create default keymap file
                logger.debug(LogCategory.SYSTEM, "No keymap settings file found, creating default")
                val defaultSettings = KeymapPresets.getBOSSDefault()
                _currentSettings.value = defaultSettings

                // Save default settings to file
                try {
                    val content = json.encodeToString(KeymapSettings.serializer(), defaultSettings)
                    settingsFile.writeText(content)
                    logger.debug(LogCategory.SYSTEM, "Created default keymap settings file", mapOf("path" to settingsFile.absolutePath))
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Could not write default keymap settings file", error = e)
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to load keymap settings, using defaults", error = e)
            _currentSettings.value = KeymapPresets.getBOSSDefault()
        }
    }

    /**
     * Migrate settings by adding any new actions from the preset that are missing.
     * This ensures existing users get new keybindings added to presets while
     * preserving their customizations.
     *
     * @param loaded The loaded user settings
     * @return Migrated settings with any missing actions added from the preset
     */
    private fun migrateSettings(loaded: KeymapSettings): KeymapSettings {
        // Get the preset that matches user's presetName
        val presetShortcuts = when (loaded.presetName) {
            "VS Code" -> KeymapPresets.getVSCodePreset().shortcuts
            "IntelliJ IDEA" -> KeymapPresets.getIntelliJPreset().shortcuts
            "Emacs" -> KeymapPresets.getEmacsPreset().shortcuts
            else -> KeymapPresets.getBOSSDefault().shortcuts
        }

        // Find actions in preset that are missing from user settings
        val missingActions = presetShortcuts.filterKeys { actionId ->
            !loaded.shortcuts.containsKey(actionId)
        }

        if (missingActions.isEmpty()) {
            return loaded // No migration needed
        }

        logger.info(LogCategory.SYSTEM, "Migrating keymap settings", mapOf(
            "newActions" to missingActions.size,
            "actionIds" to missingActions.keys.joinToString()
        ))

        // Merge: user settings + missing actions from preset
        val mergedShortcuts = loaded.shortcuts + missingActions

        return loaded.copy(shortcuts = mergedShortcuts)
    }

    /**
     * Save current settings to disk asynchronously.
     */
    actual suspend fun saveSettings() = withContext(Dispatchers.IO) {
        try {
            val content = json.encodeToString(KeymapSettings.serializer(), _currentSettings.value)
            settingsFile.writeText(content)
            logger.debug(LogCategory.SYSTEM, "Keymap settings saved")
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to save keymap settings", error = e)
        }
    }

    /**
     * Update the current settings and save to disk.
     */
    actual suspend fun updateSettings(settings: KeymapSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * Load a preset keymap by name.
     */
    actual suspend fun loadPreset(presetName: String) {
        val preset = when (presetName) {
            "BOSS Default" -> KeymapPresets.getBOSSDefault()
            "VS Code" -> KeymapPresets.getVSCodePreset()
            "IntelliJ IDEA" -> KeymapPresets.getIntelliJPreset()
            "Emacs" -> KeymapPresets.getEmacsPreset()
            else -> {
                logger.warn(LogCategory.SYSTEM, "Unknown keymap preset, using BOSS Default", mapOf("presetName" to presetName))
                KeymapPresets.getBOSSDefault()
            }
        }
        updateSettings(preset)
    }

    /**
     * Reset to default BOSS keymap.
     */
    actual suspend fun resetToDefault() {
        updateSettings(KeymapPresets.getBOSSDefault())
    }

    /**
     * Import keymap from JSON string.
     * Returns null if import fails.
     */
    actual suspend fun importFromJson(jsonString: String): KeymapSettings? {
        return try {
            val settings = json.decodeFromString<KeymapSettings>(jsonString)
            updateSettings(settings)
            settings
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to import keymap settings", error = e)
            null
        }
    }

    /**
     * Export current keymap to JSON string.
     */
    actual fun exportToJson(): String {
        return json.encodeToString(KeymapSettings.serializer(), _currentSettings.value)
    }

    /**
     * Import keymap from file.
     * Returns null if import fails.
     */
    suspend fun importFromFile(file: File): KeymapSettings? = withContext(Dispatchers.IO) {
        try {
            val content = file.readText()
            importFromJson(content)
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to import keymap from file", error = e)
            null
        }
    }

    /**
     * Export current keymap to file.
     */
    suspend fun exportToFile(file: File) = withContext(Dispatchers.IO) {
        try {
            file.writeText(exportToJson())
            logger.debug(LogCategory.SYSTEM, "Exported keymap settings", mapOf("path" to file.absolutePath))
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to export keymap to file", error = e)
        }
    }
}
