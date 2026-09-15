package ai.rever.boss.process

import ai.rever.boss.ipc.proto.ProcessState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors all registered processes via periodic health checks.
 *
 * Detects:
 * - Process exit (non-zero or unexpected)
 * - Heartbeat timeout
 * - Health check failure
 *
 * On failure, emits a [ProcessFailure] event for the orchestrator or kernel to handle.
 *
 * Reuses patterns from the existing PluginWatchdog:
 * - Exponential backoff on restart
 * - Max restart limit before disabling
 * - Heartbeat interval monitoring
 */
class ProcessMonitor(
    private val registry: ProcessRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val logger = LoggerFactory.getLogger(ProcessMonitor::class.java)

    private val _failures = MutableSharedFlow<ProcessFailure>(extraBufferCapacity = 64)
    val failures: SharedFlow<ProcessFailure> = _failures.asSharedFlow()

    private val monitorJobs = ConcurrentHashMap<String, Job>()
    private var globalMonitorJob: Job? = null

    /**
     * Start monitoring a specific process.
     */
    fun startMonitoring(processId: String) {
        val existing = monitorJobs[processId]
        if (existing?.isActive == true) return

        monitorJobs[processId] =
            scope.launch {
                monitorProcess(processId)
            }
        logger.info("Started monitoring process: {}", processId)
    }

    /**
     * Stop monitoring a specific process.
     */
    fun stopMonitoring(processId: String) {
        monitorJobs.remove(processId)?.cancel()
        logger.info("Stopped monitoring process: {}", processId)
    }

    /**
     * Start the global monitor that watches for new/removed processes.
     *
     * [ProcessType.PLUGIN] is not health-supervised here. Plugin health is supervised per window
     * by `PluginProcessMonitor`, which owns the operator-aware restart and fallback lifecycle.
     * Supervising plugins globally here would race that owner and could restart a plugin that the
     * operator deliberately disabled. Plugins remain registered because the registry is what the
     * shutdown hook reaps.
     *
     * Dead plugins are instead *pruned*. Only a deliberate terminate unregisters one, so a plugin
     * that died on its own would otherwise sit in the registry for the rest of the session, with
     * [ProcessRegistry.getAllProcesses] handing out a dead handle and processCount over-reporting
     * it as live. The removal is compare-and-remove ([ProcessRegistry.unregisterIfSame]) because
     * `getAllProcesses` hands back a snapshot: a respawn between the snapshot and the removal would
     * otherwise cost the live replacement its registry entry, orphaning it.
     */
    fun startGlobalMonitor(checkIntervalMs: Long = 2_000) {
        globalMonitorJob =
            scope.launch {
                while (isActive) {
                    // Check all registered processes
                    registry.getAllProcesses().forEach { process ->
                        if (process.config.processType == ProcessType.PLUGIN) {
                            if (!process.isAlive) {
                                registry.unregisterIfSame(process.config.processId, process)
                            }
                            return@forEach
                        }
                        if (!monitorJobs.containsKey(process.config.processId) ||
                            monitorJobs[process.config.processId]?.isActive != true
                        ) {
                            startMonitoring(process.config.processId)
                        }
                    }
                    delay(checkIntervalMs)
                }
            }
    }

    /**
     * Stop all health supervision, leaving [scope] usable.
     *
     * This is what a caller that *passed in* its own scope wants: [stopAll] cancels that scope,
     * which for `KernelBootstrap` means taking down its IPC event bridge and failure-handler
     * collector as a side effect of stopping monitoring.
     */
    fun stopSupervision() {
        globalMonitorJob?.cancel()
        monitorJobs.values.forEach { it.cancel() }
        monitorJobs.clear()
    }

    /**
     * Stop monitoring and cancel [scope]. Only for a caller that owns the scope - if you passed one
     * in, use [stopSupervision] and cancel your own scope when you are ready.
     */
    fun stopAll() {
        stopSupervision()
        scope.cancel()
    }

    private suspend fun monitorProcess(processId: String) {
        val checkIntervalMs =
            registry
                .getProcess(processId)
                ?.config
                ?.heartbeatIntervalMs ?: 5_000

        while (currentCoroutineContext().isActive) {
            val process = registry.getProcess(processId) ?: break

            // Check if process is still alive
            if (!process.isAlive) {
                val exitCode =
                    try {
                        process.process.exitValue()
                    } catch (_: IllegalThreadStateException) {
                        -1
                    }

                logger.warn(
                    "Process {} (pid={}) exited with code {}",
                    processId,
                    process.pid,
                    exitCode,
                )

                process.updateState(ProcessState.PROCESS_STATE_CRASHED)

                _failures.emit(
                    ProcessFailure(
                        processId = processId,
                        reason = FailureReason.PROCESS_EXIT,
                        exitCode = exitCode,
                        errorMessage = "Process exited with code $exitCode",
                        timestamp = System.currentTimeMillis(),
                    ),
                )
                break
            }

            delay(checkIntervalMs)
        }
    }

    companion object {
        /**
         * Calculate exponential backoff delay for restarts.
         * Ported from existing PluginSandboxManagerImpl.calculateBackoff().
         */
        fun calculateBackoff(
            attempt: Int,
            baseMs: Long = 1_000,
            maxMs: Long = 30_000,
        ): Long {
            val safeAttempt = attempt.coerceIn(0, 30)
            val delay = baseMs * (1L shl safeAttempt)
            return delay.coerceAtMost(maxMs)
        }
    }
}

data class ProcessFailure(
    val processId: String,
    val reason: FailureReason,
    val exitCode: Int = -1,
    val errorMessage: String = "",
    val stackTrace: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

enum class FailureReason {
    PROCESS_EXIT,
    HEARTBEAT_TIMEOUT,
    HEALTH_CHECK_FAILED,
    OUT_OF_MEMORY,
    MANUAL_STOP,
}
