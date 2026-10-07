package ai.rever.boss.startup

import ai.rever.boss.cli.CLICommandHandler
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.plugin.browser.ChromiumToolkitPreload
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.ApplicationRestarter
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Result of Chromium engine verification and preparation.
 */
data class ChromiumPreparation(
    val needsDownload: Boolean,
    val engineLabel: String,
)

/**
 * What [ChromiumBootstrap.preflight] decided about the engine, handed to [ChromiumBootstrap.prepare].
 */
internal data class ChromiumPreflight(
    val engineAction: FluckEngine.EngineStartupAction,
)

/**
 * Handles browser engine verification, lock cleanup, pending install promotion,
 * and background pre-warming.
 */
object ChromiumBootstrap {
    private val logger by lazy { BossLogger.forComponent("ChromiumBootstrap") }

    /**
     * Inspects the browser engine, cleans stale locks, promotes pending downloads, logs the engine
     * startup verdict and, when the engine will boot from disk, preloads the native toolkit.
     *
     * On the Download path nothing is preloaded: the engine is not on disk yet. On a packaged macOS
     * build [onEngineDownloadComplete] relaunches once it lands, so the toolkit is loaded by this
     * preflight in the next process rather than by JxBrowser with the app fully running.
     *
     * **Call before anything creates the AWT toolkit** (`DefaultWindowIcon.install()`), and only
     * while the single-instance lock is held: pending-install promotion renames the engine directory
     * another instance may be booting from. `StartupOrderingTest` pins both ends of that in
     * `main`. Loading the toolkit swaps the process's default malloc zone, and a free() on
     * another thread mid-swap traps in the shim. Once AppKit runs, Core Animation frees on the
     * AppKit thread continuously (9.5.33, 2026-09-29: `brk #0` at `libtoolkit+0x4c9f4` under
     * `CA::Transaction::commit`, +1.9s). See [ChromiumToolkitPreload].
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun preflight(): ChromiumPreflight {
        // Proactively clean up stale JxBrowser lock files from previous sessions
        try {
            FluckEngine.proactiveCleanupOnStartup()
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Proactive browser lock cleanup failed", error = e)
        }

        // Check before prewarming or PasskeyPlatformInit: a stale native engine otherwise raises
        // UnsatisfiedLinkError before the download repair UI can run after a JxBrowser upgrade.
        // Cache health alone is insufficient (#121): the resolver prefers a bundled engine and
        // must validate the directory it will actually boot.
        // promotePendingInstall must stay ahead of engine creation: it renames the engine directory.
        ChromiumAutoDownloader.promotePendingInstall()

        // Ask whether an engine will actually boot
        val cacheHealthy = ChromiumAutoDownloader.isChromiumInstalled()
        val hasUsableEngine = FluckEngine.hasUsableEngine(cacheHealthy)
        val engineAction = FluckEngine.engineStartupAction(hasUsableEngine, cacheHealthy)

        when (engineAction) {
            FluckEngine.EngineStartupAction.BootAndReport -> {
                logger.error(
                    LogCategory.SYSTEM,
                    "Installed engine is healthy and stamped with the required version but is still " +
                        "unusable - the published archive does not match this build; not re-downloading",
                    mapOf("required" to ChromiumAutoDownloader.effectiveVersion),
                )
            }

            FluckEngine.EngineStartupAction.Download -> {
                logger.info(
                    LogCategory.SYSTEM,
                    "No usable browser engine - will prompt for download",
                    mapOf("required" to ChromiumAutoDownloader.effectiveVersion),
                )
            }

            FluckEngine.EngineStartupAction.Boot -> {
                Unit
            }
        }

        // Load JxBrowser's native toolkit HERE, on the main thread, before AppKit starts and
        // before the pre-warm thread exists: loading it swaps the process's malloc zones, and a
        // free() on another thread during that swap is an uncatchable SIGTRAP. Same directory the engine
        // will boot from, so JxBrowser's own System.load later is a no-op. See ChromiumToolkitPreload.
        // Boot only: on Download the engine is not on disk yet; onEngineDownloadComplete
        // relaunches (packaged macOS) so the next process preloads here instead.
        // The preload also records what it loaded for the native agent, which repeats it before
        // the JVM starts its threads on the next launch; any other verdict clears that record.
        if (engineAction == FluckEngine.EngineStartupAction.Boot) {
            ChromiumToolkitPreload.preload(FluckEngine.resolveEngineDir(cacheHealthy))
        } else {
            ChromiumToolkitPreload.forgetNextLaunch("engine verdict $engineAction")
        }

        return ChromiumPreflight(engineAction = engineAction)
    }

    /** What [onEngineDownloadComplete] does once the engine is on disk. */
    internal enum class AfterDownload {
        /** Restart, so the new process preloads the toolkit in [preflight] before AppKit starts. */
        Relaunch,

        /** Boot the engine in this process, with AppKit running: exposed to the zone-swap race. */
        BootInProcess,
    }

    /**
     * Relaunch only where the race exists and the relaunch is reliable: a packaged macOS
     * `BOSS.app` ([ApplicationRestarter.canRelaunchMacBundle] is false everywhere else). Gradle
     * runs cannot be trusted to come back, and other platforms do not swap malloc zones, so both
     * keep booting in process.
     */
    internal fun afterDownloadAction(canRelaunch: Boolean): AfterDownload =
        if (canRelaunch) AfterDownload.Relaunch else AfterDownload.BootInProcess

    /**
     * Called when the engine download the setup window ran has finished, while only that window is
     * up: on a fresh install, and equally after an app update or engine change that needed a new
     * engine, or a damaged cache.
     *
     * Booting here crashed the very first launch of a fresh install (9.5.39, 2026-10-05, +15.6s):
     * JxBrowser's `Chromium Process Thread` loaded `libtoolkit` for the first time, its static
     * initializer swapped the malloc zones, and the AppKit thread freed mid-swap from an
     * `NSAutoFillHeuristicController` timer - `brk #0` at `libtoolkit+0x4c9f4`. No preload covers a
     * load this late, because AppKit is already running. The relaunched process reaches [preflight]
     * with the engine installed, takes the Boot path and preloads before AppKit, and records the
     * manifest so the launch after that gets the native agent too.
     *
     * No window, workspace or browser exists yet, but the requests BOSS was opened with (a
     * `boss://` link, a file) are queued in memory until the main window starts the CLI handler;
     * [RelaunchHandoff] carries them, and the forced pre-warm, to the next process. If the relauncher
     * cannot even be started, the handoff is withdrawn, the requests go back on the queue and the
     * engine boots here after all: the race is lost only sometimes, and quitting with nothing coming
     * back is worse.
     *
     * [onRelaunching] runs just before the restart so the setup window can say so; [bootInProcess]
     * is the previous behaviour.
     */
    fun onEngineDownloadComplete(
        onRelaunching: () -> Unit,
        bootInProcess: () -> Unit,
    ) {
        val action = afterDownloadAction(canRelaunch = ApplicationRestarter.canRelaunchMacBundle())
        logger.info(LogCategory.SYSTEM, "Browser engine downloaded", mapOf("next" to action.name))
        if (action == AfterDownload.BootInProcess) {
            bootInProcess()
            return
        }
        val cli = CLICommandHandler.getInstance()
        val pending = cli.takeQueuedForRelaunch()
        if (!RelaunchHandoff.write(pending, forcePrewarm = true)) {
            // Restarting now would drop those requests; keep them and boot here instead.
            pending.forEach(cli::queueCommand)
            bootInProcess()
            return
        }
        onRelaunching()
        ApplicationRestarter.restartApplication(
            onRelaunchNotStarted = {
                RelaunchHandoff.discard()
                pending.forEach(cli::queueCommand)
                bootInProcess()
            },
        )
    }

    /**
     * Requests the background engine pre-warm and reports whether [preflight] requires a download.
     * The engine's existing pre-warm path performs its own usability checks.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun prepare(
        preflight: ChromiumPreflight,
        forcePrewarm: Boolean = false,
    ): ChromiumPreparation {
        val engineLabel = "BOSS Browser Engine ${ChromiumAutoDownloader.effectiveVersion}"
        val needsDownload = preflight.engineAction == FluckEngine.EngineStartupAction.Download

        // Pre-warm the browser engine off the UI thread so the first browser tab opens quickly
        try {
            // Forced after an engine-download relaunch: the in-process path it replaces forced the
            // pre-warm, because a freshly downloaded engine has no browser profile and the
            // unforced gate reads that as "this machine does not use the browser".
            FluckEngine.prewarmInBackground(force = forcePrewarm)
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Browser engine pre-warm failed to start", error = e)
        }

        return ChromiumPreparation(
            needsDownload = needsDownload,
            engineLabel = engineLabel,
        )
    }
}
