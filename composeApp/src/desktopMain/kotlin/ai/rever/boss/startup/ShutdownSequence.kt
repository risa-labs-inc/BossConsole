package ai.rever.boss.startup

import ai.rever.boss.app.LastSessionCoordinator
import ai.rever.boss.cache.HighQualityFaviconService
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.dashboard.RecentFilesManager
import ai.rever.boss.performance.PerformanceMonitor
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.browser.BrowserCleanupDrain
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.services.auth.UserDataStorage
import ai.rever.boss.updater.AppUpdateRealtimeService
import ai.rever.boss.updater.UpdateCoordinator
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.AWTKeyboardInterceptor
import kotlinx.coroutines.runBlocking

/**
 * A named step in the shutdown sequence.
 */
data class ShutdownStep(
    val name: String,
    val action: () -> Unit,
)

/**
 * Encapsulates the application shutdown sequence.
 *
 * Each step is executed in deterministic order, with per-step error isolation
 * so a failure in one subsystem teardown does not abort the remaining steps.
 */
object ShutdownSequence {
    /**
     * How long shutdown waits for browser native cleanup before the engine closes.
     *
     * The same five-second bound the engine-close path uses, rather than the per-handle ten-second
     * warning: this is the last moment a pending native close can still run, and a quit that hangs
     * is its own defect. The engine close that follows is what makes this a deadline rather than a
     * join - past it the cleanup's own browser close has nothing left to run against.
     */
    private const val BROWSER_CLEANUP_DRAIN_MS = 5_000L

    private val logger = BossLogger.forComponent("ShutdownSequence")

    /**
     * Builds the standard shutdown steps for the application.
     */
    fun defaultSteps(kernelBootstrapProvider: () -> Any? = { null }): List<ShutdownStep> =
        listOf(
            ShutdownStep("saving Last Session on exit") {
                // Save "Last Session" for the exits that never dispose a Compose
                // composition, so the window-dispose save never runs: macOS
                // app-menu Quit / Cmd+Q, ApplicationRestarter's exitProcess paths, SIGTERM.
                LastSessionCoordinator.instance.saveOnProcessExit()
            },
            ShutdownStep("flushing debounced recent-files and user-data saves on exit") {
                // RecentFilesManager debounces saves by up to 5 seconds; quitting inside
                // that window dropped the last recorded entry - the gap #795's own body
                // called out as a separate bug. runBlocking, not a fire-and-forget launch:
                // this hook thread must not return - and let the process finish exiting -
                // before both writes are on disk.
                runBlocking {
                    RecentFilesManager.flushPendingSaves()
                    RecentBrowserPagesManager.flushPendingSaves()
                    UserDataStorage.flushPendingSaves()
                }
            },
            ShutdownStep("stopping performance monitor") {
                PerformanceMonitor.stop()
            },
            ShutdownStep("draining browser native cleanup") {
                // Before the engine closes, not after: what is being waited on is a browser close
                // and a profile release, and both call into the engine this sequence is about to
                // shut down. Bounded, because a wedged renderer must not hold the quit open - it
                // keeps its browser and profile exactly as it does on the per-handle path, and the
                // warning below reports what was left behind.
                if (!runBlocking { BrowserCleanupDrain.awaitDrained(BROWSER_CLEANUP_DRAIN_MS) }) {
                    logger.warn(
                        LogCategory.BROWSER,
                        "Browser native cleanup abandoned at shutdown",
                        mapOf("pending" to BrowserCleanupDrain.pending, "deadlineMs" to BROWSER_CLEANUP_DRAIN_MS),
                    )
                }
            },
            ShutdownStep("closing browser engine") {
                val engine = FluckEngine.currentEngine
                if (engine != null && !engine.isClosed) {
                    engine.close()
                }
            },
            ShutdownStep("closing favicon HTTP client") {
                HighQualityFaviconService.close()
            },
            ShutdownStep("uninstalling keyboard interceptor") {
                AWTKeyboardInterceptor.uninstall()
            },
            ShutdownStep("stopping app update realtime") {
                AppUpdateRealtimeService.instance.dispose()
            },
            ShutdownStep("shutting down updater") {
                UpdateCoordinator.instance.shutdown()
            },
            ShutdownStep("shutting down plugin store") {
                PluginStoreSetup.shutdown()
            },
            ShutdownStep("shutting down logger") {
                BossLogger.shutdown()
            },
            ShutdownStep("shutting down kernel") {
                kernelBootstrapProvider()?.let { kb ->
                    kb.javaClass.getMethod("shutdown").invoke(kb)
                }
            },
        )

    /**
     * Executes all shutdown steps sequentially with error isolation, followed by lock release.
     */
    fun execute(
        steps: List<ShutdownStep>,
        releaseLock: () -> Unit = { SingleInstanceManager.release() },
    ) = executePrepared({ steps }, releaseLock)

    @Suppress("TooGenericExceptionCaught")
    internal fun executePrepared(
        prepareSteps: () -> List<ShutdownStep>,
        releaseLock: () -> Unit = { SingleInstanceManager.release() },
    ) {
        try {
            for (step in prepareSteps()) {
                try {
                    step.action()
                } catch (e: Throwable) {
                    System.err.println("Error ${step.name}: ${e.message}")
                }
            }
        } finally {
            try {
                releaseLock()
            } catch (e: Throwable) {
                System.err.println("Error releasing single-instance lock: ${e.message}")
            }
        }
    }

    /**
     * Registers the JVM runtime shutdown hook using the default shutdown steps.
     */
    fun register(kernelBootstrapProvider: () -> Any? = { null }): Thread {
        val hookThread =
            Thread(
                {
                    executePrepared({ defaultSteps(kernelBootstrapProvider) })
                },
                "boss-shutdown-hook",
            )
        Runtime.getRuntime().addShutdownHook(hookThread)
        return hookThread
    }
}
