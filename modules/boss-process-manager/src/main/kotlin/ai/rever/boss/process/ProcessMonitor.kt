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

    // One live monitor per process id. The lock makes startMonitoring's check-and-register and
    // the stop paths atomic against each other; see startMonitoring for what breaks without it.
    private val monitorJobs = ConcurrentHashMap<String, Job>()
    private val monitorJobsLock = Any()
    private var globalMonitorJob: Job? = null

    /**
     * Start monitoring a specific process.
     *
     * The check-and-register step is atomic under the monitor-jobs lock: the global monitor
     * loop's re-attach pass and a spawn's own start call race for the same id at boot, and two
     * unsynchronized launches would leave one coroutine untracked in the job map - invisible to
     * [stopMonitoring] and [stopSupervision], uncancellable, and a second reporter of the same
     * death. Two failure emissions make the kernel respawn twice, which is what evicts a live
     * child from the registry and orphans it. Invariant: at most one live monitor per process
     * id, and every live monitor is tracked.
     *
     * Each monitor deregisters itself on completion. That deregistration is identity-guarded,
     * so a finished monitor's cleanup can never remove a successor's registration.
     */
    fun startMonitoring(processId: String) {
        synchronized(monitorJobsLock) {
            val existing = monitorJobs[processId]
            if (existing?.isActive == true) return

            monitorJobs[processId] =
                scope.launch {
                    val self = currentCoroutineContext().job
                    try {
                        monitorProcess(processId)
                    } finally {
                        // Identity-guarded: only this monitor's own registration is removed.
                        monitorJobs.remove(processId, self)
                    }
                }
        }
        logger.info("Started monitoring process: {}", processId)
    }

    /**
     * Stop monitoring a specific process.
     *
     * The remove runs under the same lock as [startMonitoring]'s check-and-register, so a stop
     * cannot race a start into leaving a second monitor behind for this id.
     */
    fun stopMonitoring(processId: String) {
        val job = synchronized(monitorJobsLock) { monitorJobs.remove(processId) }
        job?.cancel()
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
                        // A death already reported stays in the registry until its replacement is
                        // spawned; re-attaching would only watch the same dead handle (#1612).
                        if (process.state.value == ProcessState.PROCESS_STATE_CRASHED) return@forEach
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
     * What is serialized against [startMonitoring] is the snapshot-and-clear of the job map: it
     * runs under the monitor-jobs lock, so every monitor registered before it is captured and
     * cancelled. The `cancel()` calls themselves run after the lock is released, and cancellation
     * is cooperative, so a global-monitor pass already in flight can still register one more
     * monitor after the clear; that monitor is tracked in the map like any other, and a later
     * [stopMonitoring] or [stopSupervision] cancels it. (#1612: the previous wording claimed
     * cancel-all and clear were atomic together, which they are not.)
     *
     * This is what a caller that *passed in* its own scope wants: [stopAll] cancels that scope,
     * which for `KernelBootstrap` means taking down its IPC event bridge and failure-handler
     * collector as a side effect of stopping monitoring.
     */
    fun stopSupervision() {
        globalMonitorJob?.cancel()
        val tracked =
            synchronized(monitorJobsLock) {
                val jobs = monitorJobs.values.toList()
                monitorJobs.clear()
                jobs
            }
        tracked.forEach { it.cancel() }
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
                // One report per death: a monitor that re-attached to an already-reported dead
                // handle stops here instead of emitting a duplicate the kernel would act on
                // twice - a second eviction and respawn racing the first one's replacement.
                if (!process.claimFailureReport()) break
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
