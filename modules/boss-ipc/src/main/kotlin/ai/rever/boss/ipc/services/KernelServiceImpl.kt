package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.IpcLogText
import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.flow.Flow
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
 * - Capability mediation between child processes (#1061)
 */

/** Signature of the host-wired broker that invokes a registered process's capability. */
private typealias CapabilityBroker = suspend (InvokeCapabilityRequest) -> InvokeCapabilityResponse

@Suppress("TooManyFunctions") // registration, heartbeat, status, shutdown and mediation share one process table.
class KernelServiceImpl(
    private val onProcessRegistered: suspend (String, ProcessManifest, String) -> Unit = { _, _, _ -> },
    private val onShutdownRequested: suspend (String, Boolean) -> Boolean = { _, _ -> true },
    /**
     * Broker a capability invocation on a registered process, exactly as the host
     * kernel wires it: look the process up in the registry RegisterProcess populates
     * and dial it over that process's IPC client. Children cannot reach each other
     * directly, so this callback is the only road between two child processes
     * (#1061). Unwired kernels fail closed.
     */
    private val onCapabilityInvocation: CapabilityBroker = ::unwiredCapabilityInvocation,
) : KernelServiceGrpcKt.KernelServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(KernelServiceImpl::class.java)

    // Track registered processes and their IPC addresses
    private val registeredProcesses = ConcurrentHashMap<String, RegisteredProcessInfo>()

    // Heartbeat tracking
    private val lastHeartbeats = ConcurrentHashMap<String, Long>()

    override suspend fun registerProcess(request: RegisterProcessRequest): RegisterProcessResponse {
        val manifest = request.manifest
        val processId = manifest.processId
        val caller = IpcCall.requireOwnProcess(processId)
        IpcCall.requirePermission(caller.expectedAddress != null && request.ipcAddress == caller.expectedAddress)

        // The display name is free text from the child's own manifest: neutralized so a hostile
        // name cannot forge kernel log records. The manifest the registry stores is untouched.
        logger.info(
            "Process registering: id={}, type={}, name={}, ipc={}",
            processId,
            manifest.processType,
            IpcLogText.neutralize(manifest.displayName),
            request.ipcAddress,
        )

        // Notify the kernel's process registry
        try {
            onProcessRegistered(processId, manifest, request.ipcAddress)
        } catch (e: Exception) {
            logger.error("Error in process registration callback for {}", processId, e)
            return RegisterProcessResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("Registration callback failed")
                .build()
        }

        IpcCall.requireOwnProcess(processId)
        registeredProcesses[processId] =
            RegisteredProcessInfo(
                manifest = manifest,
                ipcAddress = request.ipcAddress,
                registeredAt = System.currentTimeMillis(),
            )
        lastHeartbeats[processId] = System.currentTimeMillis()

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
                IpcCall.requireOwnProcess(processId)
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
        IpcCall.requireProcessControl(processId)
        // A supervisor may name any target, so the id that reaches the log is caller-supplied text.
        logger.info(
            "Shutdown requested for process: id={}, force={}",
            IpcLogText.neutralize(processId),
            request.force,
        )

        val success =
            try {
                onShutdownRequested(processId, request.force)
            } catch (e: Exception) {
                logger.error("Error shutting down process {}", IpcLogText.neutralize(processId), e)
                false
            }

        if (success) {
            evictProcess(processId)
        }

        return ShutdownResponse
            .newBuilder()
            .setSuccess(success)
            .build()
    }

    /**
     * Deregister a process that died without a clean shutdown - the crash path the kernel's
     * failure handling reports on the host side (KernelBootstrap.handleFailure).
     *
     * Registration is otherwise removed only on a successful [requestShutdown], so a crashed
     * id would stay in the tables for the rest of the session: [getProcessStatus] and
     * [listProcesses] keep stamping it RUNNING and [registerProcess] keeps handing its stale
     * [RegisteredProcessInfo.ipcAddress] to every later child. Evicting here keeps
     * "registered" equivalent to "live" for this table.
     *
     * Call before spawning a replacement: a respawn re-registers the same id, and evicting
     * after that would drop the live child's entries instead of the dead one's.
     *
     * [registeredBefore] makes the eviction compare-and-remove (#1612): only an entry registered
     * before that instant is dropped. The failure path passes the moment the death was observed,
     * so a replacement that registered after it - for instance while a duplicate report of the
     * same death was still being handled - keeps its registration. Registration time is the
     * identity that works here: the ipcAddress is derived from the process type and id alone, so
     * the dead child and its replacement share it. The heartbeat entry is guarded the same way.
     *
     * @return true if the id was registered before [registeredBefore] and its entries were dropped.
     */
    fun deregisterProcess(
        processId: String,
        registeredBefore: Long = Long.MAX_VALUE,
    ): Boolean {
        val evicted = evictProcess(processId, registeredBefore)
        if (evicted) {
            logger.info("Deregistered process after failure: id={}", processId)
        }
        return evicted
    }

    /**
     * Single eviction site for both deregistration paths: the clean [requestShutdown] flow
     * and the crash path via [deregisterProcess].
     *
     * Compare-and-remove on [registeredBefore] (#1612): only a registration older than it is
     * dropped, and only a heartbeat older than it. The default, [Long.MAX_VALUE], drops both
     * unconditionally - [requestShutdown]'s behaviour, unchanged. The heartbeat is guarded on its
     * own rather than only after an eviction because [heartbeat] records a timestamp for any
     * authenticated process, registered or not, and the shutdown path has always cleared it.
     *
     * @return true if a registration was dropped.
     */
    private fun evictProcess(
        processId: String,
        registeredBefore: Long = Long.MAX_VALUE,
    ): Boolean {
        var evicted = false
        registeredProcesses.computeIfPresent(processId) { _, info ->
            if (info.registeredAt < registeredBefore) {
                evicted = true
                null
            } else {
                info
            }
        }
        lastHeartbeats.computeIfPresent(processId) { _, last -> if (last < registeredBefore) null else last }
        return evicted
    }

    override suspend fun getProcessStatus(request: ProcessStatusRequest): ProcessStatusResponse {
        val processId = request.processId
        IpcCall.requireProcessControl(processId)
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
        val caller = IpcCall.current()
        val statuses =
            registeredProcesses
                .filterKeys {
                    it == caller.processId || caller.authority != ProcessAuthority.PROCESS
                }.map { (id, info) ->
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

    override suspend fun invokeCapability(request: InvokeCapabilityRequest): InvokeCapabilityResponse {
        requireCapabilityCaller()
        // Admission (#1061): the requested action is checked against the registered process's
        // own manifest before anything dispatches, from the same table [listCapabilities]
        // advertises, so the set the kernel dispatches can never be wider than the set it
        // advertises.
        val refusal = capabilityAdmissionRefusal(request)
        if (refusal != null) {
            return refuseCapabilityInvocation(refusal)
        }
        return onCapabilityInvocation(request)
    }

    /**
     * The admission verdict for one capability invocation: `null` admits it, anything else
     * is the fail-closed refusal reason, taken from the process's own registered manifest.
     * The registry entry is read once, so the presence check and the broker's own lookup
     * cannot race a deregistration.
     */
    private fun capabilityAdmissionRefusal(request: InvokeCapabilityRequest): String? {
        val info = registeredProcesses[request.pluginId]
        return when {
            info == null -> {
                "Process not found: ${request.pluginId}"
            }

            info.manifest.capabilitiesList.none { it.action == request.action } -> {
                "Plugin ${request.pluginId} does not advertise capability: ${request.action}"
            }

            else -> {
                null
            }
        }
    }

    /**
     * Refuse one capability invocation before the broker is consulted: an admission failure
     * never falls through to dispatch. The reason states the kernel's own fail-closed cause,
     * and both ids in it are caller-supplied text, so the copy that reaches the kernel log is
     * neutralized while the response keeps the original for the authenticated caller.
     */
    private fun refuseCapabilityInvocation(reason: String): InvokeCapabilityResponse {
        logger.warn("Capability invocation refused: {}", IpcLogText.neutralize(reason))
        return InvokeCapabilityResponse
            .newBuilder()
            .setSuccess(false)
            .setErrorMessage(reason)
            .build()
    }

    override suspend fun listCapabilities(request: Empty): ListCapabilitiesResponse {
        requireCapabilityCaller()
        val descriptors =
            registeredProcesses.values.flatMap { info ->
                info.manifest.capabilitiesList.map { capability ->
                    CapabilityDescriptor
                        .newBuilder()
                        .setPluginId(info.manifest.processId)
                        .setAction(capability.action)
                        .setDescription(capability.description)
                        .setInputSchemaJson(capability.inputSchemaJson)
                        .setOutputSchemaJson(capability.outputSchemaJson)
                        .build()
                }
            }
        return ListCapabilitiesResponse
            .newBuilder()
            .addAllCapabilities(descriptors)
            .build()
    }

    /**
     * Capability composition is kernel-brokered: the caller must be the host, a
     * supervisor, or a process that completed its own registration. Anyone else - an
     * issued token that never registered, a stranger - learns nothing about the
     * registered processes and invokes nothing through them. Capability descriptors
     * are advertised to every admitted caller because composing other processes'
     * capabilities is what the Mastery orchestrator is for; what a capability
     * actually does remains the offering process's own decision.
     */
    private fun requireCapabilityCaller() {
        val caller = IpcCall.current()
        val isRegisteredProcess = registeredProcesses.containsKey(caller.processId)
        IpcCall.requirePermission(
            caller.authority != ProcessAuthority.PROCESS || isRegisteredProcess,
        )
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

/** An unwired kernel fails closed: it never reports another process's capability as done. */
private suspend fun unwiredCapabilityInvocation(request: InvokeCapabilityRequest): InvokeCapabilityResponse =
    InvokeCapabilityResponse
        .newBuilder()
        .setSuccess(false)
        .setErrorMessage("Capability invocation is not configured on this kernel: ${request.pluginId}")
        .build()

internal data class RegisteredProcessInfo(
    val manifest: ProcessManifest,
    val ipcAddress: String,
    val registeredAt: Long,
    val lastMetrics: AtomicReference<ProcessHealthMetrics?> = AtomicReference(null),
)
