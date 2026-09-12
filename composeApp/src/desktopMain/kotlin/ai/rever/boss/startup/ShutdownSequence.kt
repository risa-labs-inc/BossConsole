package ai.rever.boss.startup

import ai.rever.boss.app.LastSessionCoordinator
import ai.rever.boss.cache.HighQualityFaviconService
import ai.rever.boss.performance.PerformanceMonitor
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.updater.AppUpdateRealtimeService
import ai.rever.boss.updater.UpdateCoordinator
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.window.AWTKeyboardInterceptor

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
            ShutdownStep("stopping performance monitor") {
                PerformanceMonitor.stop()
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
