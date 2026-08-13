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
    /** Monotonic clock, injectable so the stall guard can be tested. */
    private val monotonicMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    /** Wall clock, injectable so the stall guard can be tested. */
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private val logger = BossLogger.forComponent("PluginWatchdog")
    private var watchdogJob: Job? = null

    // Prevent concurrent restart attempts from rapid successive health check failures
    private val restartInProgress = AtomicBoolean(false)

    // Consecutive checks that found the plugin healthy. Clears the restart
    // counter once the plugin has stayed healthy long enough to have earned it.
    private var healthyChecks = 0

    // Wall-clock deadline until which health checks are suppressed after a
    // process-wide stall, giving every plugin's heartbeat coroutine - frozen by
    // the same stall - a full unhealthy window to run again and prove liveness.
    private var suppressChecksUntilMs = 0L

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }

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
                var lastTickMonotonic = monotonicMillis()
                var lastTickWallClock = wallClockMillis()
                while (isActive) {
                    delay(config.heartbeatIntervalMs)

                    val nowMonotonic = monotonicMillis()
                    val nowWallClock = wallClockMillis()
                    val monotonicElapsed = nowMonotonic - lastTickMonotonic
                    val wallClockElapsed = nowWallClock - lastTickWallClock
                    lastTickMonotonic = nowMonotonic
                    lastTickWallClock = nowWallClock

                    val stalled = detectStall(monotonicElapsed, wallClockElapsed, nowWallClock)
                    if (!stalled && nowWallClock >= suppressChecksUntilMs) {
                        checkHealth()
                    }
                }
            }
    }

    /**
     * Decide whether this tick can say anything about plugin health.
     *
     * Two ways the process itself stalls, each with its own signature:
     *
     * - **The machine slept** (or the wall clock jumped). A monotonic clock does
     *   not advance across suspend on macOS or Linux, so wall-clock time runs
     *   ahead of it. Every plugin's heartbeat looks exactly that much older
     *   than it is.
     * - **The process froze while awake** - a long GC pause, a breakpoint, a
     *   starved dispatcher. Both clocks advance together, and this loop's own
     *   overrun is what gives it away.
     *
     * In both cases the heartbeats did not go stale because the plugins are
     * wedged; they went stale because nothing in this JVM ran. Restarting on
     * that evidence takes down every loaded plugin at once, which is exactly
     * what used to happen after a laptop lid was closed.
     *
     * @return true when this tick must be discarded.
     */
    private fun detectStall(
        monotonicElapsed: Long,
        wallClockElapsed: Long,
        nowWallClock: Long,
    ): Boolean {
        val suspendedMs = wallClockElapsed - monotonicElapsed
        val overrunMs = monotonicElapsed - config.heartbeatIntervalMs
        val stalledMs = maxOf(suspendedMs, overrunMs)
        if (stalledMs <= config.stallGraceMs) return false

        // Suppress for a full unhealthy window so the plugins' own heartbeat
        // coroutines - overdue for the same reason - get to run first.
        suppressChecksUntilMs = nowWallClock + config.unhealthyThresholdMs
        healthyChecks = 0
        logger.info(
            LogCategory.SYSTEM,
            "Host stalled, skipping plugin health check",
            mapOf(
                "pluginId" to sandbox.pluginId,
                "stalledMs" to stalledMs,
                "cause" to if (suspendedMs > overrunMs) "host suspended" else "host frozen",
                "resumeChecksInMs" to config.unhealthyThresholdMs,
            ),
        )
        return true
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

        val timeSinceHeartbeat = wallClockMillis() - metrics.lastHeartbeat

        // Check for heartbeat timeout (early return prevents duplicate restart triggers)
        if (timeSinceHeartbeat > config.unhealthyThresholdMs) {
            healthyChecks = 0
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
            healthyChecks = 0
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
            healthyChecks = 0
            logger.debug(
                LogCategory.SYSTEM,
                "Plugin is unhealthy but monitoring",
                mapOf(
                    "pluginId" to sandbox.pluginId,
                    "consecutiveErrors" to metrics.consecutiveErrors,
                ),
            )
            return
        }

        recordHealthyCheck(metrics)
    }

    /**
     * Count a check the plugin passed, and once it has passed enough of them in
     * a row, forgive its earlier restarts.
     *
     * Without this a plugin that hiccups once a week eventually reaches
     * `maxRestartAttempts` and is disabled for a fault it recovered from days
     * ago. The counter is cleared here rather than when a restart returns,
     * because a restart returning is not evidence that anything recovered.
     */
    private fun recordHealthyCheck(metrics: PluginHealthMetrics) {
        healthyChecks++
        if (healthyChecks < config.healthyChecksToClearRestarts || metrics.restartAttempts <= 0) return

        logger.info(
            LogCategory.SYSTEM,
            "Plugin healthy again, clearing restart counter",
            mapOf(
                "pluginId" to sandbox.pluginId,
                "clearedAttempts" to metrics.restartAttempts,
            ),
        )
        sandbox.resetRestartAttempts()
        healthyChecks = 0
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
