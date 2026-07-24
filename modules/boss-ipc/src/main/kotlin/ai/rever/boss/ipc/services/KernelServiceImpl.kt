package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * gRPC implementation of the KernelService.
 *
 * This runs in the kernel process and handles:
 * - Child process registration
 * - Heartbeat monitoring
 * - Process status queries
 * - Shutdown requests
 */
class KernelServiceImpl(
    private val onProcessRegistered: suspend (String, ProcessManifest, String) -> Unit = { _, _, _ -> },
    private val onShutdownRequested: suspend (String, Boolean) -> Boolean = { _, _ -> true },
) : KernelServiceGrpcKt.KernelServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(KernelServiceImpl::class.java)

    // Track registered processes and their IPC addresses
    private val registeredProcesses = ConcurrentHashMap<String, RegisteredProcessInfo>()

    // Heartbeat tracking
    private val lastHeartbeats = ConcurrentHashMap<String, Long>()

    override suspend fun registerProcess(request: RegisterProcessRequest): RegisterProcessResponse {
        val manifest = request.manifest
        val processId = manifest.processId

        logger.info(
            "Process registering: id={}, type={}, name={}, ipc={}",
            processId,
            manifest.processType,
            manifest.displayName,
            request.ipcAddress,
        )

        registeredProcesses[processId] =
            RegisteredProcessInfo(
                manifest = manifest,
                ipcAddress = request.ipcAddress,
                registeredAt = System.currentTimeMillis(),
            )
        lastHeartbeats[processId] = System.currentTimeMillis()

        // Notify the kernel's process registry
        try {
            onProcessRegistered(processId, manifest, request.ipcAddress)
        } catch (e: Exception) {
            logger.error("Error in process registration callback for {}", processId, e)
            return RegisterProcessResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("Registration callback failed: ${e.message}")
                .build()
        }

        // Build service address map for the child process
        val serviceAddresses =
            registeredProcesses
                .filter { it.key != processId }
                .mapValues { it.value.ipcAddress }

        logger.info("Process registered successfully: id={}", processId)

        return RegisterProcessResponse
            .newBuilder()
            .setSuccess(true)
            .setAssignedProcessId(processId)
            .putAllServiceAddresses(serviceAddresses)
            .build()
    }

    override fun heartbeat(requests: Flow<HeartbeatPing>): Flow<HeartbeatPong> =
        flow {
            requests.collect { ping ->
                val processId = ping.processId
                lastHeartbeats[processId] = System.currentTimeMillis()

                // Update metrics if provided
                if (ping.hasMetrics()) {
                    registeredProcesses[processId]?.lastMetrics?.set(ping.metrics)
                }

                emit(
                    HeartbeatPong
                        .newBuilder()
                        .setProcessId(processId)
                        .setTimestamp(System.currentTimeMillis())
                        .setAcknowledged(true)
                        .build(),
                )
            }
        }

    override suspend fun requestShutdown(request: ShutdownRequest): ShutdownResponse {
        val processId = request.processId
        logger.info(
            "Shutdown requested for process: id={}, force={}, reason={}",
            processId,
            request.force,
            request.reason,
        )

        val success =
            try {
                onShutdownRequested(processId, request.force)
            } catch (e: Exception) {
                logger.error("Error shutting down process {}", processId, e)
                false
            }

        if (success) {
            registeredProcesses.remove(processId)
            lastHeartbeats.remove(processId)
        }

        return ShutdownResponse
            .newBuilder()
            .setSuccess(success)
            .build()
    }

    override suspend fun getProcessStatus(request: ProcessStatusRequest): ProcessStatusResponse {
        val processId = request.processId
        val info =
            registeredProcesses[processId]
                ?: return ProcessStatusResponse
                    .newBuilder()
                    .setProcessId(processId)
                    .setState(ProcessState.PROCESS_STATE_STOPPED)
                    .build()

        return ProcessStatusResponse
            .newBuilder()
            .setProcessId(processId)
            .setState(ProcessState.PROCESS_STATE_RUNNING)
            .setStartTime(info.registeredAt)
            .apply {
                info.lastMetrics.get()?.let { setMetrics(it) }
                lastHeartbeats[processId]?.let { /* timestamp tracked internally */ }
            }.build()
    }

    override suspend fun listProcesses(request: Empty): ListProcessesResponse {
        val statuses =
            registeredProcesses.map { (id, info) ->
                ProcessStatusResponse
                    .newBuilder()
                    .setProcessId(id)
                    .setState(ProcessState.PROCESS_STATE_RUNNING)
                    .setStartTime(info.registeredAt)
                    .apply { info.lastMetrics.get()?.let { setMetrics(it) } }
                    .build()
            }

        return ListProcessesResponse
            .newBuilder()
            .addAllProcesses(statuses)
            .build()
    }

    /**
     * Get the last heartbeat timestamp for a process.
     * Returns null if the process has never sent a heartbeat.
     */
    fun getLastHeartbeat(processId: String): Long? = lastHeartbeats[processId]

    /**
     * Check if a process has timed out (no heartbeat within threshold).
     */
    fun isHeartbeatTimedOut(
        processId: String,
        thresholdMs: Long,
    ): Boolean {
        val lastBeat = lastHeartbeats[processId] ?: return true
        return System.currentTimeMillis() - lastBeat > thresholdMs
    }

    /**
     * Get count of registered processes.
     */
    val registeredCount: Int get() = registeredProcesses.size
}

internal data class RegisteredProcessInfo(
    val manifest: ProcessManifest,
    val ipcAddress: String,
    val registeredAt: Long,
    val lastMetrics: AtomicReference<ProcessHealthMetrics?> = AtomicReference(null),
)
