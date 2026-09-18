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
    /**
     * The kernel's heartbeat bookkeeping, or null when there is none to consult.
     *
     * Liveness alone cannot see a child that is alive but wedged - deadlocked, event loop
     * stalled, IPC reader dead - because none of those end the process. With a watch, the
     * per-process check below kills such a child and reports [FailureReason.HEARTBEAT_TIMEOUT]
     * for the existing failure path to recover from; without one, supervision stays
     * liveness-only, which is the old behaviour.
     */
    private val heartbeatWatch: HeartbeatWatch? = null,
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
     * [ProcessType.PLUGIN] is not health-supervised here. Plugin health is `PluginProcessMonitor`'s
     * responsibility on the host side - note that it is written but **not yet wired up**, so today
     * a crashed out-of-process plugin gets no restart and no in-process fallback from anywhere.
     * Supervising plugins from here is still the wrong answer to that: a plugin the operator
     * disables exits on purpose, which would read as a crash and come back through the kernel's
     * respawn path. Plugins are registered regardless, because the registry is what the shutdown
     * hook reaps.
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

        // A missed beat is a blip; HEARTBEAT_TIMEOUT_INTERVALS of silence from a live child is
        // its heartbeat loop no longer running, which no liveness check can detect.
        val heartbeatTimeoutMs = checkIntervalMs * HEARTBEAT_TIMEOUT_INTERVALS

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

            // Alive is not healthy. A wedged child keeps its process slot and stalls everything
            // waiting on it, and only the kernel's heartbeat record can tell it from a healthy
            // one - the check the self-healing design wrote but never wired up. No watch
            // injected, no check: a registry with no heartbeat source keeps the old
            // liveness-only behaviour.
            val watch = heartbeatWatch
            if (watch != null && watch.isHeartbeatTimedOut(processId, heartbeatTimeoutMs)) {
                killWedged(watch, process, heartbeatTimeoutMs)
                break
            }

            delay(checkIntervalMs)
        }
    }

    /**
     * Tear down a child that is alive but has stopped heartbeating, and report it as such.
     *
     * The beat is forgotten before the kill: it belongs to the dying generation, and left
     * behind it would let the replacement this failure respawns be judged - and killed - for
     * a beat it never sent. destroyForcibly rather than destroy, because a child wedged enough
     * to be here cannot honour a graceful shutdown; that unresponsiveness is the whole reason
     * it is being killed.
     */
    private suspend fun killWedged(
        watch: HeartbeatWatch,
        process: ManagedProcess,
        heartbeatTimeoutMs: Long,
    ) {
        val processId = process.config.processId
        logger.warn(
            "Process {} (pid={}) is alive but has not heartbeaten for over {}ms - killing it as wedged",
            processId,
            process.pid,
            heartbeatTimeoutMs,
        )

        watch.forget(processId)
        process.updateState(ProcessState.PROCESS_STATE_CRASHED)
        runCatching { process.destroyForcibly() }
            .onFailure {
                logger.warn("Failed to kill wedged process {}: {}", processId, it.message)
            }

        _failures.emit(
            ProcessFailure(
                processId = processId,
                reason = FailureReason.HEARTBEAT_TIMEOUT,
                errorMessage = "Alive but no heartbeat for over $heartbeatTimeoutMs ms (wedged)",
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        /**
         * How many heartbeat intervals of silence from an otherwise-alive child before it is
         * declared wedged. Children send a beat every `heartbeatIntervalMs`, so this bounds the
         * stall at three beats: long enough that one missed beat or a long GC pause is not read
         * as a wedge, short enough that recovery starts while the operator is still watching.
         */
        const val HEARTBEAT_TIMEOUT_INTERVALS = 3L

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

/**
 * The kernel's heartbeat bookkeeping, as [ProcessMonitor] needs it.
 *
 * Behind an interface so this module stays independent of where beats are recorded: the
 * kernel's `KernelServiceImpl` tracks them as children connect, and hands its adapter to the
 * monitor at construction. No watch means liveness-only supervision.
 */
interface HeartbeatWatch {
    /**
     * Whether [processId] has been silent for longer than [thresholdMs].
     *
     * A process with no recorded beat at all is *not* timed out: it is either still starting
     * up or not an IPC child, and killing either would turn the sweep into a hazard no
     * liveness evidence could justify.
     */
    fun isHeartbeatTimedOut(
        processId: String,
        thresholdMs: Long,
    ): Boolean

    /**
     * Forget [processId]'s recorded beat.
     *
     * Called as a wedged child is torn down, so the replacement the failure respawns is judged
     * on its own beats rather than its predecessor's last one.
     */
    fun forget(processId: String)
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
