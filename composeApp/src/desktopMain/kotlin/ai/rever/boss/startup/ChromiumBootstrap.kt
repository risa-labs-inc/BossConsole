package ai.rever.boss.startup

import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.plugin.browser.FluckEngine
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
 * Handles browser engine verification, lock cleanup, pending install promotion,
 * and background pre-warming.
 */
object ChromiumBootstrap {
    private val logger by lazy { BossLogger.forComponent("ChromiumBootstrap") }

    /**
     * Inspects the browser engine, cleans stale locks, promotes pending downloads,
     * logs engine startup verdicts, and initiates background pre-warming if ready.
     */
    @Suppress("TooGenericExceptionCaught")
    fun prepare(): ChromiumPreparation {
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

        val engineLabel = "BOSS Browser Engine ${ChromiumAutoDownloader.effectiveVersion}"
        val needsDownload = engineAction == FluckEngine.EngineStartupAction.Download

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

        // Pre-warm the browser engine off the UI thread so the first browser tab opens quickly
        try {
            FluckEngine.prewarmInBackground()
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Browser engine pre-warm failed to start", error = e)
        }

        return ChromiumPreparation(
            needsDownload = needsDownload,
            engineLabel = engineLabel,
        )
    }
}
