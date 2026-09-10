package ai.rever.boss.updater

import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Central update manager that handles periodic update checks and state management.
 *
 * This is **process-wide** state, not window state. Windows must never tear it
 * down: see [UpdateCoordinator] / [UpdateHandle] for the ownership split — the
 * coordinator is the single owner that can [shutdown], windows hold handles that
 * cannot.
 */
class UpdateManager private constructor(
    private val installOperation: (suspend (String) -> InstallOutcome)?,
    private val checkOperation: (suspend () -> UpdateInfo)?,
    private val downloadOperation: (suspend (UpdateInfo, (Float) -> Unit) -> String?)? = null,
) {
    constructor() : this(null, null)

    internal constructor(installOperation: UpdateInstallOperation) : this(installOperation::install, null)

    internal constructor(
        installOperation: UpdateInstallOperation,
        checkOperation: suspend () -> UpdateInfo,
        downloadOperation: (suspend (UpdateInfo, (Float) -> Unit) -> String?)? = null,
    ) : this(installOperation::install, checkOperation, downloadOperation)

    private val logger = BossLogger.forComponent("UpdateManager")

    // File ownership spans suspension: a second download must not overwrite the
    // artifact while an installer is using it or removing a permanent refusal.
    private val artifactMutex = Mutex()

    // Internal for access by VersionListManager
    internal val updateService = UpdateService()

    // Update state flows
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _lastCheckTime = MutableStateFlow<kotlin.time.Instant?>(null)
    val lastCheckTime: StateFlow<kotlin.time.Instant?> = _lastCheckTime.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)

    /**
     * The update the current state is about, or null before any check found one.
     * Read-only: [UpdateState.Downloading] carries a fraction and nothing else, so
     * anything naming the download (the download center's row) needs this.
     */
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    // Whether the "update available" dialog should be visible.
    // Set when a check (auto or forced) surfaces a non-dismissed update.
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    // Background job for periodic checks.
    // @Volatile: written by the settings UI (start/stopPeriodicChecks) and by
    // UpdateCoordinator.ensureStarted on other threads, and read by
    // isPeriodicCheckActive - which ensureStarted's idempotency check relies on.
    @Volatile
    private var periodicCheckJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Whether the manager's own scope is still alive, i.e. whether periodic
     * checks and background downloads can still run. Only [shutdown] flips this
     * to false, and only the app-level owner may call that.
     */
    val isActive: Boolean
        get() = scope.isActive

    /** Whether the periodic check loop is currently running. */
    val isPeriodicCheckActive: Boolean
        get() = periodicCheckJob?.isActive == true

    /**
     * The periodic loop's current job, for the idempotency test: [startPeriodicChecks]
     * cancels and replaces it, so "the same job is still there" is how a caller
     * proves the loop was not restarted.
     */
    internal val periodicCheckJobOrNull: Job?
        get() = periodicCheckJob

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
        periodicCheckJob =
            scope.launch {
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
    suspend fun checkForUpdates(force: Boolean = false): UpdateResult = checkForUpdatesInternal(force)

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
        // Error is deliberately rechecked: suppression clears the refusal banner
        // on the next automatic check rather than preserving it indefinitely.
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

            val updateInfo = checkOperation?.invoke() ?: updateService.checkForUpdates()
            _updateInfo.value = updateInfo

            when {
                updateInfo.isNewerVersionAvailable -> {
                    if (!force && isVersionDismissed(updateInfo.latestVersion)) {
                        // This exact version was dismissed or refused for this OS: stay quiet.
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
        persistDismissedVersion(version)
    }

    private suspend fun persistDismissedVersion(version: Version) {
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
     * The coroutine holding download ownership, not a queued request. Updated
     * only inside [artifactMutex], so Cancel always reaches the active transfer.
     *
     * @Volatile: written on the manager's scope and read from whichever thread
     * the download center's Cancel arrives on.
     */
    @Volatile
    private var downloadJob: Job? = null

    /**
     * Launch [downloadUpdate] on the manager's own long-lived scope so the
     * download survives the window that started it — the update dialog lives
     * in one specific window, and closing that window must not cancel an
     * in-flight download.
     */
    fun downloadUpdateInBackground(updateInfo: UpdateInfo) {
        launchInBackground { downloadUpdate(updateInfo) }
    }

    /** As [downloadUpdateInBackground], for a specific version (upgrade or downgrade). */
    fun downloadSpecificVersionInBackground(versionInfo: VersionInfo) {
        launchInBackground { downloadSpecificVersion(versionInfo) }
    }

    /**
     * Abandon the download in flight, if there is one.
     *
     * Only the download: an install is a sequence of file moves and an elevated
     * helper, and stopping it half way is worse than finishing it. The download
     * center enforces the same rule by only offering Cancel while downloading;
     * this is the second half of it, so a caller that asks anyway is refused.
     *
     * The partial file is deleted by the service, which is the only layer that
     * knows where it was staged.
     */
    fun cancelDownload() {
        if (_updateState.value !is UpdateState.Downloading) return
        downloadJob?.cancel()
    }

    /**
     * Throw away an update that finished downloading but was not installed.
     *
     * Deletes the staged artifact rather than leaving it: it is the whole app,
     * it sits in a restricted staging directory, and an update the user declined
     * should not quietly occupy that much disk until the next one replaces it.
     * The version stays on offer, so the banner can download it again.
     */
    suspend fun discardDownload() {
        val expected = _updateState.value as? UpdateState.ReadyToInstall ?: return
        artifactMutex.withLock {
            if (_updateState.value === expected) discardStagedDownload()
        }
    }

    private suspend fun discardStagedDownload() {
        // CLAIM BEFORE DELETING, and give up if the claim is lost. The state move used to
        // happen AFTER the delete, which is exactly what let an install and a discard both
        // proceed - see [claimStagedUpdate]. Whoever moves the state out of ReadyToInstall owns
        // the staged file; the loser touches nothing.
        //
        // Idle unless there is genuinely a newer version to offer: after discarding a
        // DOWNGRADE, `_updateInfo` holds the older version, and UpdateAvailable would
        // put "Update v9.4.20 available" in the banner.
        val claimed =
            _updateState.claimStagedUpdate {
                _updateInfo.value
                    ?.takeIf { info -> info.isNewerVersionAvailable }
                    ?.let { info -> UpdateState.UpdateAvailable(info) }
                    ?: UpdateState.Idle
            } ?: return
        updateService.discardDownload(claimed.downloadPath)
    }

    /**
     * Run [block] on the manager's own long-lived scope, so the work outlives any
     * single window. The scope is only cancelled by [shutdown] (app exit).
     */
    internal fun launchInBackground(block: suspend CoroutineScope.() -> Unit): Job = scope.launch { block() }

    /**
     * Download the available update
     */
    suspend fun downloadUpdate(updateInfo: UpdateInfo): UpdateResult =
        withDownloadOwnership {
            downloadAvailableUpdate(updateInfo)
        }

    private suspend fun withDownloadOwnership(operation: suspend () -> UpdateResult): UpdateResult =
        artifactMutex.withLock {
            if (_updateState.value == UpdateState.RestartRequired) {
                return@withLock UpdateResult.Error("Restart BOSS before downloading another update")
            }
            downloadJob = currentCoroutineContext()[Job]
            try {
                operation()
            } finally {
                downloadJob = null
            }
        }

    private suspend fun downloadAvailableUpdate(updateInfo: UpdateInfo): UpdateResult =
        try {
            _updateState.value = UpdateState.Downloading(0f)

            val onProgress: (Float) -> Unit = { progress -> _updateState.value = UpdateState.Downloading(progress) }
            val downloadPath =
                if (downloadOperation != null) {
                    downloadOperation.invoke(updateInfo, onProgress)
                } else {
                    updateService.downloadUpdate(updateInfo, onProgress)
                }

            if (downloadPath != null) {
                stageDownloadedUpdate(updateInfo, downloadPath)
                UpdateResult.UpdateAvailable(updateInfo.copy())
            } else {
                val errorMsg = "Failed to download update"
                _updateState.value = UpdateState.Error(errorMsg)
                UpdateResult.Error(errorMsg)
            }
        } catch (e: CancellationException) {
            // A cancellation is an answer, not a fault. Caught before the general
            // clause below, which would otherwise turn the user's own Cancel into
            // "Download failed: StandaloneCoroutine was cancelled" in the banner,
            // and leave that error where the offer to update used to be.
            _updateState.value = UpdateState.UpdateAvailable(updateInfo)
            throw e
        } catch (e: Exception) {
            val errorMsg = "Download failed: ${e.message}"
            _updateState.value = UpdateState.Error(errorMsg)
            UpdateResult.Error(errorMsg, e)
        }

    /**
     * Download a specific version (for upgrades or downgrades)
     */
    suspend fun downloadSpecificVersion(versionInfo: VersionInfo): UpdateResult =
        withDownloadOwnership { downloadSelectedVersion(versionInfo) }

    private suspend fun downloadSelectedVersion(versionInfo: VersionInfo): UpdateResult =
        try {
            _updateState.value = UpdateState.Downloading(0f)

            // Convert VersionInfo to UpdateInfo
            val updateInfo =
                UpdateInfo(
                    available = true,
                    currentVersion = AppVersion.CURRENT,
                    latestVersion = versionInfo.version,
                    releaseNotes = versionInfo.releaseNotes,
                    downloadUrl = versionInfo.downloadUrl,
                    assetSize = versionInfo.downloadSize,
                    assetName = updateService.getExpectedAssetName(versionInfo.version),
                    sha256 = versionInfo.sha256,
                )

            // Published before the download starts, so anything naming it - the
            // download center's row, and the state discardDownload restores - names the
            // version actually on the wire. Only the check path used to write this, so
            // downgrading to 9.4.20 showed a row reading "BOSS v9.4.34".
            _updateInfo.value = updateInfo

            val onProgress: (Float) -> Unit = { progress -> _updateState.value = UpdateState.Downloading(progress) }
            val downloadPath =
                if (downloadOperation != null) {
                    downloadOperation.invoke(updateInfo, onProgress)
                } else {
                    updateService.downloadUpdate(updateInfo, onProgress)
                }

            if (downloadPath != null) {
                stageDownloadedUpdate(updateInfo, downloadPath)
                UpdateResult.UpdateAvailable(updateInfo)
            } else {
                val errorMsg = "Failed to download version ${versionInfo.version}"
                _updateState.value = UpdateState.Error(errorMsg)
                UpdateResult.Error(errorMsg)
            }
        } catch (e: CancellationException) {
            // See downloadUpdate: back to whatever was on offer before, not an error -
            // and Idle rather than an "available" banner for a version that is older.
            _updateState.value =
                _updateInfo.value
                    ?.takeIf { it.isNewerVersionAvailable }
                    ?.let { UpdateState.UpdateAvailable(it) }
                    ?: UpdateState.Idle
            throw e
        } catch (e: Exception) {
            val errorMsg = "Download failed: ${e.message}"
            _updateState.value = UpdateState.Error(errorMsg)
            UpdateResult.Error(errorMsg, e)
        }

    /**
     * Install the downloaded update
     */
    suspend fun installUpdate(downloadPath: String): Boolean {
        val expected = _updateState.value as? UpdateState.ReadyToInstall ?: return false
        return artifactMutex.withLock {
            if (_updateState.value === expected) installStagedUpdate(downloadPath) else false
        }
    }

    private suspend fun installStagedUpdate(downloadPath: String): Boolean {
        // Claim the staged artifact, or do nothing at all.
        //
        // Both halves of this matter, and the bug was that neither existed. The state moved to
        // Installing only once this coroutine had been DISPATCHED, while every caller reaches it
        // through `launchInBackground` - so between pressing Install and this line running, the
        // state still read ReadyToInstall and a discard sailed past its own guard, deleted the
        // artifact, and left this installing a file that was no longer there. The two buttons sit
        // 8dp apart on the update banner, so "quickly" is one ordinary mis-click.
        //
        // A compare-and-set from the exact ReadyToInstall value makes the two mutually exclusive:
        // whichever lands first wins, and the loser returns without touching the file. It also
        // makes a second press of Install a no-op rather than a second elevated installer, which
        // the download center's dialog already got for free by clearing its action on use.
        val claimed = _updateState.claimStagedUpdate(downloadPath) { UpdateState.Installing }
        if (claimed == null) {
            logger.info(
                LogCategory.SYSTEM,
                "Ignoring install request - nothing staged, or the staged update was claimed first",
                mapOf("state" to _updateState.value::class.simpleName.orEmpty()),
            )
            return false
        }
        return try {
            // Use the path won by the claim. A stale UI action must not install an
            // artifact staged later by another download.
            val outcome =
                if (installOperation != null) {
                    installOperation.invoke(claimed.downloadPath)
                } else {
                    updateService.installUpdate(claimed.downloadPath)
                }
            if (outcome.succeeded) {
                if (!_updateState.compareAndSet(UpdateState.Installing, UpdateState.RestartRequired)) {
                    logger.warn(LogCategory.SYSTEM, "Preserving a newer update state after an installation succeeded")
                }
            } else {
                applyInstallFailure(outcome, claimed)
            }
            outcome.succeeded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _updateState.compareAndSet(UpdateState.Installing, UpdateState.Error("Installation failed: ${e.message}"))
            false
        }
    }

    /**
     * Applies the installer message directly: this is the only layer that knows
     * why an install was refused, while a generic failure is indistinguishable
     * from a crash to the person looking at the update UI.
     */
    private suspend fun applyInstallFailure(
        outcome: InstallOutcome,
        staged: UpdateState.ReadyToInstall,
    ) {
        val publishedFailure =
            _updateState.compareAndSet(
                UpdateState.Installing,
                UpdateState.Error(outcome.errorMessage ?: "Installation failed"),
            )
        if (!publishedFailure) {
            logger.warn(
                LogCategory.SYSTEM,
                "Preserving a newer update state after an earlier installation failed",
                mapOf("error" to (outcome.errorMessage ?: "Installation failed")),
            )
        }
        if (outcome.failureReason != InstallFailureReason.UnsupportedOs) return
        try {
            val info = staged.updateInfo
            val dismissed = UpdateSettings.lastDismissedVersion?.let { Version.parse(it) }
            // An intermediate release (newer than this app, older than the latest
            // refusal) must not reopen automatic offers of that latest release.
            if (info?.isNewerVersionAvailable == true && dismissed?.isNewerThan(info.latestVersion) != true) {
                logger.info(
                    LogCategory.SYSTEM,
                    "Suppressing update refused by this operating system",
                    mapOf("version" to info.latestVersion.toString()),
                )
                persistDismissedVersion(info.latestVersion)
            }
        } finally {
            // artifactMutex also excludes downloads during persistence/cleanup.
            // If a state was replaced outside that protocol, keep its files intact.
            // Cancellation can prevent durability; a later launch may offer again.
            if (publishedFailure) updateService.discardDownload(staged.downloadPath)
        }
    }

    /** Record the exact update whose downloaded artifact is now ready to install. */
    internal fun stageDownloadedUpdate(
        updateInfo: UpdateInfo,
        downloadPath: String,
    ) {
        _updateInfo.value = updateInfo
        _updateState.value = UpdateState.ReadyToInstall(downloadPath, updateInfo)
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
     * Tear down all update machinery: stop periodic checks and cancel the
     * manager's scope (killing any in-flight download).
     *
     * **Owner-only, and irreversible.** Nothing re-creates the scope, so calling
     * this while the app is still running silently disables updates for the rest
     * of the session. Reach it through [UpdateCoordinator.shutdown] from the
     * app-level exit path only — never from window or composable teardown, which
     * is why [UpdateHandle] deliberately has no equivalent (Issues #19, #37).
     */
    internal fun shutdown() {
        stopPeriodicChecks()
        scope.cancel()
    }
}

internal class UpdateInstallOperation(
    private val operation: suspend (String) -> InstallOutcome,
) {
    suspend fun install(downloadPath: String): InstallOutcome = operation(downloadPath)
}

/**
 * Update state sealed class
 */
sealed class UpdateState {
    object Idle : UpdateState()

    object CheckingForUpdates : UpdateState()

    object UpToDate : UpdateState()

    data class UpdateAvailable(
        val updateInfo: UpdateInfo,
    ) : UpdateState()

    data class Downloading(
        val progress: Float,
    ) : UpdateState() // 0.0 to 1.0

    data class ReadyToInstall(
        val downloadPath: String,
        /** Immutable metadata for the artifact at [downloadPath]. */
        val updateInfo: UpdateInfo? = null,
    ) : UpdateState()

    object Installing : UpdateState()

    object RestartRequired : UpdateState()

    data class Error(
        val message: String,
    ) : UpdateState()
}

/**
 * Take exclusive ownership of a staged update, moving it to the state [to] describes.
 *
 * Returns the [UpdateState.ReadyToInstall] that was claimed, or null when there was nothing
 * staged or another caller got there first. A null answer means **do not touch the file**.
 *
 * This exists because installing and discarding are the same artifact seen two ways, and both
 * used to check the state and then act, with a gap in between. Every caller reaches them through
 * `launchInBackground`, so the gap is a real dispatch, not an instruction or two: pressing Install
 * and then Cancel - two buttons 8dp apart on the update banner - let both pass their checks, and
 * the discard deleted the artifact while the install was opening it. The user lost the download
 * AND got an installation failure, which is neither of the two things they asked for.
 *
 * A compare-and-set from the exact `ReadyToInstall` value makes them mutually exclusive without a
 * lock, and the same call makes a second Install press a no-op rather than a second elevated
 * installer - the download center's dialog already had that, because it clears an action when it
 * fires; the banner's buttons do not.
 *
 * Deliberately takes the destination as a lambda rather than a value: the discard side computes
 * its next state from `_updateInfo`, and evaluating that eagerly for a claim that then loses would
 * read state the loser has no business acting on.
 */
internal fun MutableStateFlow<UpdateState>.claimStagedUpdate(
    expectedDownloadPath: String? = null,
    to: (UpdateState.ReadyToInstall) -> UpdateState,
): UpdateState.ReadyToInstall? {
    val ready = value as? UpdateState.ReadyToInstall ?: return null
    val matchesPath = expectedDownloadPath == null || ready.downloadPath == expectedDownloadPath
    return if (matchesPath && compareAndSet(ready, to(ready))) ready else null
}
