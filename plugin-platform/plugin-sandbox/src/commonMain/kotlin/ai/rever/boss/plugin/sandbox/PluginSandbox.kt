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
     */
    fun resetHealth()
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
    val restartBackoffMaxMs: Long = 30000
)
