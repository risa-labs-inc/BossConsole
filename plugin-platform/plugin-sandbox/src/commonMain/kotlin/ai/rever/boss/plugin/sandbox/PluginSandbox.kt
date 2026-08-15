package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.api.PluginSandboxRef
import ai.rever.boss.plugin.sandbox.health.PluginHealthMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents an isolated execution environment for a plugin.
 *
 * Each plugin runs within its own sandbox, which provides:
 * - Isolated coroutine scope with SupervisorJob (errors don't propagate)
 * - Health monitoring and watchdog
 * - Automatic restart on crash
 * - Error boundary integration for UI components
 *
 * Extends [PluginSandboxRef] to provide the minimal interface that plugins
 * can use for health reporting without depending on the full sandbox module.
 */
interface PluginSandbox : PluginSandboxRef {
    // Note: pluginId, recordHeartbeat(), recordSuccess(), recordError() are inherited from PluginSandboxRef

    /**
     * Current state of the sandbox.
     */
    val state: StateFlow<SandboxState>

    /**
     * Health metrics for the plugin.
     */
    val healthMetrics: StateFlow<PluginHealthMetrics>

    /**
     * Coroutine scope for the sandboxed plugin.
     * All plugin coroutines should use this scope.
     */
    val sandboxScope: CoroutineScope

    /**
     * Start the sandbox and prepare it for plugin execution.
     * @return Result indicating success or failure
     */
    suspend fun start(): Result<Unit>

    /**
     * Stop the sandbox and clean up resources.
     * @return Result indicating success or failure
     */
    suspend fun stop(): Result<Unit>

    /**
     * Restart the sandbox, preserving plugin state if possible.
     * @return Result indicating success or failure
     */
    suspend fun restart(): Result<Unit>

    /**
     * Mark the sandbox as unhealthy.
     * This may trigger a restart depending on configuration.
     */
    fun markUnhealthy()

    /**
     * Reset health metrics after a user-initiated reset.
     * Clears consecutive errors and marks the sandbox as healthy again.
     * Does NOT count as a crash or restart attempt.
     *
     * This clears the restart budget too. It is the user saying "this
     * recovered", and while `restartAttempts` was zeroed by every restart that
     * was moot; now that the counter survives, a plugin sitting at two attempts
     * whose user was told it had been reset would still be one hiccup from
     * being disabled outright.
     */
    fun resetHealth()

    /**
     * Clear the restart counter after the plugin has proven itself healthy
     * again for a sustained period.
     *
     * This is deliberately separate from "the restart call returned": a
     * restart that merely completes says nothing about whether the plugin
     * recovered, and zeroing the counter there makes [SandboxConfig.maxRestartAttempts]
     * unreachable. The watchdog calls this only after
     * [SandboxConfig.healthyChecksToClearRestarts] consecutive healthy checks.
     */
    fun resetRestartAttempts()
}

/**
 * Controls whether a plugin sandbox runs in the same JVM process
 * or as an isolated child process managed by the kernel.
 */
enum class SandboxMode {
    /** Plugin runs in the host JVM (current default behavior). */
    IN_PROCESS,

    /** Plugin runs in a separate child process with IPC bridge. */
    OUT_OF_PROCESS,
}

/**
 * Configuration for a plugin sandbox.
 */
data class SandboxConfig(
    /**
     * Maximum number of threads for the sandbox's thread pool.
     */
    val maxThreads: Int = 2,
    /**
     * Interval in milliseconds between heartbeat checks.
     */
    val heartbeatIntervalMs: Long = 5000,
    /**
     * Threshold in milliseconds for considering a plugin unresponsive.
     */
    val unhealthyThresholdMs: Long = 15000,
    /**
     * Maximum number of consecutive errors before marking unhealthy.
     */
    val maxConsecutiveErrors: Int = 5,
    /**
     * Maximum number of restart attempts before disabling the plugin.
     */
    val maxRestartAttempts: Int = 3,
    /**
     * Base delay in milliseconds for restart backoff.
     */
    val restartBackoffBaseMs: Long = 1000,
    /**
     * Maximum delay in milliseconds for restart backoff.
     */
    val restartBackoffMaxMs: Long = 30000,
    /**
     * How far the watchdog's own check loop may overrun [heartbeatIntervalMs]
     * before that tick is discarded as evidence about plugin health.
     *
     * Heartbeat age is wall-clock, so anything that freezes the whole process
     * - the machine sleeping, a long GC pause, a debugger breakpoint - ages
     * every plugin's heartbeat at once while the plugins themselves are doing
     * nothing wrong. The watchdog's loop is frozen by the same thing, and that
     * overrun is the signal used to tell the two apart.
     */
    val stallGraceMs: Long = 2000,
    /**
     * Consecutive healthy checks a plugin must pass before its restart counter
     * is cleared. At the default interval this is a minute of good behaviour.
     */
    val healthyChecksToClearRestarts: Int = 12,
    /**
     * Ticks in a row that may skip the health check, before one runs anyway.
     *
     * Counted in ticks, so its wall-clock meaning is entirely
     * [heartbeatIntervalMs] - which is why it belongs here rather than in a
     * constant. A plugin declaring a 500ms interval would get a 3 second
     * ceiling on suppression from a hardcoded 6; one declaring 60s would get
     * six minutes of a watchdog that is not watching.
     */
    val maxSkippedChecks: Int = 6,
)
