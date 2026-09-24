package ai.rever.boss.startup

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Desktop implementation of StartupSettingsManager.
 * Persists settings to ~/.boss/startup-settings.json
 *
 * Settings are loaded asynchronously on Dispatchers.IO to avoid blocking the main thread.
 * Default settings are provided immediately via StateFlow.
 *
 * Because that load is asynchronous, a change can land while it is still in flight - the
 * settings screen writes before the first disk read returns. The load is fenced by
 * [mutationEpoch]: it publishes its disk snapshot only while nothing has mutated the
 * in-memory state since the read started, so a stale snapshot is dropped rather than
 * clobbering the newer change. Last write wins, in memory and on disk.
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

    /**
     * Orders the async load against mutations. [mutationEpoch] is bumped and the new value
     * published inside this lock as one step, and the load's epoch check and its own publish
     * run under the same lock. That closes the window where a load checks the epoch, finds
     * it unchanged, and then overwrites a mutation that lands between the check and the
     * assignment: the two steps order completely, and the later one wins.
     */
    private val stateLock = Any()

    /** Serializes persistence so an older update cannot finish after a newer one. */
    private val persistenceLock = Mutex()

    /** Bumped before every in-memory mutation; guarded by [stateLock]. */
    private var mutationEpoch = 0L

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
            // Serialized with updateSettings under [persistenceLock]: without it an update can
            // publish and bump the epoch between this load's epoch snapshot and its disk read,
            // and the fence would then pass a snapshot that predates the update.
            persistenceLock.withLock {
                // Fence the read: every updateSettings that runs while it is in flight bumps
                // [mutationEpoch], which makes the snapshot this load ends up holding stale.
                // A stale snapshot is discarded by applyLoadedIfUnchanged below instead of
                // being published over the newer change the mutation already made.
                val epochAtStart = currentMutationEpoch()
                try {
                    settingsFile.parentFile?.mkdirs()

                    if (settingsFile.exists()) {
                        val content = settingsFile.readText()
                        val settings = json.decodeFromString<StartupSettings>(content)
                        applyLoadedIfUnchanged(settings, epochAtStart)
                        logger.debug(LogCategory.SYSTEM, "Loaded settings")
                    } else {
                        // Create default settings file
                        createDefaultFile(epochAtStart)
                        logger.debug(LogCategory.SYSTEM, "Created default settings file")
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    logger.warn(LogCategory.SYSTEM, "Error loading settings", error = e)
                    // Keep default settings on error
                }
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
        persistenceLock.withLock {
            val snapshot = synchronized(stateLock) { _currentSettings.value }
            saveSnapshot(snapshot)
        }

    private suspend fun saveSnapshot(settings: StartupSettings) =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(StartupSettings.serializer(), settings)
                // Temp sibling + atomic move, the same pattern as every other settings file
                // here: a crash mid-write leaves at most a stray temp, never a truncated
                // startup-settings.json that the next launch would parse as a fresh install.
                settingsFile.atomicWriteText(content)
                logger.debug(LogCategory.SYSTEM, "Settings saved")
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn(LogCategory.SYSTEM, "Error saving settings", error = e)
            }
        }

    /**
     * Update settings and persist.
     */
    actual suspend fun updateSettings(settings: StartupSettings) {
        persistenceLock.withLock {
            synchronized(stateLock) {
                // Bumped and published as one step under [stateLock]: a load fenced on an older
                // epoch cannot then publish over this value, however the threads interleave.
                mutationEpoch++
                _currentSettings.value = settings
            }
            // Keep the snapshot paired with the mutation while persistence is serialized, so
            // an older update cannot write after a newer one.
            saveSnapshot(settings)
        }
    }

    /**
     * The mutation epoch right now. [loadSettingsAsync] snapshots this before touching the
     * disk and hands it to [applyLoadedIfUnchanged]; internal so a test can reproduce a load
     * racing a mutation without having to win that race for real.
     */
    internal fun currentMutationEpoch(): Long = synchronized(stateLock) { mutationEpoch }

    /**
     * Publish the disk snapshot [settings] only while nothing has mutated the in-memory
     * state since the load that read it started ([epochAtStart]). A load that raced a
     * mutation is dropped as stale: the mutation is the later write, and it wins.
     *
     * Internal for the same reason as [currentMutationEpoch].
     */
    internal fun applyLoadedIfUnchanged(
        settings: StartupSettings,
        epochAtStart: Long,
    ) {
        synchronized(stateLock) {
            if (mutationEpoch == epochAtStart) {
                _currentSettings.value = settings
            } else {
                logger.debug(LogCategory.SYSTEM, "Discarded a settings load that raced a newer change")
            }
        }
    }

    /**
     * Write the initial default settings file when none exists yet. Skipped when a mutation
     * already ran: every [updateSettings] persists its own value, so the file exists and
     * holds something newer than any default this could write - overwriting it would be the
     * same clobber the load fence prevents, one level down, on disk.
     *
     * The write happens under [stateLock] so a concurrent [updateSettings] cannot slip
     * between the epoch check and the move and then have its own persist overwritten by the
     * defaults landing last.
     */
    private fun createDefaultFile(epochAtStart: Long) {
        synchronized(stateLock) {
            if (mutationEpoch != epochAtStart) {
                return
            }
            val content = json.encodeToString(StartupSettings.serializer(), _currentSettings.value)
            settingsFile.atomicWriteText(content)
        }
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
