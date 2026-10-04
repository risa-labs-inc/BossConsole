package ai.rever.boss.updater

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
internal fun defaultAutoUpdateEnabled(
    buildType: String? =
        if (System.getProperty("boss.dev.mode").toBoolean()) "debug" else System.getProperty("boss.build.type"),
    testAutoUpdate: Boolean = System.getProperty("boss.autoUpdate.test").toBoolean(),
): Boolean = testAutoUpdate || buildType == "release"

actual object UpdateSettings {
    private val pluginOptOuts = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    actual val pluginAutoUpdateOptOuts: kotlinx.coroutines.flow.StateFlow<Set<String>> = pluginOptOuts

    actual fun setPluginAutomaticUpdates(
        pluginId: String,
        enabled: Boolean,
    ) {
        pluginOptOuts.value = if (enabled) pluginOptOuts.value - pluginId else pluginOptOuts.value + pluginId
        System.setProperty("boss.plugins.autoUpdate.optOuts", pluginOptOuts.value.joinToString(","))
    }

    internal fun restorePluginOptOuts(ids: Set<String>) {
        pluginOptOuts.value = ids
        System.setProperty("boss.plugins.autoUpdate.optOuts", ids.joinToString(","))
    }

    actual fun isPluginAutomaticUpdateEnabled(pluginId: String): Boolean {
        val optedOut = pluginId in pluginOptOuts.value
        return autoPluginUpdatesEnabled && !optedOut
    }

    private val automaticPlugins =
        kotlinx.coroutines.flow.MutableStateFlow(defaultAutoUpdateEnabled()).also {
            System.setProperty("boss.plugins.autoUpdate.enabled", it.value.toString())
        }
    actual val automaticPluginUpdates: kotlinx.coroutines.flow.StateFlow<Boolean> = automaticPlugins
    actual var autoPluginUpdatesEnabled: Boolean
        get() = automaticPlugins.value
        set(value) {
            System.setProperty("boss.plugins.autoUpdate.enabled", value.toString())
            automaticPlugins.value = value
        }

    private val automatic = kotlinx.coroutines.flow.MutableStateFlow(defaultAutoUpdateEnabled())
    actual val automaticUpdates: kotlinx.coroutines.flow.StateFlow<Boolean> = automatic
    actual var autoUpdateEnabled: Boolean
        get() = automatic.value
        set(value) {
            automatic.value = value
        }

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
     * Version string suppressed after the user dismissed it or installation was
     * refused because this device does not meet its OS requirement.
     */
    @Volatile
    actual var lastDismissedVersion: String? = null

    /**
     * Version string for the newest release notes viewed by the user.
     */
    @Volatile
    actual var lastSeenReleaseVersion: String? = null
}

/**
 * Serializable data class for persisting update settings
 */
@Serializable
data class UpdateSettingsData(
    val pluginAutoUpdateOptOuts: Set<String> = emptySet(),
    val autoPluginUpdatesEnabled: Boolean = defaultAutoUpdateEnabled(),
    val autoUpdateEnabled: Boolean = defaultAutoUpdateEnabled(),
    val autoCheckEnabled: Boolean = true,
    val checkIntervalHours: Long = 6,
    val includePreReleases: Boolean = false,
    val lastDismissedVersion: String? = null,
    val lastSeenReleaseVersion: String? = null,
)

/** Filesystem destination used by [UpdateSettingsManager]. */
internal object UpdateSettingsFiles {
    /** The destination is resolved per access, so an override affects every later read and write. */
    @Volatile
    var settingsFileOverride: File? = null

    val settingsFile: File
        get() =
            settingsFileOverride
                ?: System.getProperty("boss.autoUpdate.settings.dir")?.let { File(it, "update-settings.json") }
                ?: BossDirectories.resolve("update-settings.json")
}

/**
 * Desktop implementation of update settings manager
 *
 * Settings are stored as JSON in ~/.boss/update-settings.json
 * Automatically loads settings on initialization.
 */
actual object UpdateSettingsManager {
    private val logger = BossLogger.forComponent("UpdateSettingsManager")
    private val settingsFile: File
        get() = UpdateSettingsFiles.settingsFile

    // Serialize snapshot-and-write operations so concurrent saves cannot persist stale settings.
    private val settingsWriteMutex = Mutex()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    // Accessing this JVM object runs init/loadSettingsSync exactly once before this method.
    // Keep this explicit initialization barrier: the coordinator must snapshot the persisted
    // marker, not UpdateSettings defaults. Re-running the load here would overwrite live edits.
    actual fun ensureLoaded() = Unit

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
                UpdateSettings.restorePluginOptOuts(settings.pluginAutoUpdateOptOuts)
                UpdateSettings.autoPluginUpdatesEnabled = settings.autoPluginUpdatesEnabled
                UpdateSettings.autoUpdateEnabled = settings.autoUpdateEnabled
                UpdateSettings.autoCheckEnabled = settings.autoCheckEnabled
                UpdateSettings.checkIntervalHours = settings.checkIntervalHours
                UpdateSettings.includePreReleases = settings.includePreReleases
                UpdateSettings.lastDismissedVersion = settings.lastDismissedVersion
                UpdateSettings.lastSeenReleaseVersion = settings.lastSeenReleaseVersion

                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded update settings",
                    mapOf(
                        "autoCheck" to settings.autoCheckEnabled,
                        "includePreReleases" to settings.includePreReleases,
                    ),
                )
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
    actual suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            settingsWriteMutex.withLock {
                try {
                    val settings =
                        UpdateSettingsData(
                            pluginAutoUpdateOptOuts = UpdateSettings.pluginAutoUpdateOptOuts.value,
                            autoPluginUpdatesEnabled = UpdateSettings.autoPluginUpdatesEnabled,
                            autoUpdateEnabled = UpdateSettings.autoUpdateEnabled,
                            autoCheckEnabled = UpdateSettings.autoCheckEnabled,
                            checkIntervalHours = UpdateSettings.checkIntervalHours,
                            includePreReleases = UpdateSettings.includePreReleases,
                            lastDismissedVersion = UpdateSettings.lastDismissedVersion,
                            lastSeenReleaseVersion = UpdateSettings.lastSeenReleaseVersion,
                        )

                    val content = json.encodeToString(UpdateSettingsData.serializer(), settings)
                    settingsFile.atomicWriteText(content)

                    logger.debug(
                        LogCategory.SYSTEM,
                        "Saved update settings",
                        mapOf("path" to settingsFile.absolutePath),
                    )
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Failed to save update settings", error = e)
                }
            }
        }
}
