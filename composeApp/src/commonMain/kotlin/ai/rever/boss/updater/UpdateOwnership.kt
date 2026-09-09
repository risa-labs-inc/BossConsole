package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A window's view of the updater.
 *
 * Everything a window legitimately needs — observe state, ask for a check, start
 * a download, install, dismiss — and **nothing that can tear the updater down**.
 * The absence of a `shutdown`/`cleanup` member is the point: the updater is
 * process-wide, so a window closing must not stop periodic checks or cancel an
 * in-flight download for the windows that are still open (Issues #19, #37).
 *
 * A window [release]s its handle when it closes. Releasing is bookkeeping only:
 * it never stops any update work, not even when it is the last handle — the app
 * outlives its windows on macOS, and a download queued before the last window
 * closed still needs to finish.
 */
interface UpdateHandle {
    /** The window this handle belongs to. */
    val windowId: String

    /** True once [release] has been called; a released handle stops mutating shared state. */
    val isReleased: Boolean

    val updateState: StateFlow<UpdateState>

    val showUpdateDialog: StateFlow<Boolean>

    /**
     * Awaitable check, for a caller that needs the answer. Everything else is
     * fire-and-forget below: a window must not await update work on a scope that
     * dies with its composition.
     */
    suspend fun checkForUpdates(force: Boolean = false): UpdateResult

    fun downloadUpdateInBackground(updateInfo: UpdateInfo)

    /**
     * Fire-and-forget variants for UI callbacks.
     *
     * Window UI must not run update work on `rememberCoroutineScope()`: that scope
     * dies with the composition, so closing the window mid-install cancelled the
     * install and left [UpdateState] stuck on `Installing`, and a cancelled
     * [dismissVersion] silently lost a persisted dismissal. These run on the
     * manager's own scope instead, which only app-level shutdown cancels.
     */
    fun checkForUpdatesInBackground(force: Boolean = false)

    fun installUpdateInBackground(downloadPath: String)

    /**
     * Abandon a download in progress.
     *
     * Reaches [UpdateManager.cancelDownload] directly rather than through
     * `DownloadCenter.cancel(APP_UPDATE_ID)`, even though the row carries the same
     * action: the row exists only while [UpdateDownloadCenterMirror] is running, and
     * a banner button whose behaviour depends on a collector being live is one that
     * silently does nothing when it is not. Both surfaces end at the same manager
     * method, which is idempotent, so pressing Cancel in the banner and in the
     * dialog is one cancel however they interleave.
     */
    fun cancelDownload()

    /**
     * Throw away an update that downloaded but was never installed.
     *
     * Separate from [cancelDownload] because the two abandon different things and only one of
     * them is free: cancelling stops bytes arriving, while this deletes an artifact that is
     * already on disk. [UpdateManager.discardDownload] suspends for that delete, which is why
     * this is one of the fire-and-forget variants and [cancelDownload] is not.
     */
    fun discardDownloadInBackground()

    fun dismissVersionInBackground(version: Version)

    fun dismissDialogOnly()

    fun resetState()

    /** Give up this window's interest in the updater. Idempotent. */
    fun release()
}

/**
 * The single owner of the process-wide [UpdateManager].
 *
 * Ownership is explicit rather than ambient: the coordinator starts the update
 * machinery, hands out per-window [UpdateHandle]s, and is the only thing that can
 * [shutdown]. That shutdown belongs to the app-level exit path (the JVM shutdown
 * hook in `main.kt`), which is the only place where "no window will ever need
 * updates again" is actually true.
 *
 * This mirrors the shape the Rust port settled on (`UpdateCoordinator` owns
 * `shutdown`, windows hold an `UpdateHandle` that does not).
 */
// App-wide actions and window capabilities deliberately share this single lifecycle owner.
@Suppress("TooManyFunctions")
class UpdateCoordinator internal constructor(
    internal val manager: UpdateManager,
) {
    private val logger = BossLogger.forComponent("UpdateCoordinator")

    // Shared process-wide release cache used by Settings and the home screen.
    internal val versionListManager: VersionListManager by lazy {
        VersionListManager(manager.updateService)
    }
    private val _lastSeenReleaseVersion =
        MutableStateFlow(UpdateSettings.lastSeenReleaseVersion)

    internal val lastSeenReleaseVersion: StateFlow<String?> =
        _lastSeenReleaseVersion

    private val handles = ConcurrentHashMap<String, WindowUpdateHandle>()
    private val startMutex = Mutex()
    private val seenReleaseMutex = Mutex()
    private val shutDown = AtomicBoolean(false)

    /** Number of windows currently holding a live handle. */
    val activeWindowCount: Int
        get() = handles.size

    /** True once [shutdown] has run. Terminal state — the updater is not restartable. */
    val isShutDown: Boolean
        get() = shutDown.get()

    /**
     * Handle for [windowId], created on first use. Re-acquiring for the same
     * window returns a fresh handle (the previous one is released first) so a
     * window that re-composes its lifecycle effect can't leak a stale handle.
     */
    fun handleFor(windowId: String): UpdateHandle {
        val handle = WindowUpdateHandle(windowId)
        // The process is going away; hand back an inert handle rather than
        // registering interest in an updater that can never run again.
        if (isShutDown) handle.markReleased() else publish(windowId, handle)
        return handle
    }

    private fun publish(
        windowId: String,
        handle: WindowUpdateHandle,
    ) {
        beforePublishHook?.invoke()
        handles.put(windowId, handle)?.markReleased()

        // The isShutDown test in handleFor and this put are not one atomic step, so
        // a concurrent shutdown() can clear the map in between and leave the handle
        // registered and un-released - live against a cancelled manager, with
        // activeWindowCount stuck forever. Re-check after publishing.
        if (isShutDown) {
            handles.remove(windowId, handle)
            handle.markReleased()
            logger.debug(
                LogCategory.SYSTEM,
                "Discarded update handle acquired during shutdown",
                mapOf("windowId" to windowId),
            )
        } else {
            logger.debug(
                LogCategory.SYSTEM,
                "Window acquired update handle",
                mapOf("windowId" to windowId, "activeWindows" to handles.size.toString()),
            )
        }
    }

    /**
     * Test-only seam: invoked after [handleFor] has tested [isShutDown] but before
     * the handle is published, so a test can interleave [shutdown] into exactly the
     * window where a handle would otherwise be left live against a dead manager.
     */
    internal var beforePublishHook: (() -> Unit)? = null

    /**
     * Start periodic checks (plus the one startup check) if auto-check is enabled
     * and they aren't running yet. Safe to call from every window: only the first
     * call actually starts anything, so N windows no longer restart the loop and
     * re-fire N startup checks.
     */
    suspend fun ensureStarted() {
        if (isShutDown) {
            logger.warn(LogCategory.SYSTEM, "Ignoring update start request after shutdown")
            return
        }

        // Ahead of the auto-check gate on purpose: turning automatic checks off
        // does not stop a MANUAL check from starting a download, and that download
        // still belongs in the bottom bar. Idempotent, so every window may ask.
        UpdateDownloadCenterMirror.start(this)

        if (!UpdateSettings.autoCheckEnabled) return

        val startedNow =
            startMutex.withLock {
                if (manager.isPeriodicCheckActive) {
                    false
                } else {
                    manager.startPeriodicChecks()
                    true
                }
            }

        if (startedNow) {
            // No explicit startup check here: startPeriodicChecks' first loop
            // iteration already runs one, and checkMutex would coalesce a second
            // one away anyway.
            logger.debug(LogCategory.SYSTEM, "Started periodic update checks")
        }
    }

    /**
     * App-level "Check for Updates" (window menu, settings): runs on the manager's
     * scope so it isn't tied to whichever UI triggered it.
     */
    fun checkForUpdatesInBackground(force: Boolean = false) {
        if (isShutDown) return
        manager.launchInBackground { manager.checkForUpdates(force) }
    }

    /** Enable or disable the periodic check loop (settings toggle). */
    suspend fun setPeriodicChecksEnabled(enabled: Boolean) {
        if (isShutDown) return
        if (enabled) ensureStarted() else manager.stopPeriodicChecks()
    }

    /**
     * App-level read-only state and actions, for UI that belongs to the app rather
     * than to one window (the Settings window, the window menu). Routing these
     * through the owner keeps `UpdateManager.instance` out of UI code, and every
     * action runs on the manager's scope so closing the window that started it
     * can't cancel it.
     */
    val updateState: StateFlow<UpdateState>
        get() = manager.updateState

    val lastCheckTime: StateFlow<kotlin.time.Instant?>
        get() = manager.lastCheckTime

    /** The running app version. */
    fun currentVersion(): Version = manager.getCurrentVersion()

    /** Service handle for the version-list UI. */
    internal val updateService: UpdateService
        get() = manager.updateService

    fun downloadUpdateInBackground(updateInfo: UpdateInfo) {
        if (isShutDown) return
        manager.downloadUpdateInBackground(updateInfo)
    }

    fun downloadSpecificVersionInBackground(versionInfo: VersionInfo) {
        if (isShutDown) return
        // Through the manager's own launcher, which records the job: a download
        // started from the version list is as cancellable as one started from the
        // banner, and launching it here directly would leave nothing to cancel.
        manager.downloadSpecificVersionInBackground(versionInfo)
    }

    fun installUpdateInBackground(downloadPath: String) {
        if (isShutDown) return
        manager.launchInBackground { manager.installUpdate(downloadPath) }
    }

    // Advisory badge state: process shutdown may cancel a pending disk write, so opening notes
    // immediately before quitting can show NEW again. It never changes install/dismiss state.
    internal fun markReleaseSeenInBackground(version: Version) {
        if (isShutDown) return

        manager.launchInBackground {
            seenReleaseMutex.withLock {
                val nextVersion =
                    advanceLastSeenReleaseVersion(
                        currentVersion = _lastSeenReleaseVersion.value,
                        viewedVersion = version,
                    )

                if (nextVersion == _lastSeenReleaseVersion.value) {
                    return@withLock
                }

                UpdateSettings.lastSeenReleaseVersion = nextVersion
                _lastSeenReleaseVersion.value = nextVersion
                UpdateSettingsManager.saveSettings()
            }
        }
    }

    /**
     * App-level teardown: stop periodic checks and cancel in-flight update work.
     * Idempotent. Call this exactly once, from the process exit path.
     */
    fun shutdown() {
        if (!shutDown.compareAndSet(false, true)) return
        logger.info(
            LogCategory.SYSTEM,
            "Shutting down updater",
            mapOf("activeWindows" to handles.size.toString()),
        )
        handles.values.forEach { it.markReleased() }
        handles.clear()
        manager.shutdown()
    }

    // One delegate per handle capability is the shape of this class, not debt: every method is a
    // two-line `guard(...)` forward to the shared manager, and UpdateHandleGuardTest requires it
    // stay that way. Suppressed here rather than baselined so the reason sits with the code - a
    // baseline row reads as legacy for a class that is deliberately built like this.
    @Suppress("TooManyFunctions")
    private inner class WindowUpdateHandle(
        override val windowId: String,
    ) : UpdateHandle {
        private val released = AtomicBoolean(false)

        override val isReleased: Boolean
            get() = released.get()

        override val updateState: StateFlow<UpdateState>
            get() = manager.updateState

        override val showUpdateDialog: StateFlow<Boolean>
            get() = manager.showUpdateDialog

        override suspend fun checkForUpdates(force: Boolean): UpdateResult =
            if (guard("checkForUpdates")) manager.checkForUpdates(force) else UpdateResult.HandleReleased

        override fun checkForUpdatesInBackground(force: Boolean) {
            if (guard("checkForUpdatesInBackground")) manager.launchInBackground { manager.checkForUpdates(force) }
        }

        override fun installUpdateInBackground(downloadPath: String) {
            if (guard("installUpdateInBackground")) manager.launchInBackground { manager.installUpdate(downloadPath) }
        }

        override fun dismissVersionInBackground(version: Version) {
            if (guard("dismissVersionInBackground")) manager.launchInBackground { manager.dismissVersion(version) }
        }

        override fun downloadUpdateInBackground(updateInfo: UpdateInfo) {
            if (guard("downloadUpdateInBackground")) manager.downloadUpdateInBackground(updateInfo)
        }

        override fun discardDownloadInBackground() {
            if (guard("discardDownloadInBackground")) manager.launchInBackground { manager.discardDownload() }
        }

        override fun cancelDownload() {
            // Not launchInBackground: cancelDownload only cancels the download job and
            // is not itself suspending, so wrapping it would put the cancel behind a
            // dispatch for no reason - and the point of this button is that the bar
            // stops immediately.
            if (guard("cancelDownload")) manager.cancelDownload()
        }

        override fun dismissDialogOnly() {
            if (guard("dismissDialogOnly")) manager.dismissDialogOnly()
        }

        override fun resetState() {
            if (guard("resetState")) manager.resetState()
        }

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            handles.remove(windowId, this)
            // Deliberately NOT stopping periodic checks or cancelling downloads:
            // other windows may still be open, and even if none are, the app keeps
            // running and an in-flight download must survive.
            logger.debug(
                LogCategory.SYSTEM,
                "Window released update handle",
                mapOf("windowId" to windowId, "activeWindows" to handles.size.toString()),
            )
        }

        fun markReleased() {
            released.set(true)
        }

        /** Released handles go inert instead of mutating shared update state. */
        private fun guard(operation: String): Boolean {
            if (released.get()) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Ignoring updater call from released handle",
                    mapOf("windowId" to windowId, "operation" to operation),
                )
                return false
            }
            return true
        }
    }

    companion object {
        /** The app's owner of [UpdateManager.instance]. */
        val instance = createUpdateCoordinator()
    }
}

private fun createUpdateCoordinator(): UpdateCoordinator {
    UpdateSettingsManager.ensureLoaded()
    return UpdateCoordinator(UpdateManager.instance)
}
