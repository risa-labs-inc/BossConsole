package ai.rever.boss.updater

import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Central update manager that handles periodic update checks and state management
 */
class UpdateManager {
    private val logger = BossLogger.forComponent("UpdateManager")

    // Internal for access by VersionListManager
    internal val updateService = UpdateService()
    
    // Update state flows
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
    
    private val _lastCheckTime = MutableStateFlow<kotlin.time.Instant?>(null)
    val lastCheckTime: StateFlow<kotlin.time.Instant?> = _lastCheckTime.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)

    // Whether the "update available" dialog should be visible.
    // Set when a check (auto or forced) surfaces a non-dismissed update.
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    // Background job for periodic checks
    private var periodicCheckJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    companion object {
        val instance = UpdateManager()
    }
    
    /**
     * Start periodic update checks.
     *
     * Note: the primary trigger for update checks is now Supabase Realtime push
     * (see AppUpdateRealtimeService) — the app is notified the moment a new release
     * row is published and calls [checkForUpdates] directly. This periodic loop is
     * retained only as a long-interval safety net for when Realtime is unavailable
     * (offline, websocket down) and to perform the one initial check at startup.
     */
    fun startPeriodicChecks() {
        periodicCheckJob?.cancel()
        periodicCheckJob = scope.launch {
            while (isActive) {
                try {
                    checkForUpdatesInternal()
                    delay(UpdateSettings.checkIntervalHours * 60 * 60 * 1000) // Convert hours to milliseconds
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Error in periodic update check", error = e)
                    delay(60 * 60 * 1000) // Retry in 1 hour on error
                }
            }
        }
    }
    
    /**
     * Stop periodic update checks
     */
    fun stopPeriodicChecks() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
    }
    
    /**
     * Check for updates.
     *
     * @param force When true (manual "Check for Updates" actions), bypasses the
     * persisted per-version dismissal so the prompt is shown even for a version
     * the user previously dismissed. Automatic (startup/periodic) checks use false.
     */
    suspend fun checkForUpdates(force: Boolean = false): UpdateResult {
        return checkForUpdatesInternal(force)
    }

    // Coalesces concurrent checks so the startup stampede doesn't fire 2-3 network
    // checks at once (and possibly double-pop the dialog).
    private val checkMutex = Mutex()

    private suspend fun checkForUpdatesInternal(force: Boolean = false): UpdateResult {
        // Startup fires several checks near-simultaneously (BossApp startup, the periodic
        // loop's first tick, the Realtime on-connect catch-up), and each Realtime event
        // launches its own. A forced manual check waits its turn; an automatic check is
        // dropped if one is already running.
        if (force) {
            return checkMutex.withLock { runCheck(force) }
        }
        if (!checkMutex.tryLock()) {
            val info = _updateInfo.value
            return if (info != null && info.isNewerVersionAvailable) {
                UpdateResult.UpdateAvailable(info)
            } else {
                UpdateResult.NoUpdateAvailable
            }
        }
        return try {
            runCheck(force)
        } finally {
            checkMutex.unlock()
        }
    }

    private suspend fun runCheck(force: Boolean): UpdateResult {
        // An update flow is already in progress — don't clobber its state or
        // re-pop the dialog. Covers a periodic check firing mid-download, while
        // a downloaded update waits for install, during install, and after an
        // install that's pending a restart (where the version still reads as
        // "newer" than the running build).
        val current = _updateState.value
        if (current is UpdateState.Downloading || current is UpdateState.ReadyToInstall ||
            current is UpdateState.Installing || current is UpdateState.RestartRequired
        ) {
            val info = _updateInfo.value
            return if (info != null) UpdateResult.UpdateAvailable(info) else UpdateResult.NoUpdateAvailable
        }

        return try {
            _updateState.value = UpdateState.CheckingForUpdates
            _lastCheckTime.value = Clock.System.now()

            val updateInfo = updateService.checkForUpdates()
            _updateInfo.value = updateInfo

            when {
                updateInfo.isNewerVersionAvailable -> {
                    if (!force && isVersionDismissed(updateInfo.latestVersion)) {
                        // User dismissed this exact version: stay quiet (no banner, no dialog)
                        _updateState.value = UpdateState.Idle
                        UpdateResult.NoUpdateAvailable
                    } else {
                        _updateState.value = UpdateState.UpdateAvailable(updateInfo)
                        _showUpdateDialog.value = true
                        UpdateResult.UpdateAvailable(updateInfo)
                    }
                }
                else -> {
                    _updateState.value = UpdateState.UpToDate
                    UpdateResult.NoUpdateAvailable
                }
            }
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            UpdateResult.Error("Failed to check for updates", e)
        }
    }

    private fun isVersionDismissed(latest: Version): Boolean {
        val dismissed = UpdateSettings.lastDismissedVersion ?: return false
        val parsed = Version.parse(dismissed)
        return if (parsed != null) parsed == latest else dismissed == latest.toString()
    }

    /**
     * Hide both the dialog and the banner, then persist the dismissal for
     * [version]. Used by the dialog's "Later" action and the banner's
     * "Dismiss" button. Automatic checks won't re-surface this exact version;
     * any different available version (normally a newer release) will prompt
     * again.
     */
    suspend fun dismissVersion(version: Version) {
        // Hide the UI first so dismissal is instant (and can't be re-tapped);
        // the disk write follows. saveSettings() swallows IO failures — worst
        // case the dismissal doesn't survive a restart and re-prompts.
        _showUpdateDialog.value = false
        _updateState.value = UpdateState.Idle
        UpdateSettings.lastDismissedVersion = version.toString()
        UpdateSettingsManager.saveSettings()
    }

    /**
     * Close only the dialog (e.g. after "Update Now") — the banner keeps
     * showing download/install progress.
     */
    fun dismissDialogOnly() {
        _showUpdateDialog.value = false
    }
    
    /**
     * Launch [downloadUpdate] on the manager's own long-lived scope so the
     * download survives the window that started it — the update dialog lives
     * in one specific window, and closing that window must not cancel an
     * in-flight download.
     */
    fun downloadUpdateInBackground(updateInfo: UpdateInfo) {
        scope.launch { downloadUpdate(updateInfo) }
    }

    /**
     * Download the available update
     */
    suspend fun downloadUpdate(updateInfo: UpdateInfo): UpdateResult {
        return try {
            _updateState.value = UpdateState.Downloading(0f)

            val downloadPath = updateService.downloadUpdate(updateInfo) { progress ->
                _updateState.value = UpdateState.Downloading(progress)
            }

            if (downloadPath != null) {
                _updateState.value = UpdateState.ReadyToInstall(downloadPath)
                UpdateResult.UpdateAvailable(updateInfo.copy())
            } else {
                val errorMsg = "Failed to download update"
                _updateState.value = UpdateState.Error(errorMsg)
                UpdateResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "Download failed: ${e.message}"
            _updateState.value = UpdateState.Error(errorMsg)
            UpdateResult.Error(errorMsg, e)
        }
    }

    /**
     * Download a specific version (for upgrades or downgrades)
     */
    suspend fun downloadSpecificVersion(versionInfo: VersionInfo): UpdateResult {
        return try {
            _updateState.value = UpdateState.Downloading(0f)

            // Convert VersionInfo to UpdateInfo
            val updateInfo = UpdateInfo(
                available = true,
                currentVersion = AppVersion.CURRENT,
                latestVersion = versionInfo.version,
                releaseNotes = versionInfo.releaseNotes,
                downloadUrl = versionInfo.downloadUrl,
                assetSize = versionInfo.downloadSize,
                assetName = updateService.getExpectedAssetName(versionInfo.version),
                sha256 = versionInfo.sha256
            )

            val downloadPath = updateService.downloadUpdate(updateInfo) { progress ->
                _updateState.value = UpdateState.Downloading(progress)
            }

            if (downloadPath != null) {
                _updateState.value = UpdateState.ReadyToInstall(downloadPath)
                UpdateResult.UpdateAvailable(updateInfo)
            } else {
                val errorMsg = "Failed to download version ${versionInfo.version}"
                _updateState.value = UpdateState.Error(errorMsg)
                UpdateResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "Download failed: ${e.message}"
            _updateState.value = UpdateState.Error(errorMsg)
            UpdateResult.Error(errorMsg, e)
        }
    }
    
    /**
     * Install the downloaded update
     */
    suspend fun installUpdate(downloadPath: String): Boolean {
        return try {
            _updateState.value = UpdateState.Installing
            
            val success = updateService.installUpdate(downloadPath)
            if (success) {
                _updateState.value = UpdateState.RestartRequired
            } else {
                _updateState.value = UpdateState.Error("Installation failed")
            }
            success
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("Installation failed: ${e.message}")
            false
        }
    }
    
    /**
     * Get current application version
     */
    fun getCurrentVersion(): Version = AppVersion.CURRENT
    
    /**
     * Check if enough time has passed since last check for automatic checking
     */
    fun shouldCheckForUpdates(): Boolean {
        val lastCheck = _lastCheckTime.value
        if (lastCheck == null) return true

        val now = Clock.System.now()
        val timeSinceLastCheck = now - lastCheck
        return timeSinceLastCheck.inWholeHours >= UpdateSettings.checkIntervalHours
    }
    
    /**
     * Reset update state to idle. Does NOT persist dismissal (see [dismissVersion]).
     */
    fun resetState() {
        _updateState.value = UpdateState.Idle
        _showUpdateDialog.value = false
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopPeriodicChecks()
        scope.cancel()
    }
}

/**
 * Update state sealed class
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object CheckingForUpdates : UpdateState()
    object UpToDate : UpdateState()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateState()
    data class Downloading(val progress: Float) : UpdateState() // 0.0 to 1.0
    data class ReadyToInstall(val downloadPath: String) : UpdateState()
    object Installing : UpdateState()
    object RestartRequired : UpdateState()
    data class Error(val message: String) : UpdateState()
}
