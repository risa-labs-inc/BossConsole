package ai.rever.boss.updater

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of update settings
 *
 * Controls automatic update checking behavior.
 * Settings are persisted to ~/.boss/update-settings.json
 *
 * Fields are written from UI/IO coroutines (e.g. [UpdateManager.dismissVersion])
 * and read from Dispatchers.Default (periodic checks), hence @Volatile for
 * cross-thread visibility. Each field is an independent flag — no invariant
 * spans multiple fields, so per-field volatility is sufficient.
 */
actual object UpdateSettings {
    /**
     * Whether automatic update checks are enabled
     * Default: true (preserves current behavior)
     */
    @Volatile
    actual var autoCheckEnabled: Boolean = true

    /**
     * Interval between automatic update checks in hours
     * Default: 6 hours
     */
    @Volatile
    actual var checkIntervalHours: Long = 6

    /**
     * Whether to include pre-release versions in update checks
     * Default: false (stable users won't see prereleases unless they opt in)
     */
    @Volatile
    actual var includePreReleases: Boolean = false

    /**
     * Version string last dismissed by the user (update prompt suppressed for it)
     * Default: null (nothing dismissed)
     */
    @Volatile
    actual var lastDismissedVersion: String? = null
}

/**
 * Serializable data class for persisting update settings
 */
@Serializable
data class UpdateSettingsData(
    val autoCheckEnabled: Boolean = true,
    val checkIntervalHours: Long = 6,
    val includePreReleases: Boolean = false,
    val lastDismissedVersion: String? = null
)

/**
 * Desktop implementation of update settings manager
 *
 * Settings are stored as JSON in ~/.boss/update-settings.json
 * Automatically loads settings on initialization.
 */
actual object UpdateSettingsManager {
    private val logger = BossLogger.forComponent("UpdateSettingsManager")
    private val settingsFile = BossDirectories.resolve("update-settings.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * Load settings from disk synchronously
     * Called during initialization to restore user preferences
     */
    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val settings = json.decodeFromString<UpdateSettingsData>(content)

                // Apply loaded settings
                UpdateSettings.autoCheckEnabled = settings.autoCheckEnabled
                UpdateSettings.checkIntervalHours = settings.checkIntervalHours
                UpdateSettings.includePreReleases = settings.includePreReleases
                UpdateSettings.lastDismissedVersion = settings.lastDismissedVersion

                logger.debug(LogCategory.SYSTEM, "Loaded update settings", mapOf(
                    "autoCheck" to settings.autoCheckEnabled,
                    "includePreReleases" to settings.includePreReleases
                ))
            } else {
                logger.debug(LogCategory.SYSTEM, "No saved update settings found, using defaults")
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load update settings", error = e)
            // Continue with defaults
        }
    }

    /**
     * Save current settings to disk
     * Should be called whenever settings are changed in the UI
     */
    actual suspend fun saveSettings() = withContext(Dispatchers.IO) {
        try {
            val settings = UpdateSettingsData(
                autoCheckEnabled = UpdateSettings.autoCheckEnabled,
                checkIntervalHours = UpdateSettings.checkIntervalHours,
                includePreReleases = UpdateSettings.includePreReleases,
                lastDismissedVersion = UpdateSettings.lastDismissedVersion
            )

            val content = json.encodeToString(UpdateSettingsData.serializer(), settings)
            settingsFile.writeText(content)

            logger.debug(LogCategory.SYSTEM, "Saved update settings", mapOf("path" to settingsFile.absolutePath))
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to save update settings", error = e)
        }
    }
}
