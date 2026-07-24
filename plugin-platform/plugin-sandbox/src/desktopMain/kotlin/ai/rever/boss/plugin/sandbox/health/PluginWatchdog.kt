package ai.rever.boss.plugin.sandbox.health

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.SandboxConfig
import ai.rever.boss.plugin.sandbox.SandboxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watchdog that monitors a single plugin sandbox for health issues.
 *
 * The watchdog periodically checks:
 * - Heartbeat timeout: If the plugin hasn't sent a heartbeat within the threshold
 * - Error threshold: If consecutive errors exceed the maximum
 *
 * When issues are detected, the watchdog triggers appropriate actions like
 * marking the sandbox unhealthy or triggering a restart.
 */
class PluginWatchdog(
    private val sandbox: PluginSandbox,
    private val config: SandboxConfig,
    private val scope: CoroutineScope,
    private val onRestartRequested: suspend (String) -> Unit,
) {
    private val logger = BossLogger.forComponent("PluginWatchdog")
    private var watchdogJob: Job? = null

    // Prevent concurrent restart attempts from rapid successive health check failures
    private val restartInProgress = AtomicBoolean(false)

    /**
     * Start the watchdog monitoring.
     */
    fun start() {
        if (watchdogJob?.isActive == true) {
            logger.debug(
                LogCategory.SYSTEM,
                "Watchdog already running for plugin",
                mapOf(
                    "pluginId" to sandbox.pluginId,
                ),
            )
            return
        }

        logger.info(
            LogCategory.SYSTEM,
            "Starting watchdog for plugin",
            mapOf(
                "pluginId" to sandbox.pluginId,
                "checkIntervalMs" to config.heartbeatIntervalMs,
            ),
        )

        watchdogJob =
            scope.launch {
                while (isActive) {
                    delay(config.heartbeatIntervalMs)
                    checkHealth()
                }
            }
    }

    /**
     * Stop the watchdog monitoring.
     */
    fun stop() {
        logger.info(
            LogCategory.SYSTEM,
            "Stopping watchdog for plugin",
            mapOf(
                "pluginId" to sandbox.pluginId,
            ),
        )
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private suspend fun checkHealth() {
        val metrics = sandbox.healthMetrics.value
        val currentState = sandbox.state.value

        // Skip checks if sandbox is already stopped or restarting
        if (currentState == SandboxState.STOPPED || currentState == SandboxState.RESTARTING) {
            return
        }

        val timeSinceHeartbeat = System.currentTimeMillis() - metrics.lastHeartbeat

        // Check for heartbeat timeout (early return prevents duplicate restart triggers)
        if (timeSinceHeartbeat > config.unhealthyThresholdMs) {
            logger.warn(
                LogCategory.SYSTEM,
                "Plugin heartbeat timeout",
                mapOf(
                    "pluginId" to sandbox.pluginId,
                    "timeSinceHeartbeatMs" to timeSinceHeartbeat,
                    "thresholdMs" to config.unhealthyThresholdMs,
                ),
            )
            sandbox.markUnhealthy()
            triggerRestart("Heartbeat timeout")
            return
        }

        // Check for consecutive error threshold (early return prevents duplicate restart triggers)
        if (metrics.consecutiveErrors >= config.maxConsecutiveErrors) {
            logger.warn(
                LogCategory.SYSTEM,
                "Plugin exceeded error threshold",
                mapOf(
                    "pluginId" to sandbox.pluginId,
                    "consecutiveErrors" to metrics.consecutiveErrors,
                    "threshold" to config.maxConsecutiveErrors,
                ),
            )
            triggerRestart("Consecutive errors exceeded threshold")
            return
        }

        // Log if unhealthy but not yet requiring restart
        if (currentState == SandboxState.UNHEALTHY) {
            logger.debug(
                LogCategory.SYSTEM,
                "Plugin is unhealthy but monitoring",
                mapOf(
                    "pluginId" to sandbox.pluginId,
                    "consecutiveErrors" to metrics.consecutiveErrors,
                ),
            )
        }
    }

    private suspend fun triggerRestart(reason: String) {
        // Prevent concurrent restart attempts
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.debug(
                LogCategory.SYSTEM,
                "Restart already in progress, skipping",
                mapOf(
                    "pluginId" to sandbox.pluginId,
                    "reason" to reason,
                ),
            )
            return
        }

        try {
            val metrics = sandbox.healthMetrics.value

            // Check if we've exceeded max restart attempts
            if (metrics.restartAttempts >= config.maxRestartAttempts) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Plugin exceeded max restart attempts, disabling",
                    mapOf(
                        "pluginId" to sandbox.pluginId,
                        "restartAttempts" to metrics.restartAttempts,
                        "maxAttempts" to config.maxRestartAttempts,
                    ),
                )
                sandbox.stop()
                // Stop watchdog to release resources and prevent further monitoring
                stop()
                return
            }

            logger.info(
                LogCategory.SYSTEM,
                "Triggering plugin restart",
                mapOf(
                    "pluginId" to sandbox.pluginId,
                    "reason" to reason,
                    "attempt" to (metrics.restartAttempts + 1),
                ),
            )

            onRestartRequested(sandbox.pluginId)
        } finally {
            restartInProgress.set(false)
        }
    }
}
