package ai.rever.boss.plugin.browser

import ai.rever.boss.components.plugin.tab_types.fluck.DownloadItem
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadManager
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadSettings
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadStatus
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.config.ChromiumFlagKeys
import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.platform.FileNameSanitizer
import ai.rever.boss.platform.FileSystemUtils
import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.platform.pickSaveFile
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import com.teamdev.jxbrowser.browser.callback.StartCaptureSessionCallback
import com.teamdev.jxbrowser.browser.callback.StartDownloadCallback
import com.teamdev.jxbrowser.download.Download
import com.teamdev.jxbrowser.download.event.*
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.ProprietaryFeature
import com.teamdev.jxbrowser.engine.Theme
import com.teamdev.jxbrowser.engine.UserDataDirectoryAlreadyInUseException
import com.teamdev.jxbrowser.permission.PermissionType
import com.teamdev.jxbrowser.permission.callback.RequestPermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Toolkit
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Classification of engine initialization errors for better user feedback.
 */
sealed class EngineInitError {
    data class LicenseValidation(
        val message: String,
    ) : EngineInitError()

    data class NetworkError(
        val message: String,
    ) : EngineInitError()

    data class Other(
        val message: String,
        val cause: Throwable?,
    ) : EngineInitError()
}

// Singleton engine for all browser tabs
object FluckEngine {
    private val logger = BossLogger.forComponent("FluckEngine")

    // --- Host-theme-driven Chromium color scheme (prefers-color-scheme) ---
    // NOTE: declared BEFORE the init {} block below, so themeScope is non-null
    // when startHostThemeObserver() runs during object initialization.

    @Volatile
    private var preferredColorSchemeDark: Boolean = true
    private val themeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // Load persisted browser settings (user agent, profile, share-button toggle…)
        // before the first browser/toolbar is created, so saved values apply on boot.
        BrowserSettingsManager.ensureLoaded()
        startHostThemeObserver()
    }

    /**
     * Mirror the active BOSS host theme into the live Chromium engine so web
     * content's `prefers-color-scheme` matches the app. Keyed off the theme's own
     * [ai.rever.boss.plugin.ui.BossAppTheme.isLight] flag, so a new theme needs no
     * change here. Emits the current value immediately, then on every host theme
     * switch.
     */
    private fun startHostThemeObserver() {
        themeScope.launch {
            snapshotFlow { BossThemes.byId(BossThemeController.currentId).isLight }
                .collect { isLight -> setColorScheme(dark = !isLight) }
        }
    }

    /**
     * Set Chromium's theme (drives `prefers-color-scheme`) to match the host.
     * Applied live to the running engine and re-applied on engine (re)creation.
     * Safe to call before the engine exists.
     */
    fun setColorScheme(dark: Boolean) {
        preferredColorSchemeDark = dark
        try {
            _engine?.setTheme(if (dark) Theme.DARK else Theme.LIGHT)
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Failed to apply engine color scheme", mapOf("error" to (e.message ?: "unknown")))
        }
    }

    // @Volatile on all three: written under engineLock but read WITHOUT the lock —
    // _engine via currentEngine/isEngineHealthy/setColorScheme, the other two via
    // initError/isAvailable() on the UI thread. Pre-warm moves the writes to a
    // background thread on every normal launch, so the unlocked reads need a
    // happens-before edge (safe publication for _engine included).
    @Volatile private var _engine: Engine? = null

    @Volatile private var initializationError: Throwable? = null

    @Volatile private var attemptCount = 0
    private var proactiveCleanupDone = false

    /**
     * Whether a pre-warm boot thread is already running. See [prewarmInBackground]: more than one
     * caller can now ask for the head start, and a second thread would just sit on engineLock for
     * the duration of the first boot and then claim credit for it. Cleared again when an attempt
     * fails, so a later caller (a completed engine download, say) still gets its chance.
     */
    private val prewarmStarted = AtomicBoolean(false)

    /**
     * Engine generation counter - increments every time the engine is reinitialized.
     * Browser tabs can use this to detect when their browser instance is stale.
     */
    // @Volatile for the same reason as _engine above — mutated under engineLock
    // (possibly on the pre-warm thread), read lock-free via currentEngineGeneration.
    // Also: non-volatile Long writes aren't guaranteed atomic on the JVM.
    @Volatile private var _engineGeneration = 0L
    private val _engineGenerationFlow = MutableStateFlow(0L)

    /**
     * Observable flow of engine generation changes.
     * Browser tabs should collect this and invalidate/reload when generation changes.
     */
    val engineGenerationFlow: StateFlow<Long> = _engineGenerationFlow.asStateFlow()

    /**
     * Current engine generation. Browsers created before this generation are stale.
     */
    val currentEngineGeneration: Long
        get() = _engineGeneration

    /**
     * Classified initialization error for better user feedback.
     * Returns null if no error or engine initialized successfully.
     */
    val initError: EngineInitError?
        get() = initializationError?.let { classifyError(it) }

    /**
     * Classify the initialization error for user-friendly messages.
     */
    private fun classifyError(e: Throwable): EngineInitError {
        val msg = e.message?.lowercase() ?: ""
        val fullStackTrace = e.stackTraceToString().lowercase()

        return when {
            // Linux sandbox bring-up failures. Hardened distros (Ubuntu 23.10+ /
            // 24.04 restrict unprivileged user namespaces via AppArmor) can fail
            // the zygote/sandbox start now that the sandbox is on by default —
            // the user can't guess the escape hatch from a crashing tab, so the
            // message must carry it. Deliberately matches on the MESSAGE only:
            // a stack-trace match would shadow the license/network branches
            // below, since Linux engine boots traverse sandbox/zygote frames on
            // unrelated failures too.
            msg.contains("sandbox") || msg.contains("zygote") ||
                msg.contains("user namespace") || msg.contains("clone()") -> {
                EngineInitError.Other(
                    "The browser sandbox failed to start. On hardened Linux (e.g. Ubuntu 24.04), " +
                        "enable unprivileged user namespaces, or set BOSS_CHROMIUM_DISABLE_SANDBOX=true " +
                        "and restart BOSS.",
                    e,
                )
            }

            // License validation errors (usually network-related)
            msg.contains("license") || msg.contains("validation") ||
                fullStackTrace.contains("licensecheck") || fullStackTrace.contains("license") -> {
                EngineInitError.LicenseValidation(
                    "License validation failed. Please check your internet connection.",
                )
            }

            // Network/connection errors
            msg.contains("network") || msg.contains("connect") ||
                msg.contains("timeout") || msg.contains("unreachable") ||
                msg.contains("socket") || msg.contains("host") ||
                e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException -> {
                EngineInitError.NetworkError(
                    "Network error. Please check your internet connection and try again.",
                )
            }

            // Other errors
            else -> {
                EngineInitError.Other(
                    e.message ?: "Unknown error occurred",
                    e,
                )
            }
        }
    }

    /**
     * Reset initialization state to allow retry after fixing network issues.
     */
    fun resetInitializationState() {
        // Deliberately NOT synchronized(engineLock): the engine getter holds that
        // lock for the entire multi-second boot, and this is called from UI-thread
        // click handlers (Retry Engine) — taking engineLock here would block the
        // click behind an in-flight boot, exactly the freeze class this code
        // exists to remove. Both fields are @Volatile; an interleave with a
        // concurrent boot can only briefly reorder these benign counters, which
        // the getter's own retry logic absorbs.
        initializationError = null
        attemptCount = 0
    }

    /**
     * Proactively clean up stale lock files and zombie processes on app startup.
     * Call this early in app initialization to ensure session reuse works.
     */
    fun proactiveCleanupOnStartup() {
        if (proactiveCleanupDone) return
        proactiveCleanupDone = true

        val selectedProfile = BrowserSettings.currentProfile
        val profileDirPath = BossDirectories.resolve(selectedProfile).toPath()

        // First, kill any stale Chromium processes from previous sessions
        killStaleChromiumProcesses()

        if (profileDirPath.toFile().exists()) {
            cleanupStaleLockFiles(profileDirPath)
            // Also clean up any other lock-related files
            cleanupAllLockRelatedFiles(profileDirPath)
        }

        // Clean up ALL temporary profiles from previous sessions
        // At startup time, no temp profiles should be in use
        cleanupAllTemporaryProfiles()
    }

    /**
     * Kill stale Chromium processes that were spawned by previous BOSS sessions.
     * These zombie processes can prevent profile reuse even without lock files.
     */
    private fun killStaleChromiumProcesses() {
        var killedAny = false
        try {
            // Use explicit paths for more precise matching (security: avoid killing unrelated processes)
            val bossChromiumDir = BossDirectories.resolve("jxbrowser-chromium").absolutePath
            val bossBrandedChromiumDir = BossDirectories.resolve("boss-chromium").absolutePath
            val bossProfileDir = BossDirectories.resolve("browser-profile").absolutePath
            val currentPid = ProcessHandle.current().pid()
            val currentTimeMs = System.currentTimeMillis()

            // Find all processes that match JxBrowser's Chromium
            // Also catch chrome_crashpad orphans whose parent is dead
            val staleProcesses =
                ProcessHandle
                    .allProcesses()
                    .filter { process ->
                        try {
                            val command = process.info().command().orElse("")
                            val commandLine = process.info().commandLine().orElse("")

                            // Security: First verify it's actually a Chromium/Chrome executable
                            val isChromiumExecutable =
                                command.contains("chrome", ignoreCase = true) ||
                                    command.contains("chromium", ignoreCase = true) ||
                                    command.contains("jxbrowser", ignoreCase = true)

                            if (!isChromiumExecutable) return@filter false

                            // Security: Check if it's from our JxBrowser installation
                            // Use explicit full paths to avoid false positives
                            val isFromBossDir =
                                command.contains(bossChromiumDir) ||
                                    command.contains(bossBrandedChromiumDir) ||
                                    commandLine.contains(bossChromiumDir) ||
                                    commandLine.contains(bossBrandedChromiumDir) ||
                                    commandLine.contains(bossProfileDir)

                            // Also detect orphaned chrome_crashpad processes:
                            // These are helper processes whose parent (the main Chromium) has died.
                            // They have "chrome_crashpad" in the command but may not reference BOSS dirs.
                            // Safe to kill if their parent process is dead (orphaned to PID 1/launchd).
                            val isCrashpadOrphan =
                                command.contains("chrome_crashpad") &&
                                    !process.parent().isPresent

                            val isJxBrowserChromium = isFromBossDir || isCrashpadOrphan

                            // Don't kill processes that belong to current BOSS instance
                            val parentPid = process.parent().map { it.pid() }.orElse(-1L)
                            val isOurChild = parentPid == currentPid

                            // Security: Don't kill processes started less than 5 seconds ago
                            // This prevents killing newly spawned legitimate processes
                            val startTimeMs =
                                process
                                    .info()
                                    .startInstant()
                                    .map { it.toEpochMilli() }
                                    .orElse(currentTimeMs)
                            val processAgeMs = currentTimeMs - startTimeMs
                            val isTooRecent = processAgeMs < 5000

                            isJxBrowserChromium && !isOurChild && !isTooRecent
                        } catch (e: Exception) {
                            // Process may have exited mid-inspection - skip it
                            logger.debug(
                                LogCategory.BROWSER,
                                "Could not inspect process during stale Chromium scan - skipping",
                                mapOf("error" to e.toString()),
                            )
                            false
                        }
                    }.toList()

            staleProcesses.forEach { process ->
                try {
                    val pid = process.pid()
                    val command = process.info().command().orElse("unknown")
                    val commandLine = process.info().commandLine().orElse("unknown")

                    // Log full command line for debugging before killing

                    // Try graceful termination first with proper timeout handling
                    process.destroy()
                    killedAny = true

                    // Wait for graceful shutdown using ProcessHandle.onExit() with timeout
                    // This is more reliable than Thread.sleep() and doesn't block unnecessarily
                    try {
                        process.onExit().get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        // Process didn't exit in time - force kill
                        logger.debug(
                            LogCategory.BROWSER,
                            "Stale Chromium process did not exit in 100ms - force killing",
                            mapOf("error" to e.toString()),
                        )
                        process.destroyForcibly()
                    } catch (e: Exception) {
                        // Process already exited or other error
                        logger.debug(
                            LogCategory.BROWSER,
                            "Wait for stale Chromium exit failed - likely already gone",
                            mapOf("error" to e.toString()),
                        )
                    }
                } catch (e: Exception) {
                    // Kill of one stale process failed - continue with the rest
                    logger.debug(
                        LogCategory.BROWSER,
                        "Failed to terminate stale Chromium process - skipping",
                        mapOf("error" to e.toString()),
                    )
                }
            }

            if (staleProcesses.isEmpty()) {
            }

            // If we killed any processes, wait for them to fully terminate
            // Using Thread.sleep() is acceptable here since this runs during startup
            // before UI initialization (per code review recommendation)
            if (killedAny) {
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            // Cleanup is best-effort - engine startup proceeds either way
            logger.warn(LogCategory.BROWSER, "Stale Chromium process cleanup failed - continuing startup", error = e)
        }
    }

    /**
     * Clean up ALL lock-related files in the profile directory.
     * JxBrowser/Chromium uses multiple files for locking.
     */
    private fun cleanupAllLockRelatedFiles(profileDir: java.nio.file.Path) {
        val lockFiles =
            listOf(
                "SingletonLock",
                "SingletonSocket",
                "SingletonCookie",
                "lockfile",
                ".org.chromium.Chromium.lock", // legacy Chromium bundle id (pre-9.3.0 engines)
                ".com.teamdev.Platinum.lock", // JxBrowser 9.3.0+ renamed the Chromium bundle id
            )

        lockFiles.forEach { fileName ->
            val file = profileDir.resolve(fileName).toFile()
            if (file.exists() && !file.delete()) {
                // A surviving lock file can make the next engine start think another
                // instance owns the profile - worth a breadcrumb, not a failure.
                logger.debug(LogCategory.BROWSER, "Could not delete stale browser lock file", mapOf("file" to fileName))
            }
        }

        // Also check for lock files in Default subdirectory
        val defaultDir = profileDir.resolve("Default")
        if (defaultDir.toFile().exists()) {
            lockFiles.forEach { fileName ->
                val file = defaultDir.resolve(fileName).toFile()
                if (file.exists() && !file.delete()) {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Could not delete stale browser lock file",
                        mapOf("file" to "Default/" + fileName),
                    )
                }
            }
        }
    }

    // Track URLs that are being downloaded to prevent popup handler from opening tabs
    private val activeDownloadUrls = Collections.synchronizedSet(mutableSetOf<String>())

    // Track recently opened tabs that might be download redirects
    // Store tab IDs opened in the last few seconds
    private val recentlyOpenedTabIds = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())

    // Callback to close most recent tab
    private var onCloseMostRecentTab: (() -> Unit)? = null

    // Download manager for tracking all downloads
    val downloadManager = DownloadManager()

    // Download settings (can be persisted later)
    private var downloadSettings = DownloadSettings()

    // Track active downloads for pause/resume operations
    private val activeDownloads = Collections.synchronizedMap(mutableMapOf<String, Download>())

    // Expose current engine instance for shutdown purposes
    val currentEngine: Engine?
        get() = _engine

    /**
     * Check if a URL is currently being downloaded.
     * Used by popup handler to prevent opening new tabs for download links.
     */
    fun isActiveDownload(url: String): Boolean = activeDownloadUrls.contains(url)

    /**
     * Notify that a tab was just opened via popup handler.
     * This tab might be a download redirect and should be auto-closed if download starts soon.
     */
    fun notifyTabOpened() {
        val now = System.currentTimeMillis()
        recentlyOpenedTabIds.add(now to "")

        // Clean up old entries (older than 5 seconds)
        val cutoff = now - 5_000
        recentlyOpenedTabIds.removeIf { it.first < cutoff }
    }

    /**
     * Set callback to close the most recently opened tab.
     * Called by BossApp or tab management system.
     */
    fun setCloseMostRecentTabCallback(callback: () -> Unit) {
        onCloseMostRecentTab = callback
    }

    /**
     * Auto-close the most recently opened tab if it was opened within the last 3 seconds.
     * Called when a download starts.
     */
    private fun autoCloseDownloadTab() {
        val now = System.currentTimeMillis()
        val recentCutoff = now - 3_000 // Tabs opened in last 3 seconds

        // Find tabs opened in the last 3 seconds
        val recentTabs = recentlyOpenedTabIds.filter { it.first >= recentCutoff }

        if (recentTabs.isNotEmpty()) {
            onCloseMostRecentTab?.invoke()
            // Clear the entries
            recentlyOpenedTabIds.removeIf { it.first >= recentCutoff }
        }
    }

    /**
     * Pause an active download.
     * @param downloadId The unique ID of the download to pause
     * @return true when the engine accepted the command; false when the engine
     *   no longer owns the download, so callers must not report success for a
     *   pause Chromium never performed.
     */
    fun pauseDownload(downloadId: String): Boolean = commandDownload(downloadId, "pause") { it.pause() }

    /**
     * Resume a paused download.
     * @param downloadId The unique ID of the download to resume
     * @return true when the engine accepted the command, false otherwise.
     */
    fun resumeDownload(downloadId: String): Boolean = commandDownload(downloadId, "resume") { it.resume() }

    /**
     * Cancel an active or paused download.
     * @param downloadId The unique ID of the download to cancel
     * @return true when the engine accepted the command, false otherwise.
     */
    fun cancelDownload(downloadId: String): Boolean = commandDownload(downloadId, "cancel") { it.cancel() }

    /**
     * Applies [command] to the live engine download registered under
     * [downloadId]. Returns false when the engine has already released the
     * download (finished, failed or cancelled) or the command threw, so a
     * caller can surface a failure instead of a status the engine is not in.
     */
    private fun commandDownload(
        downloadId: String,
        commandName: String,
        command: (Download) -> Unit,
    ): Boolean {
        val download = activeDownloads[downloadId]
        if (download == null) {
            logger.debug(
                LogCategory.BROWSER,
                "No active engine download for command",
                mapOf("downloadId" to downloadId, "command" to commandName),
            )
            return false
        }
        return try {
            command(download)
            true
        } catch (e: Exception) {
            // Download may already be finished or cancelled
            logger.debug(
                LogCategory.BROWSER,
                "Failed to $commandName download",
                mapOf("downloadId" to downloadId, "error" to e.toString()),
            )
            false
        }
    }

    // Lock object for thread-safe engine access
    private val engineLock = Any()

    /** How long [recycleWedgedEngine] waits for a wedged engine to close before force-killing it. */
    private const val ENGINE_CLOSE_TIMEOUT_MS = 5_000L

    /**
     * How long a replacement engine's boot waits for the engine it replaces to let go of the
     * profile directory. Covers [ENGINE_CLOSE_TIMEOUT_MS] plus the force-kill that follows it.
     */
    private const val ENGINE_DRAIN_TIMEOUT_MS = 10_000L

    /** How long the force-kill waits for a doomed Chromium process to actually exit. */
    private const val PROCESS_EXIT_TIMEOUT_MS = 3_000L

    /** Poll interval while waiting for doomed Chromium processes to exit. */
    private const val PROCESS_EXIT_POLL_MS = 100L

    /**
     * Gate that holds a replacement engine's boot until the engine it replaces has really let go
     * of the profile directory.
     *
     * Chromium takes an exclusive lock on `--user-data-dir`. [recycleWedgedEngine] bumps the
     * generation first, so every live handle reports itself stale and every tab comes straight
     * back through the [engine] getter - observed at ~400ms, long before the doomed process is
     * gone. Without this gate that boot loses the race to the lock, and
     * [createEngineWithProfile] quietly falls back to a throwaway `browser-profile-<millis>`
     * directory: the user is signed out of every site they were signed into, and the session
     * they had is stranded in a directory nothing will read again.
     */
    @Volatile private var recycleDrain: CountDownLatch? = null

    // Set by BrowserServiceImpl once its wedge detector has spent its recycle budget: the
    // engine still refuses to create browsers and we have stopped trying to repair it.
    // Read by isEngineHealthy(), which otherwise cannot see a wedge (isClosed stays false).
    @Volatile private var wedgeUnrecoverable = false

    internal fun reportWedgeUnrecoverable(unrecoverable: Boolean) {
        wedgeUnrecoverable = unrecoverable
    }

    val engine: Engine
        get() =
            synchronized(engineLock) {
                // Return cached engine if available AND not closed
                _engine?.let { cachedEngine ->
                    if (!cachedEngine.isClosed) {
                        return@synchronized cachedEngine
                    }
                    // Engine was closed (e.g., during app restart/update flow)
                    // Clear cache and reinitialize
                    _engine = null
                    initializationError = null
                    attemptCount = 0
                    // Increment generation to notify browser tabs that they need to reload
                    _engineGeneration++
                    _engineGenerationFlow.value = _engineGeneration
                }

                // Throw cached error if initialization failed before and we've tried too many times
                if (attemptCount > 3) {
                    initializationError?.let { throw it }
                }

                // Try to initialize
                initializeEngine()
            }

    /**
     * The engine together with the generation it belongs to, read as one atomic step.
     *
     * Reading the two separately is a bug the type system will not catch. Browser creation is
     * time-boxed at 20s, and a recycle triggered by *another* caller's failure lands inside that
     * window routinely — so a caller that reads the generation after creating its browser stamps
     * a browser belonging to the closed engine with the *replacement's* generation. That handle
     * then reports `isValid == true` forever (the generation matches, and the browser's own
     * `isClosed` never flips because the notification would have to arrive over the IPC channel
     * that just died), so nothing invalidates it and no tab recovers, until the first call
     * through it throws ObjectClosedException — which is a crash inside whichever plugin made
     * the call, not a recoverable browser error.
     */
    fun engineWithGeneration(): Pair<Engine, Long> = synchronized(engineLock) { engine to _engineGeneration }

    /**
     * Force-replace an engine that is alive but can no longer create browsers.
     *
     * The [engine] getter above self-heals only when JxBrowser reports the engine closed. A
     * *wedged* engine — Chromium process alive, IPC dead — never trips that check, so every
     * `newBrowser()` keeps failing against the same cached instance until the process happens
     * to die on its own. This performs the identical swap deliberately, on demand.
     *
     * Deliberately does NOT touch the profile directory; that is [resetBrowserProfile], a
     * destructive user-initiated action. Here we only replace the engine.
     *
     * @return true if an engine was dropped, false if there was nothing cached to recycle.
     */
    suspend fun recycleWedgedEngine(reason: String): Boolean {
        val drain = CountDownLatch(1)
        val doomed: Engine
        val doomedProcesses: List<ProcessHandle>
        synchronized(engineLock) {
            val cached = _engine ?: return false
            // Snapshot the doomed engine's Chromium processes HERE: under the lock, while
            // _engine still points at it and before the generation bump. No replacement can
            // exist yet, so every Chromium child of this JVM belongs to the engine being
            // dropped. A moment later that is no longer true and the list would be unsafe.
            doomed = cached
            doomedProcesses = chromiumEngineProcesses()
            recycleDrain = drain
            // Exactly the mutations the getter performs when it finds a closed engine:
            // drop the cached instance, clear the retry budget, and bump the generation
            // so every live BrowserHandle reports itself invalid and its tab reloads onto
            // the replacement (BrowserHandleImpl.isValid / Fluck.kt's generation effect).
            _engine = null
            initializationError = null
            attemptCount = 0
            _engineGeneration++
            _engineGenerationFlow.value = _engineGeneration
        }

        logger.warn(
            LogCategory.BROWSER,
            "Recycling wedged browser engine",
            mapOf(
                "reason" to reason,
                "newGeneration" to _engineGeneration,
                "chromiumProcesses" to doomedProcesses.size,
            ),
        )

        try {
            // close() runs OUTSIDE engineLock and on its own scope, for three reasons: a wedged
            // engine can block in there for as long as its dead IPC takes to give up; the getter
            // needs the lock to boot the replacement; and await() gives the timeout a real
            // suspension point to fire on — wrapping the blocking close() directly in
            // withTimeoutOrNull would never interrupt it.
            val closeJob = CoroutineScope(Dispatchers.IO).async { runCatching { doomed.close() } }
            val closeResult = withTimeoutOrNull(ENGINE_CLOSE_TIMEOUT_MS) { closeJob.await() }

            // Whether close() *returned* says nothing about whether Chromium is gone, and the
            // difference is the whole bug this branch was written for. Against a wedged engine
            // close() fails fast with a dead-IPC error rather than hanging, so a timeout-only
            // test read that instant failure as success and skipped the kill entirely: the
            // process tree lived on, kept holding the profile lock (sending the replacement to
            // a throwaway temp profile, i.e. signing the user out everywhere) and kept playing
            // whatever audio it had. Ask the OS instead.
            val survivors = doomedProcesses.filter { runCatching { it.isAlive }.getOrDefault(false) }
            if (survivors.isNotEmpty()) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Wedged engine left Chromium processes alive - killing them",
                    mapOf(
                        "closeOutcome" to closeOutcomeLabel(closeResult),
                        "processes" to survivors.size,
                        "pids" to survivors.joinToString(",") { it.pid().toString() },
                    ),
                )
                withContext(Dispatchers.IO) { killEngineProcesses(survivors) }
            }
        } finally {
            // Releases the replacement engine's boot (see [recycleDrain]). In a finally so a
            // failure in here cannot leave every later boot waiting out the full timeout.
            drain.countDown()
        }
        return true
    }

    /** How [recycleWedgedEngine]'s close attempt ended, for the log line that reports survivors. */
    private fun closeOutcomeLabel(result: Result<Unit>?): String =
        when {
            result == null -> "timed-out"
            result.isSuccess -> "returned"
            else -> "threw: ${result.exceptionOrNull()?.message ?: "unknown"}"
        }

    /**
     * The Chromium process trees this JVM has spawned, for a targeted kill.
     *
     * [killStaleChromiumProcesses] cannot serve this: it deliberately skips any process whose
     * parent is this one, so that a sweep can never kill the *live* engine. The engine being
     * recycled is precisely such a child, which is why a wedged engine used to survive the
     * force-kill path and go on holding the profile lock for the rest of the session.
     *
     * Safety here comes from *when* this is called rather than from what it excludes: under
     * engineLock, before the generation bump, when the only engine that can own a Chromium
     * child is the one being dropped.
     */
    private fun chromiumEngineProcesses(): List<ProcessHandle> =
        runCatching {
            val brandedDir = BossDirectories.resolve("boss-chromium").absolutePath
            val legacyDir = BossDirectories.resolve("jxbrowser-chromium").absolutePath
            ProcessHandle
                .current()
                .children()
                .filter { child ->
                    val command = runCatching { child.info().command().orElse("") }.getOrDefault("")
                    command.startsWith(brandedDir) || command.startsWith(legacyDir)
                }.toList()
        }.getOrElse {
            logger.warn(LogCategory.BROWSER, "Could not enumerate Chromium processes for recycle", error = it)
            emptyList()
        }

    /**
     * Terminate the given engine processes and everything they spawned.
     *
     * Descendants are collected BEFORE the parent is signalled: killing the browser process
     * reparents its helpers to pid 1, and an orphan can no longer be reached from here. The
     * audio helper is one of those, which is what keeps a video audible after its tab is gone.
     *
     * Not covered: `chrome_crashpad_handler`, which JxBrowser reparents to pid 1 at startup, so
     * it is neither a child nor a descendant of anything we hold. It is ~10MB, holds no lock,
     * and [killStaleChromiumProcesses] already sweeps orphaned crashpad handlers on startup.
     */
    internal suspend fun killEngineProcesses(processes: List<ProcessHandle>) {
        val tree =
            processes.flatMap { engineProcess ->
                runCatching { engineProcess.descendants().toList() }.getOrDefault(emptyList()) + engineProcess
            }
        tree.forEach { runCatching { it.destroy() } }
        awaitProcessExit(tree)

        // SIGTERM is advisory, and a wedged (or SIGSTOPped) Chromium ignores it. SIGKILL is
        // what actually frees the --user-data-dir lock the replacement needs.
        val stubborn = tree.filter { runCatching { it.isAlive }.getOrDefault(false) }
        if (stubborn.isNotEmpty()) {
            stubborn.forEach { runCatching { it.destroyForcibly() } }
            awaitProcessExit(stubborn)
        }

        val remaining = tree.count { runCatching { it.isAlive }.getOrDefault(false) }
        if (remaining > 0) {
            logger.warn(
                LogCategory.BROWSER,
                "Chromium processes survived SIGKILL - replacement engine may fall back to a temp profile",
                mapOf("remaining" to remaining),
            )
        }
    }

    internal suspend fun awaitProcessExit(processes: List<ProcessHandle>) {
        val deadline = System.currentTimeMillis() + PROCESS_EXIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline &&
            processes.any { runCatching { it.isAlive }.getOrDefault(false) }
        ) {
            delay(PROCESS_EXIT_POLL_MS)
        }
    }

    /**
     * Hold a boot until an engine being recycled has released the profile directory.
     *
     * Called from [initializeEngine], which runs under engineLock — deliberately, since the
     * point is that no engine boots while the old one is draining. [recycleWedgedEngine] takes
     * that lock only for its opening snapshot, before this can be reached, so waiting here
     * cannot deadlock the recycle that is going to release it.
     */
    private fun awaitRecycleDrain() {
        val drain = recycleDrain ?: return
        if (drain.count == 0L) return
        if (!drain.await(ENGINE_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            logger.warn(
                LogCategory.BROWSER,
                "Recycled engine still draining - booting anyway, profile directory may still be locked",
                mapOf("timeoutMs" to ENGINE_DRAIN_TIMEOUT_MS),
            )
        }
    }

    // ---- RPA profile helpers (used by BrowserServiceImpl's managed profiles) ----
    // JxBrowser profiles are isolated cookie/storage/network contexts inside
    // the single shared engine. They are the isolation primitive for running
    // multiple RPAs with different credentials concurrently.

    /** Create a fresh isolated profile for an RPA run. Caller must delete it when done. */
    fun newRpaProfile(name: String): com.teamdev.jxbrowser.profile.Profile = synchronized(engineLock) { engine.profiles().newProfile(name) }

    /** Look up an existing profile by name, or null. */
    fun findProfile(name: String): com.teamdev.jxbrowser.profile.Profile? =
        try {
            synchronized(engineLock) { engine.profiles().list().firstOrNull { it.name() == name } }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Profile lookup failed - treating as not found",
                mapOf("profile" to name, "error" to e.toString()),
            )
            null
        }

    /** Delete an RPA profile and its on-disk data. Safe to call if already gone. */
    fun deleteRpaProfile(profile: com.teamdev.jxbrowser.profile.Profile) {
        try {
            synchronized(engineLock) { engine.profiles().delete(profile) }
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Error deleting RPA profile", mapOf("error" to (e.message ?: "unknown")))
        }
    }

    /**
     * Delete leftover profiles whose name starts with [prefix] (e.g. ephemeral
     * "rpa-eph-" profiles orphaned by a previous/crashed session). Returns the
     * number removed. Never touches the default profile.
     */
    fun cleanupOrphanedRpaProfiles(prefix: String): Int =
        try {
            synchronized(engineLock) {
                val profiles = engine.profiles()
                val orphans = profiles.list().filter { !it.isDefault && it.name().startsWith(prefix) }
                orphans.forEach { profiles.delete(it) }
                if (orphans.isNotEmpty()) {
                    logger.info(LogCategory.BROWSER, "Cleaned up orphaned RPA profiles", mapOf("count" to orphans.size))
                }
                orphans.size
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Error cleaning orphaned RPA profiles", mapOf("error" to (e.message ?: "unknown")))
            0
        }

    // --- Env flag helpers. Pure variants are internal so tests can cover them. ---
    private val ENV_TRUE = setOf("1", "true", "yes", "on")
    private val ENV_FALSE = setOf("0", "false", "no", "off")

    internal fun isTruthyFlag(value: String?): Boolean = value?.trim()?.lowercase() in ENV_TRUE

    internal fun isFalsyFlag(value: String?): Boolean = value?.trim()?.lowercase() in ENV_FALSE

    /**
     * Falsiness of a tunable, resolved through [ai.rever.boss.config.ConfigLoader] rather than
     * `System.getenv`.
     *
     * The distinction matters since these became Settings rows: settings are published as system
     * properties at startup, and getenv cannot see a system property — so a getenv read here would
     * accept the environment and silently ignore the app's own setting. ConfigLoader keeps env
     * first, so the override precedence is unchanged; it just stops being the only source.
     *
     * For CAPABILITY-granting keys use [capabilityValue] instead, which deliberately excludes
     * local.properties.
     */
    private fun configIsFalse(name: String) =
        isFalsyFlag(
            ai.rever.boss.config.ConfigLoader
                .getConfig(name),
        )

    /**
     * A CAPABILITY-GRANTING tunable: environment variable or system property ONLY, never
     * [ai.rever.boss.config.ConfigLoader].
     *
     * ConfigLoader also ranks local.properties and the embedded build config, and for these two
     * keys that is a trust boundary rather than a convenience. Moving them off `System.getenv` to
     * make Settings reachable by the engine quietly handed local.properties the power to turn off
     * the Chromium sandbox for every future run of a checkout — the exact property the DevTools
     * port is kept out of ConfigLoader to avoid, applied to a capability at least as strong.
     *
     * It was invisible as well as wrong: the sandbox opt-out is applied through
     * `EngineOptions.disableSandbox()` rather than a switch, so it cannot show under "Active this
     * session"; `envNote` reads only the environment; and `settings.disableSandbox` stays null, so
     * the Danger Zone toggle renders OFF. The sandbox would be disabled while every surface in the
     * app said it was on.
     *
     * The system property is still honoured, because that is exactly how
     * [ai.rever.boss.config.ChromiumFlagsSettingsManager.applyToSystemProperties] delivers the
     * user's own setting — so this costs the Settings screen nothing. Precedence within the pair
     * matches ConfigLoader: environment first.
     */
    private fun capabilityValue(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }

    private fun capabilityIsTrue(name: String) = isTruthyFlag(capabilityValue(name))

    /**
     * The outcome of parsing the extra-switches field, split three ways.
     *
     * [malformed] and [gated] are separate because they are different mistakes with different
     * fixes, and folding them together made the app tell a user who typed `--no-sandbox` that
     * their entry did not start with `--` — plainly false, and it sends them to fix the wrong
     * thing. One list, one message, meant the message could only be right for one of the cases.
     */
    internal data class ExtraSwitches(
        val accepted: List<String> = emptyList(),
        val malformed: List<String> = emptyList(),
        val gated: List<String> = emptyList(),
    )

    /**
     * Parse BOSS_CHROMIUM_EXTRA_SWITCHES: whitespace-separated, exactly like a
     * Chromium command line. NOT comma-separated — commas are Chromium's own
     * separator inside feature-list values (--enable-features=A,B), which must
     * pass through intact. Entries must look like switches; values with embedded
     * spaces are not supported. Returns (accepted switches, dropped tokens) from
     * a single tokenization so the accept filter and the dropped-token warning
     * can never diverge.
     */
    internal fun partitionExtraSwitches(raw: String?): ExtraSwitches {
        val tokens = raw?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() } ?: emptyList()
        return ExtraSwitches(
            accepted = tokens.filter { it.startsWith("--") && !isGatedSwitch(it) },
            malformed = tokens.filterNot { it.startsWith("--") },
            gated = tokens.filter { it.startsWith("--") && isGatedSwitch(it) },
        )
    }

    /**
     * Switches that already have their own Settings row behind a confirmation dialog, and are
     * therefore refused from the free-form extra-switches field.
     *
     * Without this the text box is a way around the two confirmations, not an escape hatch
     * alongside them: `--no-sandbox` reaches the same place as the sandbox toggle, and
     * `--remote-debugging-port=9222` reaches the same place as the DevTools port — a port that is
     * deliberately kept out of ConfigLoader precisely so it cannot arrive by a quiet path. A
     * confirmation the user can sidestep by typing is not a confirmation.
     *
     * Deliberately narrow. This is NOT an attempt to sanitise Chromium switches in general, which
     * is not a winnable game (`--disable-web-security`, `--proxy-server`, `--load-extension` are
     * all still accepted, and the field is documented as unrestricted). It closes only the paths
     * that bypass a gate this app itself put up; anything else remains the operator's call.
     *
     * Prefix-matched on the switch name so `--no-sandbox` and `--remote-debugging-port=9222` are
     * both caught, and refused entries surface in the UI's "will be ignored" list rather than
     * being dropped silently.
     */
    internal fun isGatedSwitch(token: String): Boolean {
        // Lowercased before matching. Chromium's own switch lookup is case-sensitive, so
        // `--No-Sandbox` would not disable the sandbox and letting it through is harmless — but
        // the cost of being wrong about that on some platform is an ungated capability, and the
        // cost of being over-strict is refusing a switch spelling nobody uses.
        val name = token.substringBefore('=').lowercase()
        return name in GATED_SWITCHES
    }

    private val GATED_SWITCHES =
        setOf(
            "--no-sandbox",
            "--disable-gpu-sandbox",
            "--disable-setuid-sandbox",
            "--remote-debugging-port",
            "--remote-debugging-pipe",
            "--remote-allow-origins",
        )

    private val WHITESPACE = Regex("\\s+")

    /** Accepted switches only — see [partitionExtraSwitches]. */
    internal fun parseExtraSwitches(raw: String?): List<String> = partitionExtraSwitches(raw).accepted

    /**
     * Warm the engine on a background thread so the first browser tab doesn't pay
     * the full Chromium boot (process spawn, profile open, license validation) on
     * the UI path. Without this, the engine initializes lazily and synchronously
     * inside the first tab's composition — a multi-second freeze on cold starts.
     *
     * Deliberate default: pre-warm only when the browser profile directory already
     * exists, i.e. this machine has used the browser before. Browser-less sessions
     * (terminal-only, editor-only, first run) never pay the Chromium spawn; the
     * first real browser use creates the profile, and every launch after that
     * pre-warms. Opt out entirely with BOSS_BROWSER_PREWARM=false.
     *
     * [force] is for the callers that already know a browser tab is coming, where the
     * profile-existence gate is answering a question nobody asked. It reads "has this machine
     * ever used the browser", and on a first install the answer is no for a reason that has
     * nothing to do with the user's intent - so the pre-warm was skipped on precisely the launch
     * that pays the most for skipping it, including immediately after the user waited through a
     * several-hundred-megabyte download OF THE BROWSER ENGINE. Forcing is deliberately not the
     * default: it creates the profile directory as a side effect, which would flip the unforced
     * gate on permanently and pre-warm every later launch for a user who never opens a browser.
     *
     * Called once from app startup after [proactiveCleanupOnStartup]. A pre-warm
     * failure must not poison user-facing availability (the user never asked for
     * this boot), so it clears the recorded init error — the first real use
     * re-attempts initialization and surfaces its own error through the normal
     * createBrowser flow.
     *
     * Guarantee calibration: this removes the freeze, it doesn't make it
     * impossible. The [engine] getter holds engineLock for the whole boot, so a
     * tab opened WHILE pre-warm is still booting blocks on that lock for the
     * remainder of the boot — a head start, not an exclusion.
     *
     * The gate checks the CONFIGURED primary profile directory; the boot itself
     * may fall back to a temp profile (browser-profile-<ts>) if the primary is
     * locked by another instance. The two profile notions intentionally differ —
     * the gate only decides whether the head start happens, never correctness.
     */
    fun prewarmInBackground(force: Boolean = false) {
        val decision =
            prewarmDecision(
                prewarmDisabled = configIsFalse(ChromiumFlagKeys.PREWARM),
                force = force,
                engineUsable = { hasUsableEngine(cacheIsHealthy()) },
                profileExists = { BossDirectories.resolve(BrowserSettings.currentProfile).exists() },
            )
        if (decision != PrewarmDecision.RUN) {
            // The reason, not a guess at it. One shared exit used to log "no browser profile on
            // this machine yet" for an opt-out that had nothing to do with the profile, and logged
            // nothing at all for the most surprising outcome of the three - a caller that knew a
            // tab was coming, overruled.
            logger.debug(LogCategory.BROWSER, "Skipping engine pre-warm - ${decision.reason}")
            return
        }
        // One boot thread at a time. The getter is synchronized, so a second thread would only
        // block on engineLock for the whole first boot and then log a "pre-warmed" line for work
        // it did not do - and now that several call sites can ask (startup, a completed download, a
        // workspace carrying a browser tab), that stopped being hypothetical.
        //
        // Released in the `finally` below, so the flag means what its name and its log line claim:
        // a boot thread is RUNNING. Held for the process lifetime instead, an engine recycle
        // (which drops _engine and is exactly when a head start is worth something again) would
        // leave every later caller refused for good.
        if (!claimPrewarmSlot()) {
            logger.debug(LogCategory.BROWSER, "Engine pre-warm already under way")
            return
        }
        Thread({
            try {
                // A live engine needs no head start, and saying it was pre-warmed here would be
                // this method claiming credit for a boot somebody else paid for.
                if (isEngineHealthy() && _engine != null) {
                    logger.debug(LogCategory.BROWSER, "Engine pre-warm skipped - engine already running")
                    return@Thread
                }
                val startNs = System.nanoTime()
                engine
                logger.info(
                    LogCategory.BROWSER,
                    "Browser engine pre-warmed",
                    mapOf(
                        "durationMs" to (System.nanoTime() - startNs) / 1_000_000,
                    ),
                )
            } catch (e: Throwable) {
                // Errors (UnsatisfiedLinkError from a broken Chromium bundle, OOM)
                // deserve visibility; plain Exceptions (transient network/license)
                // are routine and stay at debug.
                if (e is Error) {
                    logger.warn(
                        LogCategory.BROWSER,
                        "Engine pre-warm failed with a serious error (lazy init will retry on first use)",
                        error = e,
                    )
                } else {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Engine pre-warm failed (lazy init will retry on first use)",
                        mapOf(
                            "error" to (e.message ?: "unknown"),
                        ),
                    )
                }
                clearInitStateIfErrorIs(e)
            } finally {
                // Covers all three exits - booted, skipped as already running, failed. A failed
                // attempt bought nothing and must not refuse the next caller with a reason to ask
                // (a download that has just finished); a successful one leaves an engine the
                // `already running` check above answers for.
                releasePrewarmSlot()
            }
        }, "fluck-engine-prewarm").apply { isDaemon = true }.start()
    }

    /** Whether a pre-warm runs, and if not, the reason a reader of the log needs. */
    internal enum class PrewarmDecision(
        val reason: String,
    ) {
        RUN("nothing in the way"),

        /** BOSS_BROWSER_PREWARM=false. Outranks `force`. */
        OPTED_OUT("BOSS_BROWSER_PREWARM opts out"),

        /** No engine directory passes the version check, so a boot could only fail. */
        NO_USABLE_ENGINE("no usable browser engine to warm"),

        /** This machine has never opened a browser and nobody said one is coming. */
        NEVER_USED_BROWSER("no browser profile on this machine yet"),
    }

    /**
     * Whether a pre-warm should run, and why not when it should not.
     *
     * Pure given its suppliers, so every branch is testable without an engine, a profile directory
     * or a machine that has never opened a browser - and the suppliers are lazy so a decision the
     * cheap checks already settled costs no filesystem work.
     *
     * Order is the whole content of this function:
     *
     * - **The opt-out outranks [force].** BOSS_BROWSER_PREWARM=false is the user saying no Chromium
     *   boot they did not ask for; a caller knowing a tab is coming does not overrule that, and the
     *   tab boots the engine itself when it gets there.
     * - **A usable engine outranks [force] too**, and this is not a nicety. Without an engine the
     *   boot walks straight into `getChromiumDir()` throwing, which logs an error and burns an
     *   attempt for nothing. `applyWorkspace` runs on every window and every workspace switch, so
     *   a forced call with no gate would repeat that indefinitely on an engine-less machine - and,
     *   worse, could hold the boot slot at the moment a completed download wants it.
     * - **[force] then outranks the profile check**, which is the change this whole gate is about.
     */
    internal fun prewarmDecision(
        prewarmDisabled: Boolean,
        force: Boolean,
        engineUsable: () -> Boolean,
        profileExists: () -> Boolean,
    ): PrewarmDecision =
        when {
            prewarmDisabled -> PrewarmDecision.OPTED_OUT
            !engineUsable() -> PrewarmDecision.NO_USABLE_ENGINE
            force -> PrewarmDecision.RUN
            profileExists() -> PrewarmDecision.RUN
            else -> PrewarmDecision.NEVER_USED_BROWSER
        }

    /** Claim the single pre-warm boot slot. False when a boot thread is already running. */
    internal fun claimPrewarmSlot(): Boolean = prewarmStarted.compareAndSet(false, true)

    /** Release the boot slot. See [prewarmInBackground] for why this is not one-way. */
    internal fun releasePrewarmSlot() = prewarmStarted.set(false)

    /**
     * Clear the recorded init state ONLY if [expected] is still the recorded
     * error — i.e. this pre-warm attempt's own failure. Without the guard, a
     * user-initiated boot that failed AFTER the pre-warm (recording its own,
     * legitimate error) would have that error silently wiped by the pre-warm's
     * cleanup, making isAvailable() report healthy with no working engine.
     * Runs under engineLock for atomicity against the getter's mutations —
     * safe here because this is only called from the pre-warm background
     * thread, where briefly waiting out an in-flight boot is harmless (unlike
     * the UI-path [resetInitializationState], which must stay lock-free).
     *
     * Error-class failures (UnsatisfiedLinkError, OOM) fall through harmlessly:
     * createEngineWithProfile records only Exceptions into initializationError,
     * so for an Error the recorded value is still null, the identity check
     * fails, and nothing is cleared — the correct outcome, since nothing was
     * poisoned in the first place.
     */
    private fun clearInitStateIfErrorIs(expected: Throwable) {
        synchronized(engineLock) {
            if (initializationError === expected) {
                initializationError = null
                attemptCount = 0
            }
        }
    }

    private fun initializeEngine(): Engine {
        attemptCount++

        // Nothing below can boot while the engine this one replaces is still holding the
        // profile directory open. See [recycleDrain] for what that costs when it is skipped.
        awaitRecycleDrain()

        // NOTE: screen-recording permission is intentionally NOT requested here.
        // Asking at engine startup is an unexplained, abrupt OS prompt. It is now
        // requested lazily on the first user-initiated screen share, after an in-app
        // rationale dialog (see setupCaptureSessionHandler + ScreenCaptureNotifier).

        // getChromiumDir now returns only a directory that already passed the
        // version check, so the separate veto that used to sit here can never fire
        // — resolveEngineDir enforces it structurally instead of by convention.
        // What remains is making sure its diagnosis reaches the user: this throw
        // precedes createEngineWithProfile, which is the only other place
        // initializationError is assigned, so without recording it here initError
        // stays null, getBrowserState swallows the exception, and the tab renders
        // "Could not initialize browser ... window not ready" instead of the reason.
        val chromiumDir =
            runCatching { getChromiumDir() }.getOrElse { e ->
                logger.error(
                    LogCategory.BROWSER,
                    "No usable browser engine",
                    mapOf("reason" to (e.message ?: "unknown")),
                )
                initializationError = e
                throw e
            }

        // Create directories if they don't exist
        chromiumDir.toFile().mkdirs()

        // Clean up old temporary profiles on startup (older than 24 hours)
        cleanupOldTemporaryProfiles()

        // Try to create engine with profile handling
        return createEngineWithProfile(chromiumDir)
    }

    /**
     * Get the Chromium directory to use, with priority:
     * 1. Bundled BOSS-branded Chromium (in app resources)
     * 2. Cached BOSS-branded Chromium (~/.boss/boss-chromium/)
     */
    private fun getChromiumDir(): java.nio.file.Path = resolveEngineDir() ?: throw IllegalStateException(noUsableEngineReason())

    /**
     * Why no engine could be selected, as specifically as the candidates allow.
     *
     * Since [resolveEngineDir] only ever returns a directory that already passed
     * the version check, the veto inside engine creation can no longer fire — this
     * is where that diagnosis has to live instead. Without it a stale engine
     * reports "BOSS-branded Chromium not found", which is both wrong (it is
     * present, just stale) and names a folder that may not be the offending one.
     */
    internal fun noUsableEngineReason(locations: List<java.nio.file.Path> = engineLocations()): String {
        // Derived from engineLocations, NOT engineCandidates: a diagnosis has to
        // describe the engines that were rejected, and the candidate list has by
        // definition already dropped them.
        val present = locations.filter { isValidChromiumDir(it) }

        return present.firstNotNullOfOrNull { chromiumVersionMismatch(it) }
            ?: if (present.isNotEmpty()) {
                "The installed browser engine is not usable with this build of BOSS. " +
                    "Restart BOSS to download the matching engine."
            } else {
                "BOSS-branded Chromium not found. Please restart the app to trigger auto-download, " +
                    "or manually install to ~/.boss/boss-chromium/"
            }
    }

    /**
     * The engine directory that will actually boot, or null when none can.
     *
     * Candidates in priority order — bundled in the app image, then the downloaded
     * cache — and a candidate only wins if it is **usable**: well-formed *and*
     * carrying the Chromium build this jar needs.
     *
     * The version check is part of the choice rather than a later veto because the
     * bundled engine is not repairable. `ChromiumAutoDownloader` writes only to the
     * cache, so if a stale bundled engine won an unconditional first-priority match,
     * every download would land in a directory this resolver then ignored — the
     * repair path could never repair anything. Skipping an unusable candidate lets
     * the download take effect.
     *
     * Exposed so startup can ask the same question it is about to act on. Answering
     * "is an engine installed?" by inspecting only the cache is what let a mismatched
     * engine boot with the guard reporting everything fine (BossConsole#121).
     */

    /** Paths already reported as stale, so the warning is not repeated per call. */
    private val warnedStalePaths =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** Whether the downloaded cache is well-formed and at the required version. */
    private fun cacheIsHealthy(): Boolean = ChromiumAutoDownloader.isChromiumInstalled()

    internal fun resolveEngineDir(cacheHealthy: Boolean = cacheIsHealthy()): java.nio.file.Path? =
        firstUsableEngineDir(engineCandidates(cacheHealthy = cacheHealthy))

    /**
     * Engine directories to consider, in priority order.
     *
     * The cache is included only when [cacheHealthy] — `isChromiumInstalled()` by
     * default — because that is the only check that works on EVERY platform: it
     * compares `version.txt` against `effectiveVersion` unconditionally, and
     * additionally rejects a macOS binary that has lost its execute bit.
     *
     * [chromiumVersionMismatch] cannot stand in for it. `frameworkVersionsDir`
     * returns null off macOS by design, so the usability predicate collapses to
     * "executable.name exists" there — a stale Windows/Linux cache would sail
     * through the guard, pre-warm against the wrong engine, and bring back the
     * UnsatisfiedLinkError this whole line of work exists to prevent.
     *
     * The bundled engine is checked by [bundledStampIsAcceptable] instead. It gets a
     * `version.txt` of its own, written by every bundling site in `release.yml`, so
     * the same cross-platform signal covers both candidates. Before that stamp
     * existed this paragraph claimed the bundled engine was "consistent with the jar
     * by construction" — the assumption BossConsole#123 disproved.
     *
     * Parameters are injectable purely so the rule is testable — the real values
     * come from `java.home` and the user's home directory, which a test cannot
     * fabricate.
     */
    internal fun engineCandidates(
        bundled: java.nio.file.Path? = getBundledChromiumPath(),
        cache: java.nio.file.Path = BossDirectories.resolve("boss-chromium").toPath(),
        cacheHealthy: Boolean = cacheIsHealthy(),
        required: String = ChromiumAutoDownloader.effectiveVersion,
    ): List<java.nio.file.Path> =
        listOfNotNull(
            bundled?.takeIf { bundledStampIsAcceptable(it, required) },
            cache.takeIf { cacheHealthy },
        )

    /**
     * Every place an engine may live, unfiltered.
     *
     * Separate from [engineCandidates] because the diagnosis needs to see engines
     * the selection rule *rejected* — that is the whole point of a diagnosis. With
     * both derived from the filtered list, a stale bundled engine was excluded and
     * the reason fell through to "BOSS-branded Chromium not found" while it sat
     * right there, reintroducing exactly the wrong-message problem #122 removed.
     */
    internal fun engineLocations(
        bundled: java.nio.file.Path? = getBundledChromiumPath(),
        cache: java.nio.file.Path = BossDirectories.resolve("boss-chromium").toPath(),
    ): List<java.nio.file.Path> = listOfNotNull(bundled, cache)

    /**
     * Whether a bundled engine's version stamp permits using it.
     *
     * Deliberately more lenient than the cache's check, and the asymmetry is the
     * point. The cache is written by us, so a *missing* `version.txt` there means a
     * broken extraction and `isChromiumInstalled()` rejects it. A bundled engine is
     * copied in at packaging time and older app images predate stamping entirely,
     * so a missing stamp here means "can't tell" and is allowed through — the same
     * fail-open rule the framework probe uses.
     *
     * What this does catch is a stamp that is *present and wrong*, which is the only
     * signal available off macOS: `frameworkVersionsDir` returns null there, so
     * without this a mis-built release ships a stale bundled engine that wins first
     * priority, is never checked, and cannot be repaired by a download — the
     * download writes to the cache, which the resolver then never reaches
     * (BossConsole#123).
     */
    private fun bundledStampIsAcceptable(
        bundled: java.nio.file.Path,
        required: String = ChromiumAutoDownloader.effectiveVersion,
    ): Boolean {
        val stamped = ChromiumAutoDownloader.installedVersionAt(bundled)
        val acceptable = stamped == null || stamped == required
        // Once per path: engineCandidates is called from hasUsableEngine at startup,
        // from getChromiumDir on every initializeEngine attempt (which retries), and
        // from the diagnosis — so a stale bundle otherwise logs the same WARN several
        // times a launch and reads like several distinct faults during triage.
        if (!acceptable && warnedStalePaths.add(bundled.toString())) {
            logger.warn(
                LogCategory.BROWSER,
                "Ignoring bundled browser engine stamped with a different version",
                mapOf("stamped" to (stamped ?: "none"), "required" to required),
            )
        }
        return acceptable
    }

    /**
     * The first candidate that is well-formed and carries the required Chromium build.
     *
     * Split from [resolveEngineDir] so the selection rule is testable: the real
     * candidate list starts from `java.home`, which a test cannot fabricate.
     */
    internal fun firstUsableEngineDir(candidates: List<java.nio.file.Path>): java.nio.file.Path? =
        candidates.firstOrNull { isValidChromiumDir(it) && chromiumVersionMismatch(it) == null }

    /** Whether an engine that can actually boot is present. */
    internal fun hasUsableEngine(cacheHealthy: Boolean): Boolean = resolveEngineDir(cacheHealthy) != null

    /** What startup should do about the engine. */
    internal enum class EngineStartupAction {
        /** An engine is present and usable — boot normally. */
        Boot,

        /** Nothing usable and a fetch can repair it — show the download UI. */
        Download,

        /**
         * Nothing usable, but the cache is healthy and already stamped with the
         * version we would fetch — so the published archive does not carry the
         * Chromium build this jar needs. Re-fetching would download hundreds of MB
         * every launch and never converge; start instead and let the browser report
         * the mismatch.
         */
        BootAndReport,
    }

    /**
     * The startup decision, as a function of the two things it depends on.
     *
     * Extracted from `fun main` because it is the highest-risk logic in this area
     * and was the only part with no coverage. Keyed on [cacheHealthy] — i.e.
     * `isChromiumInstalled()` — rather than on version.txt alone: that file still
     * reads correctly for a cache with a missing `executable.name` or a lost
     * execute bit, both of which a re-download *does* repair. Suppressing the
     * download on the version stamp alone turned ordinary local corruption into a
     * terminal state with no in-app way out.
     */
    internal fun engineStartupAction(
        hasUsableEngine: Boolean,
        cacheHealthy: Boolean,
    ): EngineStartupAction =
        when {
            hasUsableEngine -> EngineStartupAction.Boot
            cacheHealthy -> EngineStartupAction.BootAndReport
            else -> EngineStartupAction.Download
        }

    /**
     * Why the engine at [chromiumDir] cannot serve this build's JxBrowser, or null
     * if it can.
     *
     * JxBrowser loads its native toolkit from
     * `<executable>.app/Contents/Frameworks/Chromium Framework.framework/Versions/<chromium>/Libraries/`,
     * where `<chromium>` is [com.teamdev.jxbrowser.VersionInfo.chromiumVersion] — a
     * value compiled into the jar. So the engine on disk has to carry exactly the
     * Chromium build this jar was made against; anything else fails at `System.load`
     * no matter how well-formed the directory is.
     *
     * macOS only: the `Versions/<chromium>` layout is specific to the framework
     * bundle. Elsewhere this makes no claim rather than guessing.
     */
    internal fun chromiumVersionMismatch(chromiumDir: java.nio.file.Path): String? =
        frameworkVersionsDir(chromiumDir)?.let { chromiumMismatchMessage(it) }

    /**
     * The mismatch message for an already-located `Versions` directory, or null when
     * it carries the required build.
     *
     * Split from the filesystem/OS probing above so the comparison — the part that
     * decides whether an engine boots — is exercised on every CI leg rather than
     * only the macOS one.
     *
     * Deliberately omits the engine path. This string reaches [classifyError], which
     * substring-matches the message for "host", "connect", "license" and friends to
     * pick a remedy; a home directory containing any of those would be classified as
     * a network or licensing failure and shown the wrong advice. The path is logged
     * at the throw site instead.
     */
    internal fun chromiumMismatchMessage(versionsDir: java.io.File): String? {
        val required =
            com.teamdev.jxbrowser.VersionInfo
                .chromiumVersion()
        return if (versionsDir.resolve(required).isDirectory) {
            null
        } else {
            val present =
                versionsDir
                    .listFiles()
                    ?.filter { it.isDirectory && it.name != "Current" }
                    ?.joinToString(", ") { it.name }
                    ?.ifEmpty { "none" }
                    ?: "none"
            // No remedy naming a specific folder: the mismatched engine may be the
            // one bundled in the app image, which deleting the cache would not touch.
            // resolveEngineDir now skips an unusable candidate, so a restart genuinely
            // repairs both cases on its own.
            "Installed browser engine does not match this build of BOSS. " +
                "JxBrowser ${com.teamdev.jxbrowser.VersionInfo.version()} needs Chromium $required, " +
                "but the installed engine provides: $present. " +
                "Restart BOSS to download the matching engine."
        }
    }

    /**
     * The framework's `Versions` directory, or null when this isn't a layout we model.
     *
     * Every "can't tell" answer is null: refusing to boot on a guess would be a worse
     * failure than the cryptic error this exists to replace.
     */
    private fun frameworkVersionsDir(chromiumDir: java.nio.file.Path): java.io.File? {
        val isMac =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")
        val executableName =
            if (!isMac) {
                null
            } else {
                runCatching {
                    chromiumDir
                        .resolve("executable.name")
                        .toFile()
                        .readText()
                        .trim()
                }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
        return executableName
            ?.let {
                chromiumDir
                    .resolve("$it.app/Contents/Frameworks/Chromium Framework.framework/Versions")
                    .toFile()
            }?.takeIf { it.isDirectory }
    }

    /**
     * Check if a Chromium directory is valid (contains executable.name file).
     * The executable.name file is critical for JxBrowser to locate the branded binary.
     */
    private fun isValidChromiumDir(dir: java.nio.file.Path): Boolean {
        if (!dir.toFile().exists()) return false
        val executableNameFile = dir.resolve("executable.name").toFile()
        return executableNameFile.exists()
    }

    /**
     * Get the path to bundled BOSS-branded Chromium based on platform.
     * Returns null if no bundled Chromium is found.
     */
    private fun getBundledChromiumPath(): java.nio.file.Path? {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") -> getMacOSBundledChromiumPath()
            osName.contains("win") -> getWindowsBundledChromiumPath()
            else -> getLinuxBundledChromiumPath()
        }
    }

    /**
     * macOS: Look for bundled Chromium in BOSS.app/Contents/Resources/chromium/
     */
    private fun getMacOSBundledChromiumPath(): java.nio.file.Path? {
        // Java home is typically: BOSS.app/Contents/runtime/Contents/Home
        // So app bundle root is 4 levels up
        val javaHome = System.getProperty("java.home") ?: return null
        val javaHomePath = Paths.get(javaHome)

        // Navigate from runtime to app bundle: runtime/Contents/Home -> app/Contents/Resources/chromium
        val appContents = javaHomePath.parent?.parent?.parent ?: return null
        val chromiumPath = appContents.resolve("Resources").resolve("chromium")

        return if (chromiumPath.toFile().exists()) chromiumPath else null
    }

    /**
     * Windows: Look for bundled Chromium in app installation directory/chromium/
     */
    private fun getWindowsBundledChromiumPath(): java.nio.file.Path? {
        // Try relative to app installation directory
        val userDir = System.getProperty("user.dir")
        val chromiumPath = Paths.get(userDir, "chromium")

        if (chromiumPath.toFile().exists()) return chromiumPath

        // Also try relative to Java home for installed apps
        val javaHome = System.getProperty("java.home") ?: return null
        val javaHomePath = Paths.get(javaHome)
        val appChromiumPath = javaHomePath.parent?.resolve("chromium")

        return if (appChromiumPath?.toFile()?.exists() == true) appChromiumPath else null
    }

    /**
     * Linux: Look for bundled Chromium in standard installation directories
     */
    private fun getLinuxBundledChromiumPath(): java.nio.file.Path? {
        // Check common Linux installation paths
        val paths =
            listOf(
                "/opt/boss/lib/chromium", // Bundled in lib directory (new packaging)
                "/opt/boss/chromium",
                "/usr/share/boss/chromium",
                "/usr/local/share/boss/chromium",
            )

        for (pathStr in paths) {
            val path = Paths.get(pathStr)
            if (path.toFile().exists()) return path
        }

        // Also try relative to user.dir (for portable installations)
        val userDir = System.getProperty("user.dir")
        val chromiumPath = Paths.get(userDir, "chromium")
        return if (chromiumPath.toFile().exists()) chromiumPath else null
    }

    /**
     * Clean up stale lock files from a previous BOSS session that didn't close properly.
     * On Linux, Chromium creates SingletonLock as a symlink to "spark-<hostname>-<pid>".
     * If the PID is no longer running, the lock is stale and can be safely removed.
     */
    private fun cleanupStaleLockFiles(profileDir: java.nio.file.Path): Boolean {
        val lockFile = profileDir.resolve("SingletonLock").toFile()
        val socketFile = profileDir.resolve("SingletonSocket").toFile()
        val cookieFile = profileDir.resolve("SingletonCookie").toFile()

        if (!lockFile.exists()) {
            return false // No lock to clean
        }

        // On Linux, SingletonLock is a symlink to "spark-<hostname>-<pid>"
        // Check if the PID is still running
        try {
            val isSymlink = Files.isSymbolicLink(lockFile.toPath())

            if (isSymlink) {
                val target = Files.readSymbolicLink(lockFile.toPath()).toString()

                // Parse PID from "spark-hostname-12345" or similar format
                val pid = target.substringAfterLast("-").toLongOrNull()

                if (pid != null) {
                    // Check if process is still running
                    val processHandle = ProcessHandle.of(pid)
                    val isRunning = processHandle.isPresent

                    if (isRunning) {
                        // Additional check: verify it's actually a BOSS/JxBrowser process
                        // not just a reused PID from another application
                        val processInfo = processHandle.orElse(null)
                        val command = processInfo?.info()?.command()?.orElse(null)

                        // If it's not a Java process, it's likely a reused PID
                        val isJavaProcess = command?.contains("java", ignoreCase = true) == true
                        if (!isJavaProcess) {
                            deleteLockFiles(lockFile, socketFile, cookieFile)
                            return true
                        }
                    } else {
                        deleteLockFiles(lockFile, socketFile, cookieFile)
                        return true
                    }
                } else {
                    // Couldn't parse PID - try to clean up anyway if lock file is old
                    val lastModified = lockFile.lastModified()
                    val ageMinutes = (System.currentTimeMillis() - lastModified) / (1000 * 60)

                    // If lock is older than 5 minutes, assume it's stale
                    if (ageMinutes > 5) {
                        deleteLockFiles(lockFile, socketFile, cookieFile)
                        return true
                    }
                }
            } else {
                // Not a symlink (Windows or other OS) - check file age
                val lastModified = lockFile.lastModified()
                val ageMinutes = (System.currentTimeMillis() - lastModified) / (1000 * 60)

                // On non-Linux, if lock is older than 5 minutes and we're starting fresh, clean it
                if (ageMinutes > 5) {
                    deleteLockFiles(lockFile, socketFile, cookieFile)
                    return true
                }
            }
        } catch (e: Exception) {
            logger.warn(
                LogCategory.BROWSER,
                "Could not inspect profile lock files - attempting cleanup anyway",
                error = e,
            )

            // If we can't check, try to clean up anyway
            try {
                deleteLockFiles(lockFile, socketFile, cookieFile)
                return true
            } catch (e2: Exception) {
                // Cleanup failed too - caller falls back to a temporary profile
                logger.warn(LogCategory.BROWSER, "Stale lock-file cleanup failed - profile stays locked", error = e2)
            }
        }

        return false
    }

    private fun deleteLockFiles(
        lockFile: java.io.File,
        socketFile: java.io.File,
        cookieFile: java.io.File,
    ) {
        // Use Files.deleteIfExists which handles symlinks properly on macOS
        // File.delete() can silently fail on dangling symlinks
        listOf(lockFile, socketFile, cookieFile).forEach { file ->
            try {
                Files.deleteIfExists(file.toPath())
            } catch (e: Exception) {
                // Fallback to File.delete()
                logger.debug(
                    LogCategory.BROWSER,
                    "Files.deleteIfExists failed for lock file - falling back to File.delete",
                    mapOf("file" to file.name, "error" to e.toString()),
                )
                file.delete()
            }
        }
    }

    /**
     * Clean up old temporary profiles to prevent disk space accumulation.
     * Deletes browser-profile-* directories older than 24 hours.
     * Called during engine initialization (may run alongside active engine).
     */
    private fun cleanupOldTemporaryProfiles() {
        try {
            val bossDir = BossDirectories.rootDir
            val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)

            bossDir
                .listFiles()
                ?.filter {
                    it.isDirectory &&
                        it.name.startsWith("browser-profile-") &&
                        it.name != "browser-profile" &&
                        it.lastModified() < oneDayAgo
                }?.forEach { dir ->
                    dir.deleteRecursively()
                }
        } catch (e: Exception) {
            // Housekeeping only - old temp profiles are retried next startup
            logger.debug(LogCategory.BROWSER, "Old temporary profile cleanup failed", mapOf("error" to e.toString()))
        }
    }

    /**
     * Clean up ALL temporary profiles on startup.
     * At startup time, no temp profiles should be in use — they are always
     * leftovers from crashed/killed sessions. Safe to delete unconditionally.
     */
    private fun cleanupAllTemporaryProfiles() {
        try {
            val bossDir = BossDirectories.rootDir
            var cleanedCount = 0

            bossDir
                .listFiles()
                ?.filter {
                    it.isDirectory &&
                        it.name.startsWith("browser-profile-") &&
                        it.name != "browser-profile"
                }?.forEach { dir ->
                    if (dir.deleteRecursively()) {
                        cleanedCount++
                    }
                }

            if (cleanedCount > 0) {
                logger.info(
                    LogCategory.BROWSER,
                    "Cleaned up temporary browser profiles",
                    mapOf(
                        "count" to cleanedCount,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Error cleaning temporary profiles",
                mapOf(
                    "error" to (e.message ?: "unknown"),
                ),
            )
        }
    }

    private fun createEngineWithProfile(chromiumDir: java.nio.file.Path): Engine {
        val selectedProfile = BrowserSettings.currentProfile
        val profileDirPath = BossDirectories.resolve(selectedProfile).toPath()
        profileDirPath.toFile().mkdirs()

        return try {
            createEngineInstance(chromiumDir, profileDirPath)
        } catch (e: UserDataDirectoryAlreadyInUseException) {
            logger.warn(
                LogCategory.BROWSER,
                "Profile directory already in use - trying lock cleanup, then temp profile",
                error = e,
            )
            // Try to clean up stale lock files first
            if (cleanupStaleLockFiles(profileDirPath)) {
                try {
                    return createEngineInstance(chromiumDir, profileDirPath)
                } catch (e2: Exception) {
                    // Retry after cleanup still failed - fall through to temporary profile
                    logger.warn(
                        LogCategory.BROWSER,
                        "Engine creation still failing after lock cleanup - using temporary profile",
                        error = e2,
                    )
                }
            }

            // Profile is genuinely in use by another process, use temporary
            val tempProfile = "browser-profile-${System.currentTimeMillis()}"
            val tempProfilePath = BossDirectories.resolve(tempProfile).toPath()
            tempProfilePath.toFile().mkdirs()

            try {
                createEngineInstance(chromiumDir, tempProfilePath)
            } catch (e2: Exception) {
                throw e2
            }
        } catch (e: Exception) {
            initializationError = e
            throw e
        }
    }

    /**
     * Chromium performance configuration, scoped per platform.
     *
     * The bundled BOSS Chromium is 150.x — modern enough that the classic "enable"
     * switches (GPU rasterization, ANGLE Metal/D3D11, QUIC, canvas OOP raster) are
     * on by default, so the previous flag set was audited out:
     *  - --enable-gpu-rasterization / --enable-zero-copy / --ignore-gpu-blocklist:
     *    default-on in 150; their only residual effect is forcing GPU paths on
     *    driver-blocklisted machines, where they cause crashes/artifacts.
     *  - --disable-dev-shm-usage: container-only switch. On desktop Linux it moves
     *    Chromium's shared memory to disk-backed files — and shared memory is the
     *    OFF_SCREEN frame-transport path, so it directly slowed rendering.
     *  - --no-sandbox: no rendering benefit; dropped to restore Chromium process
     *    isolation (BOSS_CHROMIUM_DISABLE_SANDBOX=true restores the old behavior).
     *
     * In OFF_SCREEN mode the rendering ceiling is the Chromium→Java pixel copy
     * (per TeamDev), so what remains here targets real stalls (Windows occlusion
     * tracking), video decode (Linux VA-API), and repeat-load speed (disk cache).
     * Skia Graphite is opt-in only — it blanks OSR output on this JxBrowser (see
     * the mac branch below). Extra switches can be injected without a rebuild via
     * BOSS_CHROMIUM_EXTRA_SWITCHES (whitespace-separated, like a Chromium
     * command line) or the equivalent field in Settings > Browser Engine.
     *
     * CAUTION for extra-switch users: Chromium's --enable-features /
     * --disable-features are NOT additive — the last occurrence on the command line
     * wins. Passing your own --enable-features=… replaces the platform set above
     * (e.g. SkiaGraphite, VA-API); include those features in your value if you want
     * to keep them.
     *
     * Everything resolved here is restart-scoped: EngineOptions are fixed when the
     * engine is built, so a Settings change takes effect on the next launch. The
     * resolved switch list is recorded in [lastAppliedSwitches] for the Settings UI to
     * display, so what it shows as active is what was actually passed rather than a
     * recomputation that could disagree with it.
     */
    private fun applyPerformanceSwitches(
        builder: EngineOptions.Builder,
        inContainer: Boolean,
    ) {
        // bootSettings, NOT currentSettings. Every other flag reaches the engine through the
        // system properties published from bootSettings at startup, and the engine is built
        // lazily on first browser use — so reading live settings let one boot mix values from two
        // points in time, and let a setting take effect while the Apply section still said a
        // restart was pending. Boot-scoped everywhere is the model the whole screen describes.
        val flags = ai.rever.boss.config.ChromiumFlagsSettingsManager.bootSettings

        // Bigger fixed on-disk HTTP cache for faster repeat page loads. Chromium's
        // auto-sizing historically caps around ~320 MB; 512 MB comfortably exceeds
        // it without meaningfully eating the disk. Tune via this API, not a
        // --disk-cache-size extra switch — precedence between the two is
        // unspecified when both are set.
        val diskCacheMb = diskCacheMb(flags.diskCacheMb)
        builder.diskCacheSize(diskCacheMb.toLong() * 1024 * 1024)
        _lastDiskCacheMb.value = diskCacheMb

        // capabilityValue, not ConfigLoader: arbitrary switches are arbitrary capability.
        val parsed = partitionExtraSwitches(capabilityValue(ChromiumFlagKeys.EXTRA_SWITCHES))
        val extras = parsed.accepted
        if (extras.isNotEmpty()) {
            // Audit trail: extras are unrestricted and can re-weaken hardening,
            // so record exactly what this session runs with.
            logger.info(
                LogCategory.BROWSER,
                "Injecting extra Chromium switches from BOSS_CHROMIUM_EXTRA_SWITCHES",
                mapOf(
                    "switches" to extras.joinToString(" "),
                ),
            )
        }
        if (parsed.malformed.isNotEmpty()) {
            // Surface fat-fingered entries (bare values, single-dash flags) instead
            // of silently dropping them — misconfiguration should be debuggable.
            logger.warn(
                LogCategory.BROWSER,
                "Ignoring non-switch tokens in BOSS_CHROMIUM_EXTRA_SWITCHES (switches must start with --)",
                mapOf(
                    "dropped" to parsed.malformed.joinToString(" "),
                ),
            )
        }
        if (parsed.gated.isNotEmpty()) {
            // A DIFFERENT reason, and it needs its own wording: these are well-formed switches
            // refused because they have a confirmed Settings row of their own. Reporting them as
            // malformed sent the user to fix a "--" that was never missing.
            logger.warn(
                LogCategory.BROWSER,
                "Ignoring switches that have their own confirmed setting - use Settings > Browser Engine instead",
                mapOf(
                    "dropped" to parsed.gated.joinToString(" "),
                ),
            )
        }

        // RAM cap for many-tab sessions. Bounding the renderer process count trades cross-tab
        // isolation and stability for memory, which is not a trade to make for everyone — so it
        // stays OFF on FULL and is supplied by the reduced tiers, which exist precisely to make
        // that trade. The operator's setting still wins either way (see resolvedRenderCapSwitch).
        // Values <= 0 are ignored rather than passed through, since --renderer-process-limit=0 is
        // not a meaningful cap.
        //
        // Resolved HERE rather than inside performanceSwitchesFor so that function stays pure and
        // its tests stay independent of the developer's environment. Inserted BEFORE the operator's
        // extras so the documented "extras are appended last, so operator flags win ties" holds.
        val rendererCap =
            resolvedRenderCapSwitch(
                ai.rever.boss.config.ConfigLoader
                    .getConfig(ChromiumFlagKeys.RENDERER_PROCESS_LIMIT),
                ai.rever.boss.config.ResourceModeConfig.mode,
            )

        val platformSwitches =
            performanceSwitchesFor(
                os = System.getProperty("os.name").lowercase(),
                arch = System.getProperty("os.arch").lowercase(),
                inContainer = inContainer,
                extraSwitches = listOfNotNull(rendererCap) + extras,
                // Graphite comes through ConfigLoader rather than off `flags` directly, because it
                // is a published key: an operator's env var must keep outranking the setting.
                // Graphite is resolved separately from the other toggles because its default
                // depends on the rendering mode, not just on the setting. See resolveSkiaGraphite.
                toggles =
                    SwitchToggles.from(flags).copy(
                        skiaGraphite =
                            resolveSkiaGraphite(
                                ai.rever.boss.config.ConfigLoader
                                    .getConfig(ChromiumFlagKeys.SKIA_GRAPHITE),
                                JxBrowserConfig.renderingMode,
                            ),
                    ),
            )
        platformSwitches.forEach { builder.addSwitch(it) }
        _lastAppliedSwitches.value = platformSwitches
    }

    /**
     * The switch list the live engine was actually built with, and the disk cache it was
     * given, for Settings > Browser Engine to display as "active this session".
     *
     * Recorded rather than recomputed on demand. A recomputation would read the CURRENT
     * settings, so the moment a user changed a row it would start reporting a command
     * line no running process ever had — under the heading "active". Empty/null until
     * the engine is first built, which is also true: nothing has been applied yet.
     */
    // StateFlow, not a plain @Volatile var: the Settings panel reads this during composition, and
    // a bare var never invalidates — the "active this session" list stayed on its empty-state note
    // until the user navigated away and back, which made that note ("open a browser tab to
    // populate this") false in the one case it describes.
    private val _lastAppliedSwitches = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    internal val lastAppliedSwitchesFlow: kotlinx.coroutines.flow.StateFlow<List<String>> = _lastAppliedSwitches

    private val _lastDiskCacheMb = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    internal val lastDiskCacheMbFlow: kotlinx.coroutines.flow.StateFlow<Int?> = _lastDiskCacheMb

    /**
     * Disk cache size in MB, clamped so a hand-edited settings file cannot hand Chromium
     * something unusable.
     *
     * The floor is 1 rather than 0: `diskCacheSize(0)` is Chromium's "pick a size
     * yourself" sentinel, so a user who typed 0 meaning "no cache" would silently get
     * the auto-sized several-hundred-MB cache instead — the opposite of the request. The
     * ceiling is 8 GB, far past any deliberate choice, so it only ever catches a slipped
     * digit that would otherwise fill a disk.
     */
    internal fun diskCacheMb(requested: Int?): Int = requested?.coerceIn(1, 8192) ?: DEFAULT_DISK_CACHE_MB

    internal const val DEFAULT_DISK_CACHE_MB = 512

    /**
     * Container detection for the Linux-only container switches. /.dockerenv only
     * covers Docker; /proc/1/cgroup catches most other runtimes (Kubernetes,
     * containerd, LXC, Podman) on cgroup v1 — cgroup v2 may show a bare "0::/",
     * which is undetectable, so BOSS_IN_CONTAINER=true remains the explicit
     * override (and BOSS_CHROMIUM_DISABLE_SANDBOX=true the sandbox-specific one).
     */
    internal fun runningInContainer(): Boolean {
        if (System.getenv("BOSS_IN_CONTAINER") == "true") return true
        // File-based markers are Linux-only concepts — skip the I/O elsewhere.
        if (!System.getProperty("os.name").lowercase().contains("linux")) return false
        if (java.io.File("/.dockerenv").exists()) return true
        return try {
            val cgroup = java.io.File("/proc/1/cgroup")
            cgroup.exists() && cgroupIndicatesContainer(cgroup.readText())
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "Could not read /proc/1/cgroup - assuming not in container",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /** Pure predicate over /proc/1/cgroup content, split out so it's unit-testable. */
    internal fun cgroupIndicatesContainer(cgroupText: String): Boolean =
        listOf("docker", "kubepods", "containerd", "lxc", "podman").any { it in cgroupText }

    /**
     * The per-platform switch decision as a pure function so the flag audit is
     * unit-testable without an [EngineOptions.Builder].
     *
     * Genuinely pure: every input is a parameter. Notably the opt-in renderer cap is NOT read
     * here — it is resolved by the caller and appended around this result (see
     * [applyPerformanceSwitches]). Reading config inside would hide an input from a function whose
     * whole point is an auditable decision, and would make these tests depend on the developer's
     * own environment: anyone with BOSS_RENDERER_PROCESS_LIMIT set would get a different switch
     * set than CI. The four toggles added when these became Settings rows follow the same rule:
     * they arrive as booleans the caller has already resolved, so "which switches does this
     * platform get" stays answerable by reading one function.
     *
     * The per-switch opt-ins arrive grouped in a [SwitchToggles] rather than as five more
     * positional booleans: adjacent same-typed parameters are a call site where a
     * transposition compiles and silently emits the wrong switches. [inContainer] stays a
     * separate parameter on purpose — it is a fact about the machine, not a choice anyone
     * made, and the two should not be able to be confused for each other.
     */
    internal fun performanceSwitchesFor(
        os: String,
        arch: String,
        inContainer: Boolean,
        extraSwitches: List<String> = emptyList(),
        toggles: SwitchToggles = SwitchToggles(),
    ): List<String> {
        val switches = mutableListOf<String>()

        // Background network chatter an embedded browser has no use for, on every platform.
        // --no-pings drops hyperlink-auditing pings; --disable-domain-reliability stops
        // Chrome's Domain Reliability error-reporting uploads to Google. Neither is load-bearing
        // for anything BOSS does, so this is pure reduction. Ported from BossConsoleLite, which
        // deliberately stopped short of --disable-background-networking and the component
        // updater pending proof they don't break the update/DRM paths — that caution is kept.
        //
        // Both are switchable because "pure reduction" is a claim about BOSS's needs, not about
        // every page a user will open: a site whose hyperlink auditing is load-bearing is a
        // support case that should be answerable without a rebuild.
        if (toggles.noPings) switches += "--no-pings"
        if (toggles.domainReliability) switches += "--disable-domain-reliability"

        when {
            os.contains("win") -> {
                // Chromium's native-window occlusion tracker can conclude the
                // embedded (hidden) native window is fully covered and stop
                // producing frames — a known stall for embedded engines whose
                // visibility is driven by the app's own surface, not the native
                // window. CEF/JCEF embedders disable it for the same reason.
                if (toggles.winOcclusion) switches += "--disable-features=CalculateNativeWinOcclusion"
            }

            os.contains("mac") -> {
                // Skia Graphite (Metal-native raster backend), Apple Silicon only.
                // ON by default under HARDWARE_ACCELERATED and OFF under OFF_SCREEN —
                // see resolveSkiaGraphite for why the default is mode-dependent, and
                // for the live evidence that it blanks the OSR path specifically.
                // Override either way with BOSS_ENABLE_SKIA_GRAPHITE or the Settings
                // row; turning it OFF is now the override worth documenting.
                if (arch.contains("aarch64") && toggles.skiaGraphite) {
                    switches += "--enable-features=SkiaGraphite"
                }
            }

            os.contains("linux") -> {
                // Linux hardware video decode is still gated in upstream defaults
                // (feature names differ across Chromium generations; unknown ones
                // are ignored, so list both eras). Switchable because VA-API depends on
                // the driver actually being there: on a machine where it is broken,
                // forcing it on is worse than leaving decode in software.
                if (toggles.vaapi) {
                    switches += "--enable-features=VaapiVideoDecoder,VaapiVideoDecodeLinuxGL,VaapiVideoEncoder"
                }
                // Container-only: tiny /dev/shm would otherwise crash renderers.
                // Never on desktop Linux — it would push the OSR frame transport
                // to disk.
                if (inContainer) {
                    switches += "--disable-dev-shm-usage"
                    // NOTE: the container sandbox opt-out is NOT a switch here —
                    // JxBrowser manages the sandbox via EngineOptions.disableSandbox()
                    // (a raw --no-sandbox may be ignored); see createEngineInstance.
                }
            }
            // Unknown platform strings get no platform-specific switches.
        }
        // Operator escape hatch, appended last (see the --enable-features caveat
        // in the KDoc above).
        switches += extraSwitches
        return switches
    }

    /**
     * The switches that were unconditional until they became Settings rows.
     *
     * **Every default is true, and that is the whole point of this type.** In
     * [ai.rever.boss.config.ChromiumFlagsSettings] these are nullable, where null means
     * "no opinion" — and resolving a null to `false` instead of `true` would silently
     * strip working flags from every user who has never opened the Settings screen.
     * Putting the resolution in [from], once, means no call site can get it wrong, and
     * putting the defaults here means omitting the argument entirely is also safe.
     */
    internal data class SwitchToggles(
        val noPings: Boolean = true,
        val domainReliability: Boolean = true,
        val winOcclusion: Boolean = true,
        val vaapi: Boolean = true,
        // The one toggle whose default is not a constant: it depends on the rendering mode, so
        // it is resolved by resolveSkiaGraphite and passed in rather than defaulted here. The
        // `false` is only what an unspecified copy() gets, which is the safe direction — the
        // shipped behaviour before this became mode-aware.
        val skiaGraphite: Boolean = false,
    ) {
        companion object {
            /** Resolve the nullable settings form, treating "no opinion" as each switch's shipped default. */
            fun from(flags: ai.rever.boss.config.ChromiumFlagsSettings) =
                SwitchToggles(
                    noPings = flags.noPings ?: true,
                    domainReliability = flags.disableDomainReliability ?: true,
                    winOcclusion = flags.disableWinOcclusion ?: true,
                    vaapi = flags.enableVaapi ?: true,
                    // NOT resolved here: its default needs the rendering mode, which this
                    // settings-only view does not have. Callers overwrite it via
                    // resolveSkiaGraphite; left at the safe `false` so a caller that forgets
                    // under-enables rather than shipping a blank browser.
                )
        }
    }

    /**
     * Whether to emit `--enable-features=SkiaGraphite`, given an explicit setting or env value
     * and the rendering mode.
     *
     * **The default is ON under HARDWARE_ACCELERATED and OFF under OFF_SCREEN, and that split is
     * the whole point of this function.** Graphite is Chromium's Metal-native raster backend and
     * is default-on in stable Chrome on Apple Silicon, so it is the better backend where it works.
     * The one place it is known NOT to work here is off-screen rendering: verified live on
     * JxBrowser 9.3.0 / Chromium 150 / Apple Silicon (2026-07-13), pages loaded normally
     * (navigation, titles, favicons all fine) but frames never reached the Compose surface —
     * a blank content area.
     *
     * That failure was specific to the OSR frame-export path, which HARDWARE_ACCELERATED does not
     * use at all: there, Chromium composites into its own native window and nothing has to be
     * exported to Compose. So the recorded breakage does not apply to the mode macOS now runs in.
     *
     * Keeping OFF_SCREEN on the old default matters more than it looks. OFF_SCREEN is the
     * documented escape hatch for anyone who cannot live with HARDWARE — the lost two-finger
     * swipe-back gesture, say. Defaulting Graphite on unconditionally would hand exactly those
     * users a BLANK BROWSER, making the escape hatch worse than the thing they were escaping.
     *
     * An explicit value always wins, either way, so a machine where Graphite misbehaves under
     * HARDWARE can turn it off without a rebuild — and unlike the old opt-in, turning it *off*
     * is now the override that needs saying.
     */
    internal fun resolveSkiaGraphite(
        raw: String?,
        mode: com.teamdev.jxbrowser.engine.RenderingMode,
    ): Boolean =
        when {
            isTruthyFlag(raw) -> true
            isFalsyFlag(raw) -> false
            else -> mode == com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED
        }

    /** Pure part of the renderer-process cap, split out so the guard is unit-testable. */
    internal fun renderCapSwitch(raw: String?): String? =
        raw
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { "--renderer-process-limit=$it" }

    /**
     * The renderer-process cap actually applied, combining the operator's setting with the
     * process's [ai.rever.boss.config.BossResourceMode].
     *
     * The setting still wins whenever it parses, in **both** directions: a number raises or
     * lowers the tier's cap, and an explicit `0` means "no cap" and must survive a reduced
     * tier. That is the difference between this and [renderCapSwitch] alone - the latter maps
     * both `0` and "unset" to null, which would let the tier default silently re-cap an
     * operator who had deliberately turned the cap off.
     *
     * Only an absent, blank or unparseable value falls through to the tier.
     */
    internal fun resolvedRenderCapSwitch(
        raw: String?,
        mode: ai.rever.boss.config.BossResourceMode,
    ): String? {
        val explicit = raw?.trim()?.toIntOrNull()
        if (explicit != null) return renderCapSwitch(explicit.toString())
        return mode.rendererProcessLimit?.let { renderCapSwitch(it.toString()) }
    }

    /**
     * Opt-in DevTools endpoint on the embedded engine, for measuring the fluck
     * browser with the same CDP harness that drives Chrome/Edge — otherwise the
     * one browser we most want to profile is the one that can only be read off a
     * screenshot (see benchmarks/speedometer/win/SpeedometerCdp.java).
     *
     * OFF unless a valid port comes from BOSS_BROWSER_REMOTE_DEBUGGING_PORT or the
     * Settings row, because an open DevTools port is full control of the browser
     * profile: any local process can read cookies and session tokens and drive
     * navigation through it, with no prompt. Chromium binds the endpoint to loopback
     * only, which bounds the exposure to this machine but not to this app.
     *
     * [parseRemoteDebuggingPort] rejects anything outside the unprivileged range
     * so a typo cannot silently mean "port 0" — which Chromium reads as
     * "pick any free port", i.e. a debugging endpoint nobody knows is open.
     *
     * **Two sources, and deliberately NOT ConfigLoader**, unlike every other tunable
     * here. ConfigLoader would add local.properties and the embedded build config as
     * sources, and a line in someone's local.properties enables this for every future
     * run of that checkout with nothing in the app to reveal it. The two sources it
     * does accept are both revocable and visible:
     *
     *  - the environment variable, scoped to one session by construction;
     *  - the Settings row, which the UI writes only behind a confirmation that spells
     *    out the exposure, shows as enabled whenever it is, and can turn back off.
     *
     * The env var is checked first so a one-session override still wins over a
     * persisted setting, matching the precedence everywhere else. This applies to the
     * SHARED engine, so without BOSS_DEV_MODE it exposes the operator's real profile
     * and cookies — hence the warning below on every boot it is on.
     */
    private fun applyRemoteDebuggingPort(builder: EngineOptions.Builder) {
        // Blank is treated as unset. `FOO= boss` exports an empty string, which is non-null, so a
        // bare getenv let an empty variable win the elvis below and silently suppress a port the
        // user had configured — reported as "not a port in 1024..65535" for a value they never set.
        val fromEnv = System.getenv(ChromiumFlagKeys.REMOTE_DEBUGGING_PORT)?.takeIf { it.isNotBlank() }
        val fromSettings =
            ai.rever.boss.config.ChromiumFlagsSettingsManager.bootSettings
                .remoteDebuggingPort
                ?.toString()
        val source = if (fromEnv != null) "environment" else "settings"
        val raw = fromEnv ?: fromSettings ?: return
        val port = parseRemoteDebuggingPort(raw)
        if (port == null) {
            logger.warn(
                LogCategory.BROWSER,
                "Ignoring remote debugging port - not a port in 1024..65535",
                mapOf("value" to raw, "source" to source),
            )
            return
        }
        builder.remoteDebuggingPort(port)
        logger.warn(
            LogCategory.BROWSER,
            "DevTools remote debugging ENABLED on this engine - any local process can drive the browser " +
                "and read its cookies. Turn it off in Settings > Browser Engine, or unset " +
                "BOSS_BROWSER_REMOTE_DEBUGGING_PORT, when you are done.",
            mapOf("port" to port, "source" to source),
        )
    }

    /** Pure part of [applyRemoteDebuggingPort], split out so the guard is unit-testable. */
    internal fun parseRemoteDebuggingPort(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 1024..65535 }

    private fun createEngineInstance(
        chromiumDir: java.nio.file.Path,
        profileDirPath: java.nio.file.Path,
    ): Engine {
        // Evaluated once per boot: feeds both the container-only switches and the
        // sandbox decision below, so the two can never disagree.
        val inContainer = runningInContainer()
        val optionsBuilder =
            EngineOptions
                .newBuilder(JxBrowserConfig.renderingMode)
                .licenseKey(JxBrowserConfig.licenseKey)
                .chromiumDir(chromiumDir)
                .userDataDir(profileDirPath)
                // Enable all proprietary codecs for full media support
                .enableProprietaryFeature(ProprietaryFeature.H_264)
                .enableProprietaryFeature(ProprietaryFeature.AAC)
                .enableProprietaryFeature(ProprietaryFeature.HEVC)
                .apply { applyPerformanceSwitches(this, inContainer) }
                // Chromium sandbox stays ON (the JxBrowser default): --no-sandbox had
                // no performance benefit and stripped process isolation from an engine
                // that renders arbitrary web content. Disabled ONLY via the supported
                // JxBrowser API (a raw --no-sandbox switch is not guaranteed to be
                // honored), for: an explicit operator opt-out, or containers, where
                // the sandbox usually can't start (no user namespaces) and the
                // container boundary provides the isolation instead.
                .apply {
                    if (capabilityIsTrue(ChromiumFlagKeys.DISABLE_SANDBOX) || inContainer) disableSandbox()
                }.apply { applyRemoteDebuggingPort(this) }

        // Add user agent if configured
        BrowserSettings.userAgent?.let { ua ->
            val userAgentMapping =
                mapOf(
                    "Chrome" to
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Firefox" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:121.0) Gecko/20100101 Firefox/121.0",
                    "Safari" to
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
                    "Edge" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
                )

            val userAgentString =
                when (ua) {
                    "Default" -> null
                    "Chrome", "Firefox", "Safari", "Edge" -> userAgentMapping[ua]
                    "Custom" -> BrowserSettings.customUserAgent
                    else -> ua
                }

            userAgentString?.let {
                optionsBuilder.userAgent(it)
            }
        }

        val newEngine = Engine.newInstance(optionsBuilder.build())

        // A successful boot invalidates any earlier failure — clear it so the
        // recorded state is unambiguous (previously a stale error from a failed
        // attempt survived a later successful boot).
        initializationError = null
        attemptCount = 0

        // Activate Widevine DRM for protected content (Netflix, Disney+, etc.).
        // Deliberately NOT joined: activation can hit the network (CDM download on
        // first run), and blocking here stalls engine creation — which stalls the
        // first browser tab. DRM sites opened in the first moments simply retry.
        try {
            newEngine.widevine().activate().whenComplete { status, error ->
                if (error != null) {
                    logger.debug(LogCategory.BROWSER, "Widevine activation failed", mapOf("error" to (error.message ?: "unknown")))
                } else {
                    logger.debug(LogCategory.BROWSER, "Widevine activation completed", mapOf("status" to status.toString()))
                }
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Widevine activation call failed", mapOf("error" to (e.message ?: "unknown")))
        }

        // Set up permission handlers for the engine
        setupPermissionHandlers(newEngine)

        _engine = newEngine

        // Match Chromium's theme to the active BOSS host theme on (re)creation.
        try {
            newEngine.setTheme(if (preferredColorSchemeDark) Theme.DARK else Theme.LIGHT)
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Failed to set initial engine theme", mapOf("error" to (e.message ?: "unknown")))
        }

        return newEngine
    }

    private fun setupPermissionHandlers(engine: Engine) {
        // Set up permission handler for all browsers created from this engine
        val profile = engine.profiles().defaultProfile()
        val permissions = profile.permissions()

        permissions.set(
            RequestPermissionCallback::class.java,
            object : RequestPermissionCallback {
                override fun on(
                    params: RequestPermissionCallback.Params,
                    action: RequestPermissionCallback.Action,
                ) {
                    val permissionType = params.permissionType()

                    logger.debug(
                        LogCategory.BROWSER,
                        "Permission requested",
                        mapOf(
                            "type" to permissionType.name,
                        ),
                    )

                    // Auto-grant camera and microphone permissions for video conferencing
                    when (permissionType) {
                        PermissionType.VIDEO_CAPTURE -> {
                            logger.info(LogCategory.BROWSER, "Granting VIDEO_CAPTURE permission")
                            action.grant()
                        }

                        PermissionType.AUDIO_CAPTURE -> {
                            logger.info(LogCategory.BROWSER, "Granting AUDIO_CAPTURE permission")
                            action.grant()
                        }

                        PermissionType.NOTIFICATIONS -> {
                            action.grant()
                        }

                        else -> {
                            // For other permissions, auto-grant as well
                            logger.debug(LogCategory.BROWSER, "Granting permission", mapOf("type" to permissionType.name))
                            action.grant()
                        }
                    }
                }
            },
        )
    }

    /**
     * Sets up screen capture session handler for a browser.
     * This intercepts screen share requests and shows a custom picker dialog for tabs.
     * User can choose to use native picker for windows/screens.
     */
    fun setupCaptureSessionHandler(browser: com.teamdev.jxbrowser.browser.Browser) {
        browser.set(
            StartCaptureSessionCallback::class.java,
            StartCaptureSessionCallback { params, tell ->
                // On macOS, explain BEFORE triggering the OS prompt: show an in-app
                // rationale dialog and only request permission if the user agrees. This
                // callback runs off the Compose UI thread, so blocking on it is safe.
                if (!MacOSScreenCapture.hasPermission()) {
                    if (!ScreenCaptureNotifier.awaitPermissionRationale()) {
                        tell.cancel()
                        return@StartCaptureSessionCallback
                    }
                    val granted = MacOSScreenCapture.requestPermission()
                    if (!granted) {
                        tell.cancel()
                        return@StartCaptureSessionCallback
                    }
                }

                val sources = params.sources()

                // Log available sources for debugging

                // Generate unique request ID
                val requestId =
                    java.util.UUID
                        .randomUUID()
                        .toString()

                // Emit to UI for user selection
                ScreenCaptureNotifier.requestCapture(
                    requestId = requestId,
                    sources = sources,
                    tell = tell,
                )

                // Set 60-second timeout - if user doesn't respond, cancel
                CoroutineScope(Dispatchers.Default).launch {
                    delay(60_000)
                    if (ScreenCaptureNotifier.hasPendingRequest(requestId)) {
                        ScreenCaptureNotifier.cancel(requestId)
                    }
                }
            },
        )
    }

    /**
     * Sets up fullscreen handler for a browser.
     * When web content requests fullscreen (e.g., YouTube video),
     * opens a fullscreen window with the browser content.
     *
     * @param browser The browser instance to configure
     * @param tabId The unique ID of the tab containing this browser
     * @param ownerWindowId The Boss window that owns the browser tab
     * @param onFullscreenEnter Callback when fullscreen mode is entered
     * @param onFullscreenExit Callback when fullscreen mode is exited
     */
    fun setupFullscreenHandler(
        browser: com.teamdev.jxbrowser.browser.Browser,
        tabId: String,
        ownerWindowId: String,
        onFullscreenEnter: () -> Unit,
        onFullscreenExit: () -> Unit,
    ) {
        // Handle fullscreen enter request
        browser.fullScreen().on(com.teamdev.jxbrowser.fullscreen.event.FullScreenEntered::class.java) {
            logger.info(LogCategory.BROWSER, "Web content requested fullscreen", mapOf("tabId" to tabId))

            // Show fullscreen window
            ai.rever.boss.tabfullscreen.FullscreenBrowserWindow.showFullscreen(
                browser = browser,
                tabId = tabId,
                ownerWindowId = ownerWindowId,
                onEnter = onFullscreenEnter,
                onExit = onFullscreenExit,
            )
        }

        // Handle fullscreen exit using event listener
        browser.fullScreen().on(com.teamdev.jxbrowser.fullscreen.event.FullScreenExited::class.java) {
            logger.info(LogCategory.BROWSER, "Fullscreen exited", mapOf("tabId" to tabId))

            // Close fullscreen window
            ai.rever.boss.tabfullscreen.FullscreenBrowserWindow
                .exitFullscreenAsync(browser, onFullscreenExit)
        }
    }

    internal data class BrowserKeyEventRoute(
        val acceptsInput: Boolean,
        val shortcutWindowId: String?,
    )

    /**
     * Resolves both input acceptance and the destination for application shortcuts.
     * A window-owned browser is accepted only while its owner is focused, and its
     * stable owner always wins over the legacy process-focused fallback.
     */
    internal fun resolveBrowserKeyEventRoute(
        ownerWindowId: String?,
        ownerWindowIsFocused: Boolean,
        fallbackFocusedWindowId: String?,
    ): BrowserKeyEventRoute =
        when {
            ownerWindowId == null -> {
                BrowserKeyEventRoute(
                    acceptsInput = true,
                    shortcutWindowId = fallbackFocusedWindowId,
                )
            }

            ownerWindowIsFocused -> {
                BrowserKeyEventRoute(
                    acceptsInput = true,
                    shortcutWindowId = ownerWindowId,
                )
            }

            else -> {
                BrowserKeyEventRoute(
                    acceptsInput = false,
                    shortcutWindowId = null,
                )
            }
        }

    /**
     * Whether the native key interceptor has to serve zoom chords in this configuration.
     *
     * Two conditions, because two different things can put the chord somewhere else.
     *
     * Rendering mode. Only HARDWARE_ACCELERATED gives Chromium a native child window that
     * consumes the key before the JVM sees it, which is the whole reason this case exists - the
     * same reason Ctrl+R needed one. Under OFF_SCREEN the chord arrives through AWT and Compose
     * instead, so the AWT keymap and the plugin's `onPreviewKeyEvent` serve it and claiming it
     * here as well risks a second zoom step. OFF_SCREEN is not hypothetical: it is reachable per
     * install through BOSS_RENDERING_MODE or Settings > Browser Engine, so gating on the platform
     * alone would leave anyone who flipped that setting exposed.
     *
     * Platform. macOS is exempt even under HARDWARE_ACCELERATED, because the chord reaches AWT
     * there - which is why zoom was reported broken on Windows only - and the AWT keymap
     * (`browser.zoom_in` and friends) plus the plugin's Compose handler already serve it.
     *
     * Both exemptions guard the same failure: two layers acting on one keypress, where the second
     * zoom step is indistinguishable from the user having pressed the key twice.
     *
     * [JxBrowserConfig.renderingMode] is a `lazy` val, so this resolves once per process. That is
     * the right granularity: changing the mode needs the engine rebuilt, so it cannot change under
     * a running browser anyway.
     */
    private val interceptsZoomNatively: Boolean
        get() =
            !SystemUtils.isMacOS &&
                JxBrowserConfig.renderingMode == com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED

    internal enum class BrowserZoomAction { IN, OUT, RESET }

    /**
     * The zoom action a main-modifier chord asks for, or null if it is not a zoom chord.
     *
     * JxBrowser 9.4.0 has no `KEY_CODE_EQUALS` and no `KEY_CODE_MINUS`. The main-row keys are
     * [com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_OEM_PLUS] ('=' / '+') and
     * [com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_OEM_MINUS] ('-' / '_'); the numpad keys are
     * `KEY_CODE_ADD` and `KEY_CODE_SUBTRACT`. Reaching for the intuitive names does not fail to
     * compile against a constant that exists under a different spelling - it silently fails to
     * match at runtime, which is precisely the failure this function was added to remove, so
     * `BrowserZoomKeyMappingTest` pins the mapping.
     *
     * Extracted as a pure function for the same reason [resolveBrowserKeyEventRoute] is: it is
     * testable without a live `Browser`.
     *
     * [shiftDown] is a parameter because Ctrl+Shift+'=' is how many layouts spell Ctrl+'+', and
     * Chrome zooms in on it. Shift is meaningless for zoom out and reset, which decline it.
     */
    internal fun resolveBrowserZoomAction(
        keyCode: com.teamdev.jxbrowser.ui.KeyCode,
        shiftDown: Boolean,
    ): BrowserZoomAction? =
        when (keyCode) {
            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_OEM_PLUS,
            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_ADD,
            -> BrowserZoomAction.IN

            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_OEM_MINUS,
            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_SUBTRACT,
            -> if (shiftDown) null else BrowserZoomAction.OUT

            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_0,
            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_NUMPAD0,
            -> if (shiftDown) null else BrowserZoomAction.RESET

            else -> null
        }

    /**
     * Applies [action] to [browser], preferring [zoomTarget] when one wraps it.
     *
     * Going through the handle rather than calling `browser.zoom()` directly is what keeps the
     * fluck browser's zoom-percent indicator in step: the indicator is driven by
     * `BrowserHandle.addZoomListener`, and only the handle's own zoom methods notify those
     * listeners. The legacy `BrowserFunctions.createBrowser` path has no handle and falls back to
     * the raw API, where there are no listeners to notify anyway.
     *
     * isClosed-guarded like every other browser call in this file: this runs on a JxBrowser
     * callback thread and can race a tab close.
     */
    private fun applyBrowserZoom(
        browser: com.teamdev.jxbrowser.browser.Browser,
        zoomTarget: BrowserHandle?,
        action: BrowserZoomAction,
    ) {
        if (browser.isClosed) return
        when (action) {
            BrowserZoomAction.IN -> if (zoomTarget != null) zoomTarget.zoomIn() else browser.zoom().`in`()
            BrowserZoomAction.OUT -> if (zoomTarget != null) zoomTarget.zoomOut() else browser.zoom().out()
            BrowserZoomAction.RESET -> if (zoomTarget != null) zoomTarget.resetZoom() else browser.zoom().reset()
        }
    }

    /**
     * Dismiss any open Swing popup menu when the user clicks inside the web page.
     *
     * A heavyweight [javax.swing.JPopupMenu] normally closes itself on a click elsewhere: Swing's
     * [javax.swing.MenuSelectionManager] watches the AWT event queue and clears the selection when
     * a press lands outside the menu. A click on the browser never reaches that queue — Chromium
     * owns a native child window and consumes the event itself — so the menu stays on screen.
     * Observed on Windows with the fluck browser's right-click menu: clicking BOSS's own chrome
     * (tab bar, sidebar) dismissed it, clicking the page did not, and right-clicking again merely
     * relocated the same popup.
     *
     * This closes the loop by turning an in-page press into the clearSelectedPath() call Swing
     * would have made itself. Deliberately placed in the host rather than in the browser plugin:
     * the plugin owns the menu but has no mouse-press signal (BrowserHandle exposes only
     * executeJavaScript and a context-menu callback), whereas the host already holds the
     * JxBrowser Browser and installs input callbacks on it. Fixing it here needs no plugin
     * release and no plugin-api change, and covers every Swing popup any plugin opens over a page.
     *
     * Always proceeds — the click must still reach the page. Registered for every browser on
     * every platform: an unnecessary clearSelectedPath() when no menu is open is a no-op, which is
     * cheaper than reasoning about which rendering mode can strand a popup.
     *
     * NOTE for future input work: JxBrowser allows ONE callback per type, so this owns the
     * browser's only `PressMouseCallback` slot. Anything else that needs mouse presses (an RPA
     * recorder, a gesture feature) must extend this callback rather than call `browser.set(...)`
     * again — a second registration replaces this one silently, with no compile error.
     */
    fun setupSwingPopupDismissOnPageClick(browser: com.teamdev.jxbrowser.browser.Browser) {
        try {
            browser.set(
                com.teamdev.jxbrowser.browser.callback.input.PressMouseCallback::class.java,
                com.teamdev.jxbrowser.browser.callback.input.PressMouseCallback {
                    // The callback arrives on a JxBrowser thread; MenuSelectionManager is
                    // Swing state and must only be touched on the EDT.
                    javax.swing.SwingUtilities.invokeLater {
                        val manager = javax.swing.MenuSelectionManager.defaultManager()
                        if (manager.selectedPath.isNotEmpty()) {
                            manager.clearSelectedPath()
                        }
                    }
                    com.teamdev.jxbrowser.browser.callback.input.PressMouseCallback.Response
                        .proceed()
                },
            )
        } catch (e: Exception) {
            // A browser that rejects the callback still works; it just keeps the old
            // stuck-menu behaviour, which is not worth failing browser setup over.
            logger.debug(
                LogCategory.BROWSER,
                "Could not install page-click popup dismissal",
                mapOf("error" to e.toString()),
            )
        }
    }

    /**
     * Publish [BrowserFindKeyProbe] into every frame of [browser] at document start.
     *
     * **Every frame, not just the main one.** A keydown is delivered to the focused frame, so a
     * find chord pressed inside an iframe is only observable by a listener inside that iframe. The
     * interaction collector gets away with main-frame-only injection because it reports what it
     * sees; this one has to answer a question about a specific keypress, and a missing answer means
     * our bar opens over a page that wanted to serve its own.
     *
     * Through [BrowserInjectDispatcher] rather than `browser.set(InjectJsCallback…)`: JxBrowser has
     * exactly one such slot per browser and a second registration silently replaces the first.
     */
    private fun installFindKeyProbe(browser: com.teamdev.jxbrowser.browser.Browser) {
        val bridge =
            BrowserFindKeyProbeBridge { pageHandledKey ->
                BrowserFindController.onPageVerdict(browser, pageHandledKey)
            }
        BrowserInjectDispatcher.register(browser) { frame ->
            try {
                frame
                    .executeJavaScript<com.teamdev.jxbrowser.js.JsObject>("window")
                    ?.putProperty(BrowserFindKeyProbe.BRIDGE_PROPERTY, bridge)
                frame.executeJavaScript<Any?>(BrowserFindKeyProbe.source)
            } catch (e: Exception) {
                // The class only, never the message: this runs against arbitrary pages. A frame
                // that refuses injection is normal (a cross-process navigation can tear one down
                // mid-flight) and the verdict deadline covers it.
                logger.debug(
                    LogCategory.BROWSER,
                    "Find-key probe injection failed",
                    mapOf("error" to (e::class.simpleName ?: "Exception")),
                )
            }
        }
    }

    /**
     * Sets up keyboard interceptor for a browser to forward menu shortcuts to the native menu bar.
     * This intercepts Cmd+R, Cmd+N, Cmd+T, Cmd+W, etc. (on macOS) or Ctrl+R, Ctrl+N, etc. (on Windows/Linux)
     * before JxBrowser consumes them, and manually triggers the corresponding MenuActionsHandler methods.
     * Window-owned browsers suppress every key event while their AWT window is inactive.
     *
     * @param ownerWindowId stable owner used for focus gating and shortcut dispatch; null preserves
     * legacy behavior for the old unscoped browser helper.
     * @param zoomTarget the handle wrapping [browser], when one exists. Zoom goes through it rather
     * than through `browser.zoom()` so the plugin's zoom-percent indicator stays in step - see
     * [applyBrowserZoom].
     */
    fun setupKeyboardInterceptor(
        browser: com.teamdev.jxbrowser.browser.Browser,
        ownerWindowId: String? = null,
        zoomTarget: BrowserHandle? = null,
    ) {
        // Adopt the browser for find-in-page before the callback below can serve a find chord.
        // A browser created outside a BrowserHandleImpl reaches this too (the legacy
        // BrowserFunctions.createBrowser path), which is why registration lives here rather than
        // only in the handle: this is the one function both paths call.
        BrowserFindController.register(browser)
        installFindKeyProbe(browser)
        val suppressionLogged = AtomicBoolean(false)
        browser.set(
            com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback::class.java,
            com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback { params ->
                val event = params.event()
                val modifiers = event.keyModifiers()
                val keyCode = event.keyCode()

                val route =
                    resolveBrowserKeyEventRoute(
                        ownerWindowId = ownerWindowId,
                        ownerWindowIsFocused = ownerWindowId?.let(WindowFocusManager::isWindowFocused) == true,
                        fallbackFocusedWindowId =
                            if (ownerWindowId == null) {
                                WindowFocusManager.focusedWindowFlow.value
                            } else {
                                null
                            },
                    )
                if (!route.acceptsInput) {
                    ownerWindowId?.let { windowId ->
                        if (suppressionLogged.compareAndSet(false, true)) {
                            logger.debug(
                                LogCategory.BROWSER,
                                "Browser key input suppressed because owner window is not focused",
                                mapOf(
                                    "ownerWindowId" to windowId,
                                    "ownerRegistered" to (WindowFocusManager.getWindow(windowId) != null),
                                ),
                            )
                        }
                    }
                    return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                        .suppress()
                }
                suppressionLogged.set(false)
                val shortcutWindowId = route.shortcutWindowId

                // Platform-aware main modifier: Cmd on macOS, Ctrl on Windows/Linux
                val isMainModifierDown =
                    if (SystemUtils.isMacOS) {
                        modifiers.isMetaDown && !modifiers.isControlDown
                    } else {
                        modifiers.isControlDown && !modifiers.isMetaDown
                    }
                val modifierName = if (SystemUtils.isMacOS) "Cmd" else "Ctrl"

                // Intercept main modifier + key shortcuts
                if (isMainModifierDown && !modifiers.isShiftDown && !modifiers.isAltDown) {
                    // Reload is BROWSER-scoped and this callback already fires for the browser that
                    // received the key, so reload it directly instead of routing through the
                    // focused WINDOW. MenuActionsHandler.triggerReloadBrowser only emits an event
                    // that ends in the same reload, so nothing host-side is skipped — this is the
                    // same action, aimed at the browser we already have rather than at whichever
                    // window is focused.
                    //
                    // Deliberately NOT justified by "HARDWARE has no focused window": an owned
                    // browser whose window is not focused is suppressed by the acceptsInput gate
                    // above, well before this point, so that claim cannot be what makes this
                    // necessary. Applies on every platform and both rendering modes.
                    //
                    // isClosed-guarded like every other browser call in this file: this runs on a
                    // JxBrowser callback thread and can race a tab close.
                    if (keyCode == com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_R) {
                        if (!browser.isClosed) browser.navigation().reload()
                        return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                            .suppress()
                    }

                    // Zoom is BROWSER-scoped for the same reason reload directly above is: this
                    // callback fires for the browser that received the key, so the browser is
                    // already known and routing through the focused WINDOW could only make the
                    // answer worse. On Windows that is not hypothetical - clicking a Chromium
                    // native child window gives it OS focus without the click reaching Compose, so
                    // the active split panel can still be the OTHER half. Sitting before the
                    // shortcutWindowId gate also serves the legacy unowned browser, which that gate
                    // has to drop.
                    //
                    // Without this, the chord reached nothing at all on Windows: under
                    // HARDWARE_ACCELERATED the native surface consumes it, so neither the AWT
                    // keymap nor the plugin's Compose handler ever sees a key event. Same failure
                    // that made Ctrl+R need the case above. Skipped on macOS, where those layers do
                    // get the chord - see interceptsZoomNatively.
                    //
                    // suppress() unconditionally, unlike the find chord below, which proceeds so a
                    // site with its own find-in-page can pre-empt it: Chrome treats zoom as a
                    // reserved accelerator pages cannot override, and suppressing is also what
                    // stops Chromium applying its own built-in zoom on top of ours.
                    if (interceptsZoomNatively) {
                        val zoomAction = resolveBrowserZoomAction(keyCode, shiftDown = false)
                        if (zoomAction != null) {
                            applyBrowserZoom(browser, zoomTarget, zoomAction)
                            return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                .suppress()
                        }
                    }
                    if (shortcutWindowId != null) {
                        when (keyCode) {
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_N -> {
                                ai.rever.boss.window.MenuActionsHandler
                                    .triggerNewTab(shortcutWindowId)
                                return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                    .suppress()
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_T -> {
                                ai.rever.boss.window.MenuActionsHandler
                                    .triggerNewTab(shortcutWindowId)
                                return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                    .suppress()
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_W -> {
                                ai.rever.boss.window.MenuActionsHandler
                                    .triggerCloseTab(shortcutWindowId)
                                return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                    .suppress()
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_G -> {
                                // "Find again". Claimed only while our bar is up with a query -
                                // otherwise it does nothing here, and proceeding leaves the chord to
                                // a page that may have its own meaning for it.
                                if (BrowserFindController.onFindAgainKey(browser, backward = false)) {
                                    return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                        .suppress()
                                }
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_F -> {
                                // NOT suppressed unconditionally any more, which is the whole point.
                                // Suppressing meant the page never saw the key, so a site with its own
                                // find-in-page (Sheets, Docs, Notion) could never serve it - Chrome
                                // treats this chord as a non-reserved accelerator and lets the page
                                // pre-empt it. onFindKeyFromPage decides: it suppresses only when our
                                // bar is already up, and otherwise proceeds and waits for the page's
                                // verdict. See BrowserFindKeyProbe.
                                return@PressKeyCallback if (BrowserFindController.onFindKeyFromPage(browser)) {
                                    com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                        .suppress()
                                } else {
                                    com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                        .proceed()
                                }
                            }

                            else -> {
                                // Let other main modifier + key combos pass through to the native menu bar
                            }
                        }
                    } else {
                        // No window to route through. For a WINDOW-OWNED browser this is
                        // unreachable — an unfocused owner is suppressed by the acceptsInput gate
                        // above — so this is the legacy unowned path (BrowserFunctions.createBrowser
                        // passes no ownerWindowId). This callback is browser-scoped either way, so
                        // anything needing only the browser is served directly rather than dropped.
                        when (keyCode) {
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_G -> {
                                // "Find again". Claimed only while our bar is up with a query -
                                // otherwise it does nothing here, and proceeding leaves the chord to
                                // a page that may have its own meaning for it.
                                if (BrowserFindController.onFindAgainKey(browser, backward = false)) {
                                    return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                        .suppress()
                                }
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_F -> {
                                return@PressKeyCallback if (BrowserFindController.onFindKeyFromPage(browser)) {
                                    com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                        .suppress()
                                } else {
                                    com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                        .proceed()
                                }
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_N,
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_T,
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_W,
                            -> {
                                // New/close tab need a window we cannot resolve from here.
                                logger.debug(
                                    LogCategory.BROWSER,
                                    "No window focused, cannot dispatch shortcut",
                                    mapOf("shortcut" to "$modifierName+${keyCode.name}"),
                                )
                            }

                            else -> { /* Not a handled shortcut, no logging needed */ }
                        }
                    }
                }

                // Intercept main modifier + Shift + key shortcuts
                if (isMainModifierDown && modifiers.isShiftDown && !modifiers.isAltDown) {
                    // "Find previous", handled BEFORE the window gate for the same reason reload is:
                    // it needs only the browser this callback already has, so routing it through a
                    // focused window would drop it on the unowned path for nothing.
                    if (keyCode == com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_G &&
                        BrowserFindController.onFindAgainKey(browser, backward = true)
                    ) {
                        return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                            .suppress()
                    }

                    // Ctrl+Shift+'=' is how a great many layouts spell Ctrl+'+', and Chrome zooms
                    // in on it. Browser-scoped and placed before the window gate for the same
                    // reason as "find previous" above. Zoom out and reset decline shift, so only
                    // this one chord is claimed here. macOS-exempt on the same grounds as the
                    // unshifted case above.
                    if (interceptsZoomNatively) {
                        val shiftZoomAction = resolveBrowserZoomAction(keyCode, shiftDown = true)
                        if (shiftZoomAction != null) {
                            applyBrowserZoom(browser, zoomTarget, shiftZoomAction)
                            return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                .suppress()
                        }
                    }
                    if (shortcutWindowId != null) {
                        when (keyCode) {
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_F -> {
                                ai.rever.boss.window.MenuActionsHandler
                                    .triggerToggleFocusMode(shortcutWindowId)
                                return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                    .suppress()
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_S -> {
                                ai.rever.boss.window.MenuActionsHandler
                                    .triggerSaveWorkspace(shortcutWindowId)
                                return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                    .suppress()
                            }

                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_V -> {
                                // Paste without formatting:
                                // 1. Save original clipboard
                                // 2. Replace with plain text only (strips HTML/RTF)
                                // 3. Dispatch synthetic Cmd+V via JxBrowser API (triggers native paste)
                                // 4. Restore original clipboard after delay
                                try {
                                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                    val originalContents = clipboard.getContents(null)
                                    val plainText = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                                    if (plainText != null) {
                                        clipboard.setContents(java.awt.datatransfer.StringSelection(plainText), null)
                                        // Dispatch Cmd+V (or Ctrl+V) as a native key event to trigger paste
                                        val pasteModifiers =
                                            com.teamdev.jxbrowser.ui.KeyModifiers
                                                .newBuilder()
                                                .apply {
                                                    if (SystemUtils.isMacOS) metaDown(true) else controlDown(true)
                                                }.build()
                                        browser.dispatch(
                                            com.teamdev.jxbrowser.ui.event.KeyPressed
                                                .newBuilder(
                                                    com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_V,
                                                ).keyModifiers(pasteModifiers)
                                                .build(),
                                        )
                                        browser.dispatch(
                                            com.teamdev.jxbrowser.ui.event.KeyReleased
                                                .newBuilder(
                                                    com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_V,
                                                ).keyModifiers(pasteModifiers)
                                                .build(),
                                        )
                                        // Restore original clipboard after paste completes
                                        if (originalContents != null) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                delay(200)
                                                try {
                                                    clipboard.setContents(originalContents, null)
                                                } catch (_: Exception) {
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    logger.debug(
                                        LogCategory.BROWSER,
                                        "Paste without formatting failed",
                                        mapOf("error" to (e.message ?: "unknown")),
                                    )
                                }
                                return@PressKeyCallback com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                                    .suppress()
                            }

                            else -> {
                                // Let other main modifier + Shift + key combos pass through
                            }
                        }
                    } else {
                        // Log only for shortcuts we handle to avoid spam
                        when (keyCode) {
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_F,
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_S,
                            com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_V,
                            -> {
                                logger.debug(
                                    LogCategory.BROWSER,
                                    "No window focused, cannot dispatch shortcut",
                                    mapOf("shortcut" to "$modifierName+Shift+${keyCode.name}"),
                                )
                            }

                            else -> { /* Not a handled shortcut, no logging needed */ }
                        }
                    }
                }

                // Let all other key events proceed normally
                com.teamdev.jxbrowser.browser.callback.input.PressKeyCallback.Response
                    .proceed()
            },
        )
    }

    fun setupBrowserDownloadHandler(browser: com.teamdev.jxbrowser.browser.Browser) {
        // Set up download handler for this browser
        browser.set(
            StartDownloadCallback::class.java,
            StartDownloadCallback { params, action ->
                val download = params.download()
                val target = download.target()

                // Mark this URL as an active download IMMEDIATELY to prevent popup handler from opening a new tab
                // This must happen before any other logic because popup handler may execute concurrently
                val downloadUrl = target.url()
                activeDownloadUrls.add(downloadUrl)

                // Auto-close any tabs that were recently opened (likely download redirects)
                autoCloseDownloadTab()

                val suggestedFileName = target.suggestedFileName()
                val sanitizedFileName = FileNameSanitizer.sanitize(suggestedFileName)

                // Check if Shift key is pressed (force save dialog)
                val forceDialog = isShiftPressed()

                // Generated up here because it owns the path claim below, and a claim has to
                // name its holder so that no other download can release it.
                val downloadId = UUID.randomUUID().toString()

                // Determine save location based on settings
                val savePath =
                    when {
                        downloadSettings.alwaysAskWhereToSave || forceDialog -> {
                            // Show save dialog
                            pickSaveFile(
                                suggestedFileName = sanitizedFileName,
                                initialDirectory =
                                    downloadSettings.lastUsedDirectory
                                        ?: downloadSettings.defaultDownloadDirectory,
                            )
                        }

                        else -> {
                            // Auto-save to default/last directory
                            val directory =
                                downloadSettings.lastUsedDirectory
                                    ?: downloadSettings.defaultDownloadDirectory
                            FileSystemUtils.generateUniqueFilePath(directory, sanitizedFileName, owner = downloadId)
                        }
                    }

                if (savePath != null) {
                    // The name the file is actually written under. generateUniqueFilePath may
                    // have appended " (1)", and the save dialog lets the user type anything at
                    // all, so sanitizedFileName is only ever a *request*. Recording that instead
                    // left the Downloads panel showing "report.pdf" for a file on disk called
                    // "report (1).pdf" - and Rename, Reveal and Open all read from the panel.
                    val savedFileName = java.io.File(savePath).name
                    // Ensure parent directory exists
                    if (!FileSystemUtils.ensureParentDirectoryExists(savePath)) {
                        // Bailing before setupDownloadEventListeners means none of the three
                        // terminal handlers will run, so the claim is released here or never.
                        FileSystemUtils.releaseFilePath(savePath, owner = downloadId)
                        action.cancel()
                        return@StartDownloadCallback
                    }

                    // Warn for executable files
                    if (downloadSettings.warnForExecutables &&
                        FileNameSanitizer.isExecutableFile(sanitizedFileName)
                    ) {
                        // TODO: Show user warning dialog (for now, just proceed)
                    }

                    // Start the download
                    val downloadPath = Paths.get(savePath)

                    // Update last used directory
                    val parentDir = downloadPath.parent?.toString()
                    if (parentDir != null) {
                        downloadSettings = downloadSettings.copy(lastUsedDirectory = parentDir)
                    }

                    // Add download to manager immediately and open Downloads panel
                    CoroutineScope(Dispatchers.Default).launch {
                        downloadManager.addDownload(
                            DownloadItem(
                                id = downloadId,
                                fileName = savedFileName,
                                destinationPath = savePath,
                                url = target.url(),
                                mimeType = target.mimeType().toString(),
                                status = DownloadStatus.DOWNLOADING,
                                receivedBytes = 0,
                                totalBytes = null,
                                speed = 0.0,
                                startedAt = System.currentTimeMillis(),
                                finishedAt = null,
                                canPause = false,
                                canResume = false,
                                errorReason = null,
                            ),
                        )

                        // Open the Downloads sidebar panel. Same reasoning as the
                        // deep-link handlers: a download can start while BOSS does
                        // not hold OS focus, so focusedWindowFlow alone would drop
                        // the panel open even though a usable window is registered.
                        val targetWindowId = WindowFocusManager.resolveActionableWindowId()
                        if (targetWindowId != null) {
                            ai.rever.boss.components.events.PanelEventBus.openPanel(
                                ai.rever.boss.components.plugin.PanelIds.DOWNLOADS,
                                sourceWindowId = targetWindowId,
                            )
                        } else {
                            logger.warn(LogCategory.UI, "No usable window registered, cannot open Downloads panel")
                        }
                    }

                    // Register event listeners on the download object
                    val downloadObj = download
                    setupDownloadEventListeners(downloadObj, downloadId, savePath, target.url())

                    // Initiate the download
                    action.download(downloadPath)
                } else {
                    // User cancelled save dialog
                    action.cancel()
                }
            },
        )
    }

    private fun setupDownloadEventListeners(
        download: Download,
        downloadId: String,
        destinationPath: String,
        url: String,
    ) {
        val scope = CoroutineScope(Dispatchers.Default)

        // Track this download for pause/resume operations
        activeDownloads[downloadId] = download

        // Download progress updated
        download.on(DownloadUpdated::class.java) { event ->
            scope.launch {
                val receivedBytes = event.receivedBytes()
                val totalBytes = event.totalBytes()
                val speed = event.currentSpeed().toDouble()

                // Update capabilities based on server support
                // JxBrowser automatically supports pause/resume if the server supports HTTP range requests
                val canPause = !download.isPaused
                val canResume = download.isPaused
                downloadManager.updateCapabilities(downloadId, canPause, canResume)

                // Check if download was resumed (was PAUSED, now actively downloading)
                val currentItem = downloadManager.getDownload(downloadId)
                if (currentItem?.status == DownloadStatus.PAUSED && !download.isPaused && speed > 0) {
                    downloadManager.updateStatus(downloadId, DownloadStatus.DOWNLOADING)
                }

                downloadManager.updateProgress(downloadId, receivedBytes, totalBytes, speed)
            }
        }

        // Download paused
        download.on(DownloadPaused::class.java) { event ->
            scope.launch {
                downloadManager.updateStatus(downloadId, DownloadStatus.PAUSED)
            }
        }

        // Download finished
        download.on(DownloadFinished::class.java) { event ->
            scope.launch {
                downloadManager.updateStatus(downloadId, DownloadStatus.COMPLETED)
                // Remove from tracking maps
                activeDownloadUrls.remove(url)
                activeDownloads.remove(downloadId)
                // The file exists now, so exists() guards the name from here on.
                FileSystemUtils.releaseFilePath(destinationPath, owner = downloadId)
            }
        }

        // Download interrupted (failed)
        download.on(DownloadInterrupted::class.java) { event ->
            scope.launch {
                val reason = event.reason()?.toString() ?: "Unknown error"
                downloadManager.updateStatus(
                    downloadId,
                    DownloadStatus.FAILED,
                    errorReason = "Download failed: $reason",
                )
                FileSystemUtils.cleanupPartialFile(destinationPath)
                // Remove from tracking maps
                activeDownloadUrls.remove(url)
                activeDownloads.remove(downloadId)
                // Nothing was written, so the name goes back to the pool rather than
                // pushing the next download of it onto a suffix it does not need.
                FileSystemUtils.releaseFilePath(destinationPath, owner = downloadId)
            }
        }

        // Download cancelled
        download.on(DownloadCanceled::class.java) { event ->
            scope.launch {
                downloadManager.updateStatus(downloadId, DownloadStatus.CANCELLED)
                FileSystemUtils.cleanupPartialFile(destinationPath)
                // Remove from tracking maps
                activeDownloadUrls.remove(url)
                activeDownloads.remove(downloadId)
                FileSystemUtils.releaseFilePath(destinationPath, owner = downloadId)
            }
        }
    }

    /**
     * Checks if Shift key is currently pressed.
     * Used to force save dialog even when auto-save is enabled.
     *
     * Note: This is a placeholder implementation. Detecting modifier keys
     * outside of event handlers is not reliably supported in AWT.
     * For now, always returns false (user can enable "always ask" in settings).
     */
    private fun isShiftPressed(): Boolean {
        return false // TODO: Implement if needed
    }

    /**
     * Result of browser profile reset operation with detailed step status.
     */
    data class ResetResult(
        val success: Boolean,
        val engineClosed: Boolean = false,
        val profileDeleted: Boolean = false,
        val tempProfilesCleaned: Boolean = false,
        val errorMessage: String? = null,
        val failedStep: String? = null,
    )

    /**
     * Reset browser profile to fix persistent browser issues.
     * This will:
     * 1. Close the current engine (if running)
     * 2. Delete the browser profile directory
     * 3. Clear cached state so engine reinitializes on next use
     *
     * IMPORTANT: This is a suspend function that runs blocking I/O on Dispatchers.IO
     * to avoid freezing the UI thread.
     *
     * @return ResetResult with detailed status of each step
     */
    suspend fun resetBrowserProfile(): ResetResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var engineClosed = false
            var profileDeleted = false
            var tempProfilesCleaned = false

            try {
                // Step 1: Close current engine if it exists
                _engine?.let { engine ->
                    if (!engine.isClosed) {
                        try {
                            engine.close()
                            engineClosed = true
                        } catch (e: Exception) {
                            // Continue anyway - engine may be in bad state
                            logger.warn(LogCategory.BROWSER, "Engine close failed during reset - continuing", error = e)
                            engineClosed = true // Mark as closed since we tried
                        }
                    } else {
                        engineClosed = true // Already closed
                    }
                } ?: run {
                    engineClosed = true // No engine to close
                }

                // Step 2: Clear cached state (must happen even if engine close had issues)
                _engine = null
                initializationError = null
                attemptCount = 0
                // Increment generation to notify browser tabs that they need to reload
                _engineGeneration++
                _engineGenerationFlow.value = _engineGeneration

                // Step 3: Kill any stale Chromium processes
                try {
                    killStaleChromiumProcesses()
                } catch (e: Exception) {
                    // Continue - not critical
                    logger.debug(
                        LogCategory.BROWSER,
                        "Stale Chromium process kill failed during reset",
                        mapOf("error" to e.toString()),
                    )
                }

                // Step 4: Delete browser profile directory
                val selectedProfile = BrowserSettings.currentProfile
                val profileDir = BossDirectories.resolve(selectedProfile)

                if (profileDir.exists()) {
                    profileDeleted = profileDir.deleteRecursively()
                    if (profileDeleted) {
                    } else {
                        // This is a partial failure - return with details
                        return@withContext ResetResult(
                            success = false,
                            engineClosed = engineClosed,
                            profileDeleted = false,
                            tempProfilesCleaned = false,
                            errorMessage = "Could not delete all files in profile directory. Some files may be locked.",
                            failedStep = "Delete profile directory",
                        )
                    }
                } else {
                    profileDeleted = true // Nothing to delete is success
                }

                // Step 5: Also clean up temporary profiles
                try {
                    cleanupOldTemporaryProfiles()
                    tempProfilesCleaned = true
                } catch (e: Exception) {
                    // Not critical - continue
                    logger.debug(
                        LogCategory.BROWSER,
                        "Temporary profile cleanup failed during reset",
                        mapOf("error" to e.toString()),
                    )
                    tempProfilesCleaned = false
                }

                ResetResult(
                    success = true,
                    engineClosed = engineClosed,
                    profileDeleted = profileDeleted,
                    tempProfilesCleaned = tempProfilesCleaned,
                )
            } catch (e: Exception) {
                ResetResult(
                    success = false,
                    engineClosed = engineClosed,
                    profileDeleted = profileDeleted,
                    tempProfilesCleaned = tempProfilesCleaned,
                    errorMessage = e.message,
                    failedStep = "Unknown",
                )
            }
        }

    /**
     * Synchronous wrapper for resetBrowserProfile for simple use cases.
     * Runs the reset on a background thread and blocks until complete.
     *
     * @return true if reset was successful, false otherwise
     */
    fun resetBrowserProfileBlocking(): Boolean =
        kotlinx.coroutines.runBlocking {
            resetBrowserProfile().success
        }

    /**
     * Check if browser engine is in a healthy state.
     * Used to determine if reset might be needed.
     */
    fun isEngineHealthy(): Boolean {
        // isClosed alone cannot see a wedged engine: its Chromium process is still alive, so
        // JxBrowser reports it open while every newBrowser() fails. BrowserServiceImpl's
        // detector supplies that signal once auto-recycling has given up on repairing it.
        if (wedgeUnrecoverable) return false
        return _engine?.let { !it.isClosed } ?: true // null engine is "healthy" (will initialize on demand)
    }
}
