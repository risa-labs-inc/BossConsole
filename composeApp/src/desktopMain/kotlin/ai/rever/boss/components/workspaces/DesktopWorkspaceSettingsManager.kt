package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import ai.rever.boss.utils.renameAsideCorrupt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of WorkspaceSettingsManager.
 * Persists settings to ~/.boss/workspace-settings.json
 */
actual object WorkspaceSettingsManager {
    private val logger = BossLogger.forComponent("WorkspaceSettingsManager")

    /**
     * The production settings path, captured once so [resetForTesting] can restore it without
     * re-deriving the literal at every call site.
     */
    private val defaultSettingsFile = BossDirectories.resolve("workspace-settings.json")

    /**
     * Overridable so hermetic tests exercise the real read/write path without touching
     * `~/.boss`, as [ai.rever.boss.run.RunConfigurationManager] does. Restored by
     * [resetForTesting] callers; production code never reassigns it.
     */
    @Volatile
    internal var settingsFile: File = defaultSettingsFile
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Seeded at the CURRENT version, so 0 means exactly one thing: a key absent from a
    // file on disk. An in-memory default carrying 0 would re-arm the 0 -> 1 migration
    // for the next launch as soon as anything persisted it through updateSettings.
    private val _currentSettings =
        MutableStateFlow(WorkspaceSettings(settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION))
    actual val currentSettings: StateFlow<WorkspaceSettings> = _currentSettings.asStateFlow()

    init {
        // Synchronous, like FocusModeSettingsManager, and for the same reason: this
        // object initialises on first access, and its first accessor is the startup
        // effect that immediately reads getDefaultWorkspace(). An async load loses that
        // race every time, so the compiled-in default would win over the stored choice -
        // on Windows that means applying the browser workspace over the layout of a user
        // who picked something else, or "none". One small JSON file, read once.
        loadSettingsSync()
    }

    private fun loadSettingsSync() {
        try {
            settingsFile.parentFile?.mkdirs()

            if (!settingsFile.exists()) {
                writeSettings(_currentSettings.value)
                logger.debug(LogCategory.SYSTEM, "Created default settings file")
                return
            }

            val settings = json.decodeFromString<WorkspaceSettings>(settingsFile.readText())
            val migrated = WorkspaceSettingsMigrations.migrate(settings)
            _currentSettings.value = migrated ?: settings
            if (migrated == null) {
                logger.debug(LogCategory.SYSTEM, "Loaded settings")
                return
            }

            writeSettings(migrated)
            // INFO only when a default actually moved. Every pre-v1 file is stamped, so
            // logging the stamp at INFO would put a "Migrated" line on every existing
            // install of every platform while nothing changed.
            if (migrated.defaultWorkspaceId != settings.defaultWorkspaceId) {
                logger.info(
                    LogCategory.SYSTEM,
                    "Migrated default workspace",
                    mapOf(
                        "from" to settings.defaultWorkspaceId,
                        "to" to migrated.defaultWorkspaceId,
                    ),
                )
            } else {
                logger.debug(LogCategory.SYSTEM, "Stamped workspace settings version")
            }
        } catch (e: SerializationException) {
            // Corrupt content, not a read error: move the bad file aside so it is kept for
            // inspection instead of being re-failed on every launch or overwritten by the next
            // save, and write a fresh default so this launch self-heals.
            logger.error(
                LogCategory.SYSTEM,
                "Workspace settings file is corrupt, resetting to defaults",
                decodeFailure(e),
            )
            if (!settingsFile.renameAsideCorrupt()) {
                logger.warn(LogCategory.SYSTEM, "Workspace settings file not moved aside; overwriting it")
            }
            val defaultSettings = WorkspaceSettings(settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION)
            _currentSettings.value = defaultSettings
            writeSettings(defaultSettings)
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error loading settings", error = e)
        }
    }

    /**
     * Reset manager state and optionally redirect [settingsFile] to [testFile]; with no
     * argument, restore [defaultSettingsFile]. Call only when no save is in flight, and
     * always finish with a no-argument call, so the singleton is left where the app and
     * other tests expect it. Mirrors [ai.rever.boss.run.RunConfigurationManager].
     */
    internal fun resetForTesting(testFile: File? = null) {
        settingsFile = testFile ?: defaultSettingsFile
        _currentSettings.value = WorkspaceSettings(settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION)
        loadSettingsSync()
    }

    private fun writeSettings(settings: WorkspaceSettings) {
        try {
            settingsFile.atomicWriteText(json.encodeToString(WorkspaceSettings.serializer(), settings))
            logger.debug(LogCategory.SYSTEM, "Settings saved")
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error saving settings", error = e)
        }
    }

    // One write path. The load runs it on whatever thread touched this object first
    // (see init); every other caller gets it off the IO dispatcher.
    actual suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            writeSettings(_currentSettings.value)
        }

    actual suspend fun updateSettings(settings: WorkspaceSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    actual suspend fun setDefaultWorkspaceId(workspaceId: String) {
        // Stamp the schema version too: an explicit choice must never be rewritten by a
        // migration on the next launch. maxOf, so an older build writing over a newer
        // file cannot stamp the version backwards and re-arm a step that already ran.
        updateSettings(
            _currentSettings.value.copy(
                defaultWorkspaceId = workspaceId,
                settingsVersion =
                    maxOf(_currentSettings.value.settingsVersion, WorkspaceSettings.CURRENT_SETTINGS_VERSION),
            ),
        )
    }

    actual suspend fun setOnWorkspaceSwitch(behaviour: String) {
        // Same version stamp as setDefaultWorkspaceId, for the same reason: an explicit choice
        // must not be rewritten by a migration on the next launch.
        updateSettings(
            _currentSettings.value.copy(
                onWorkspaceSwitch = behaviour,
                settingsVersion =
                    maxOf(_currentSettings.value.settingsVersion, WorkspaceSettings.CURRENT_SETTINGS_VERSION),
            ),
        )
    }

    // Delegates rather than repeating the lookup: "ask" and "none" both mean "no workspace
    // to apply on my own", and a second copy of that rule is how they would come to disagree.
    actual fun getDefaultWorkspace(): LayoutWorkspace? =
        (_currentSettings.value.resolveOnProjectSelection() as? ProjectSelectionWorkspace.Apply)?.workspace
}
