package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.auth.ProcessCredential
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.*
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Authorizes a verified caller to target a process for shutdown. The default only permits
     * self-shutdown; the kernel supplies the orchestrator exception explicitly.
     */
    private val shutdownAuthorizer: (callerProcessId: String, targetProcessId: String) -> Boolean =
        { callerProcessId, targetProcessId -> callerProcessId == targetProcessId },
) : KernelServiceGrpcKt.KernelServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(KernelServiceImpl::class.java)

    // Track registered processes and their IPC addresses
    private val registeredProcesses = ConcurrentHashMap<String, RegisteredProcessInfo>()

    // Serialize registration/replacement and shutdown removal so a respawn cannot race a stale entry.
    private val registrationLock = Mutex()

    // Heartbeat tracking
    private val lastHeartbeats = ConcurrentHashMap<String, Long>()

    override suspend fun registerProcess(request: RegisterProcessRequest): RegisterProcessResponse {
        val manifest = request.manifest
        val processId = manifest.processId

        return registrationLock.withLock {
            // Resolve the token after waiting for the lifecycle lock. A process can be respawned while
            // an older registration is queued here; the old token must not commit after that handoff.
            val caller = authenticatedCallerOrRefuse("RegisterProcess")
            validateRegistrationIdentity(processId, caller)

            logger.info(
                "Process registering: id={}, type={}, name={}, ipc={}",
                processId,
                manifest.processType,
                manifest.displayName,
                request.ipcAddress,
            )

            val previous = registeredProcesses[processId]
            if (previous != null && previous.credentialGeneration == caller.credential.generation) {
                if (previous.manifest != manifest || previous.ipcAddress != request.ipcAddress) {
                    logger.warn("Rejected duplicate registration for process: id={}", processId)
                    throw StatusException(Status.ALREADY_EXISTS.withDescription(REGISTRATION_CONFLICT))
                }
                return@withLock registrationSuccess(registeredProcesses, processId)
            }

            val previousHeartbeat = lastHeartbeats[processId]
            val replacement =
                RegisteredProcessInfo(
                    manifest = manifest,
                    ipcAddress = request.ipcAddress,
                    registeredAt = System.currentTimeMillis(),
                    credentialGeneration = caller.credential.generation,
                )
            registeredProcesses[processId] = replacement
            lastHeartbeats[processId] = replacement.registeredAt

            // Keep the service state transactional if the kernel callback fails or the token is revoked.
            val callbackFailure =
                invokeRegistrationCallback(
                    RegistrationTransition(
                        processId = processId,
                        manifest = manifest,
                        ipcAddress = request.ipcAddress,
                        caller = caller,
                        replacement = replacement,
                        previous = previous,
                        previousHeartbeat = previousHeartbeat,
                    ),
                )
            if (callbackFailure != null) return@withLock callbackFailure

            logger.info("Process registered successfully: id={}", processId)
            registrationSuccess(registeredProcesses, processId)
        }
    }

    override fun heartbeat(requests: Flow<HeartbeatPing>): Flow<HeartbeatPong> {
        // Capture the resolver while the gRPC Context is current. The resolver is invoked for every
        // message, so revoking or replacing a token closes an already-open heartbeat stream.
        val currentCredential = ProcessIdentityInterceptor.CURRENT_CREDENTIAL.get()
        val credentialSnapshot = ProcessIdentityInterceptor.AUTHENTICATED_CREDENTIAL.get()
        return flow {
            requests.collect { ping ->
                val pong =
                    registrationLock.withLock {
                        // Re-resolve after waiting for the lifecycle lock. This prevents a heartbeat
                        // from an old credential from updating a replacement registration.
                        val credential =
                            if (currentCredential != null) {
                                currentCredential()
                            } else {
                                credentialSnapshot
                            }
                        if (credential == null) {
                            throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
                        }

                        if (ping.processId != credential.processId) {
                            logger.warn(
                                "Rejected heartbeat with mismatched process id: declared={}, authenticated={}",
                                ping.processId,
                                credential.processId,
                            )
                            throw StatusException(Status.PERMISSION_DENIED.withDescription(IDENTITY_MISMATCH))
                        }

                        val info = registeredProcesses[credential.processId]
                        if (info == null || info.credentialGeneration != credential.generation) {
                            throw StatusException(
                                Status.FAILED_PRECONDITION.withDescription(PROCESS_NOT_REGISTERED),
                            )
                        }

                        val now = System.currentTimeMillis()
                        lastHeartbeats[credential.processId] = now

                        // Update metrics if provided
                        if (ping.hasMetrics()) {
                            info.lastMetrics.set(ping.metrics)
                        }

                        HeartbeatPong
                            .newBuilder()
                            .setProcessId(credential.processId)
                            .setTimestamp(now)
                            .setAcknowledged(true)
                            .build()
                    }
                emit(pong)
            }
        }
    }

    override suspend fun requestShutdown(request: ShutdownRequest): ShutdownResponse {
        val processId = request.processId

        val success =
            registrationLock.withLock {
                // Revalidate after waiting for the lifecycle lock so a stale orchestrator or service
                // token cannot act on a replacement process registration.
                val caller = authenticatedCallerOrRefuse("RequestShutdown")
                if (!shutdownAuthorizer(caller.credential.processId, processId)) {
                    logger.warn(
                        "Rejected unauthorized shutdown request: caller={}, target={}",
                        caller.credential.processId,
                        processId,
                    )
                    throw StatusException(Status.PERMISSION_DENIED.withDescription(SHUTDOWN_NOT_AUTHORIZED))
                }

                logger.info(
                    "Shutdown requested for process: id={}, force={}, reason={}",
                    processId,
                    request.force,
                    request.reason,
                )

                val targetRegistration = registeredProcesses[processId]
                val callbackCaller = authenticatedCallerOrRefuse("RequestShutdown")
                if (callbackCaller.credential != caller.credential) {
                    throw StatusException(Status.PERMISSION_DENIED.withDescription(STALE_CREDENTIAL))
                }
                val result =
                    try {
                        onShutdownRequested(processId, request.force)
                    } catch (e: Exception) {
                        logger.error("Error shutting down process {}", processId, e)
                        false
                    }

                // Remove only the registration that was present when the shutdown started. This protects a
                // newly registered respawn from a late completion of the predecessor's shutdown callback.
                if (result && targetRegistration != null && registeredProcesses.remove(processId, targetRegistration)) {
                    lastHeartbeats.remove(processId)
                }
                result
            }

        return ShutdownResponse
            .newBuilder()
            .setSuccess(success)
            .build()
    }

    override suspend fun getProcessStatus(request: ProcessStatusRequest): ProcessStatusResponse {
        authenticatedCallerOrRefuse("GetProcessStatus")
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
        authenticatedCallerOrRefuse("ListProcesses")
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

    private fun authenticatedCallerOrRefuse(rpc: String): AuthenticatedCaller {
        val resolver = ProcessIdentityInterceptor.CURRENT_CREDENTIAL.get()
        val credential =
            if (resolver != null) {
                resolver()
            } else {
                ProcessIdentityInterceptor.AUTHENTICATED_CREDENTIAL.get()
            }
        return credential?.let(::AuthenticatedCaller) ?: run {
            logger.warn("Rejected {}: no verified process identity", rpc)
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
        }
    }

    private fun validateRegistrationIdentity(
        processId: String,
        caller: AuthenticatedCaller,
    ) {
        if (processId == caller.credential.processId) return
        logger.warn(
            "Rejected process registration with mismatched process id: declared={}, authenticated={}",
            processId,
            caller.credential.processId,
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(IDENTITY_MISMATCH))
    }

    private suspend fun invokeRegistrationCallback(transition: RegistrationTransition): RegisterProcessResponse? {
        fun rollback() {
            if (registeredProcesses[transition.processId] !== transition.replacement) return
            if (transition.previous == null) {
                registeredProcesses.remove(transition.processId, transition.replacement)
            } else {
                registeredProcesses[transition.processId] = transition.previous
            }
            if (transition.previousHeartbeat == null) {
                lastHeartbeats.remove(transition.processId, transition.replacement.registeredAt)
            } else {
                lastHeartbeats[transition.processId] = transition.previousHeartbeat
            }
        }

        val callbackCaller =
            try {
                authenticatedCallerOrRefuse("RegisterProcess")
            } catch (e: StatusException) {
                rollback()
                throw e
            }
        if (callbackCaller.credential != transition.caller.credential) {
            rollback()
            throw StatusException(Status.PERMISSION_DENIED.withDescription(STALE_CREDENTIAL))
        }

        return try {
            onProcessRegistered(transition.processId, transition.manifest, transition.ipcAddress)
            null
        } catch (e: Exception) {
            rollback()
            logger.error("Error in process registration callback for {}", transition.processId, e)
            RegisterProcessResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("Registration callback failed: ${e.message}")
                .build()
        }
    }

    private data class AuthenticatedCaller(
        val credential: ProcessCredential,
    )

    private data class RegistrationTransition(
        val processId: String,
        val manifest: ProcessManifest,
        val ipcAddress: String,
        val caller: AuthenticatedCaller,
        val replacement: RegisteredProcessInfo,
        val previous: RegisteredProcessInfo?,
        val previousHeartbeat: Long?,
    )

    private companion object {
        const val NO_IDENTITY = "This call presented no verified process identity"
        const val IDENTITY_MISMATCH = "The request process id does not match the authenticated process"
        const val REGISTRATION_CONFLICT = "This process credential is already registered with different details"
        const val PROCESS_NOT_REGISTERED = "The process must register before sending heartbeats"
        const val SHUTDOWN_NOT_AUTHORIZED = "The authenticated process is not allowed to target this process"
        const val STALE_CREDENTIAL = "The credential was revoked while the lifecycle operation was in progress"
    }
}

private fun registrationSuccess(
    registeredProcesses: Map<String, RegisteredProcessInfo>,
    processId: String,
): RegisterProcessResponse {
    val serviceAddresses =
        registeredProcesses
            .filter { it.key != processId }
            .mapValues { it.value.ipcAddress }

    return RegisterProcessResponse
        .newBuilder()
        .setSuccess(true)
        .setAssignedProcessId(processId)
        .putAllServiceAddresses(serviceAddresses)
        .build()
}

internal data class RegisteredProcessInfo(
    val manifest: ProcessManifest,
    val ipcAddress: String,
    val registeredAt: Long,
    val credentialGeneration: Long,
    val lastMetrics: AtomicReference<ProcessHealthMetrics?> = AtomicReference(null),
)
