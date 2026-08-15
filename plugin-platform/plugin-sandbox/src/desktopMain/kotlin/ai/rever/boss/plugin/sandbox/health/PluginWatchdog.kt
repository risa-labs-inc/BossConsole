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
    @Volatile
    private var healthyChecks = 0

    // Deadline until which health checks are suppressed after a process-wide
    // stall, giving every plugin's heartbeat coroutine - frozen by the same
    // stall - a full unhealthy window to run again and prove liveness.
    //
    // On the MONOTONIC clock, deliberately. A wall-clock deadline is kept on
    // the one clock this guard exists to distrust: an NTP correction on wake
    // can step currentTimeMillis backwards, and an hour of correction is an
    // hour in which no check for this plugin ever runs again - a genuinely
    // wedged plugin never restarted, with one "Host stalled" line an hour
    // earlier as the only trace. It also behaves better across a second
    // suspend, since a monotonic deadline does not advance while the machine
    // is asleep, so the grace covers the resume instead of being spent on it.
    // NO_SUPPRESSION rather than 0 because monotonic values are not anchored
    // anywhere in particular.
    @Volatile
    private var suppressChecksUntilMonotonic = NO_SUPPRESSION

    // Consecutive ticks discarded as host stalls. Bounded, because a host that
    // is chronically slow rather than briefly frozen would otherwise re-arm the
    // suppression on every tick and mute this watchdog for the life of the
    // process - no checks, no escalation, one INFO line per tick as the only
    // trace. Sustained scheduler starvation is a condition a watchdog exists to
    // notice, so past the bound it checks anyway and degrades to late
    // detection rather than none.
    @Volatile
    private var consecutiveStalls = 0

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Sentinel for "no stall recovery in progress". */
        const val NO_SUPPRESSION = Long.MIN_VALUE

        /** Stalled ticks in a row before checks resume regardless. */
        const val MAX_CONSECUTIVE_STALLS = 6
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
                while (isActive) {
                    // Sampled either side of the sleep and nowhere else, so the
                    // measurement covers the sleep alone. Measuring tick to tick
                    // instead folded in whatever checkHealth() had just done -
                    // and it can do a lot: a restart it triggers waits out an
                    // exponential backoff (up to restartBackoffMaxMs) and then
                    // blocks in awaitTermination. That reliably overran the
                    // grace, so every restart of a genuinely wedged plugin
                    // logged a bogus "host frozen" and then suppressed checks
                    // for a full unhealthy window, slowing down the one case
                    // this watchdog is actually for.
                    val beforeMonotonic = monotonicMillis()
                    val beforeWallClock = wallClockMillis()
                    delay(config.heartbeatIntervalMs)
                    val nowMonotonic = monotonicMillis()
                    val nowWallClock = wallClockMillis()

                    val stalled =
                        suppressIfHostStalled(
                            monotonicElapsed = nowMonotonic - beforeMonotonic,
                            wallClockElapsed = nowWallClock - beforeWallClock,
                            nowMonotonic = nowMonotonic,
                        )
                    if (!stalled && nowMonotonic >= suppressChecksUntilMonotonic) {
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
     * Named for its side effect: a detected stall also suppresses the checks
     * that follow it, for one unhealthy window.
     *
     * @return true when this tick must be discarded.
     */
    private fun suppressIfHostStalled(
        monotonicElapsed: Long,
        wallClockElapsed: Long,
        nowMonotonic: Long,
    ): Boolean {
        val suspendedMs = wallClockElapsed - monotonicElapsed
        val overrunMs = monotonicElapsed - config.heartbeatIntervalMs
        val stalledMs = maxOf(suspendedMs, overrunMs)
        val stalled = stalledMs > config.stallGraceMs
        consecutiveStalls = if (stalled) consecutiveStalls + 1 else 0

        return when {
            !stalled -> false
            consecutiveStalls > MAX_CONSECUTIVE_STALLS -> resumeDespiteStall(stalledMs)
            else -> beginStallSuppression(nowMonotonic, stalledMs, suspendedMs > overrunMs)
        }
    }

    /**
     * Arm the recovery window, so the plugins' own heartbeat coroutines -
     * overdue for the same reason - get to run before anything is judged.
     *
     * @return true, so the caller discards this tick.
     */
    private fun beginStallSuppression(
        nowMonotonic: Long,
        stalledMs: Long,
        suspended: Boolean,
    ): Boolean {
        suppressChecksUntilMonotonic = nowMonotonic + config.unhealthyThresholdMs
        healthyChecks = 0
        logger.info(
            LogCategory.SYSTEM,
            "Host stalled, skipping plugin health check",
            mapOf(
                "pluginId" to sandbox.pluginId,
                "stalledMs" to stalledMs,
                "cause" to if (suspended) "host suspended" else "host frozen",
                "resumeChecksInMs" to config.unhealthyThresholdMs,
            ),
        )
        return true
    }

    /**
     * Give up on waiting for a chronically slow host and check anyway.
     *
     * @return false, so the caller treats the tick as usable.
     */
    private fun resumeDespiteStall(stalledMs: Long): Boolean {
        logger.warn(
            LogCategory.SYSTEM,
            "Host still stalling, checking plugin health anyway",
            mapOf(
                "pluginId" to sandbox.pluginId,
                "consecutiveStalls" to consecutiveStalls,
                "stalledMs" to stalledMs,
            ),
        )
        suppressChecksUntilMonotonic = NO_SUPPRESSION
        return false
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
        // Bookkeeping that only means anything within one monitoring run.
        // enablePlugin builds a fresh watchdog today, so nothing inherits these
        // in practice, but leaving a half-finished healthy streak behind for a
        // restarted instance to bank is not state worth keeping.
        healthyChecks = 0
        suppressChecksUntilMonotonic = NO_SUPPRESSION
        consecutiveStalls = 0
    }

    private suspend fun checkHealth() {
        val metrics = sandbox.healthMetrics.value
        val currentState = sandbox.state.value

        // Skip checks if the sandbox is stopped, restarting or disabled.
        // DISABLED matters because recordError sets it for binary
        // incompatibility WITHOUT stopping this watchdog, and a plugin the host
        // has given up on should not be having its restart budget forgiven.
        if (currentState == SandboxState.STOPPED ||
            currentState == SandboxState.RESTARTING ||
            currentState == SandboxState.DISABLED
        ) {
            // Reset like every other non-healthy branch, so a streak cannot
            // span a restart and mean something other than "consecutive
            // healthy checks".
            healthyChecks = 0
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
        if (healthyChecks < config.healthyChecksToClearRestarts) return
        // Banked before the restartAttempts test, not after. Returning early on
        // a zero counter while leaving the streak running let a long-healthy
        // plugin accumulate an unbounded one, so a restart arriving by a path
        // that does not reset it - the error boundary's own Restart button -
        // was forgiven by the very next check. "Cleared after a sustained run"
        // then would not have been true.
        healthyChecks = 0
        if (metrics.restartAttempts <= 0) return

        logger.info(
            LogCategory.SYSTEM,
            "Plugin healthy again, clearing restart counter",
            mapOf(
                "pluginId" to sandbox.pluginId,
                "clearedAttempts" to metrics.restartAttempts,
            ),
        )
        sandbox.resetRestartAttempts()
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

            // The restart budget is deliberately NOT checked here. This used to
            // hold a copy of the check in PluginSandboxManager.handleRestartRequest,
            // and because this one runs first, the manager's was unreachable -
            // which did not matter while restartAttempts was zeroed by every
            // restart and neither could fire.
            //
            // Making the counter survive a restart made the branch live, and the
            // copy that won was the worse one: it stopped the sandbox without
            // calling setDisabled(), so the state landed on STOPPED, and
            // PluginErrorBoundary only synthesises its fallback UI for DISABLED.
            // The plugin kept rendering normally over a dead scope, isPluginDisabled()
            // stayed false, and no notification was raised - the exact
            // looks-alive-does-nothing state this watchdog change exists to
            // remove, arrived at from the other side. The manager's version
            // disables the sandbox, records it and notifies listeners, so the
            // decision belongs there and only there.
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
