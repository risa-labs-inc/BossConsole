package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * Bootstrap utility for child processes (services, apps, plugins).
 *
 * Every child process uses this to:
 * 1. Read kernel IPC address from environment
 * 2. Connect to the kernel
 * 3. Register itself with its manifest
 * 4. Start heartbeat stream
 * 5. Start its own gRPC server for incoming calls
 *
 * Usage:
 * ```kotlin
 * fun main() {
 *     val bootstrap = ChildProcessBootstrap()
 *     runBlocking {
 *         val connection = bootstrap.connect(manifest)
 *         // connection.kernelChannel is ready for making calls
 *         // connection.processServer is ready for receiving calls
 *         connection.awaitTermination()
 *     }
 * }
 * ```
 */
class ChildProcessBootstrap {
    private val logger = LoggerFactory.getLogger(ChildProcessBootstrap::class.java)
    private val startTimeMs = System.currentTimeMillis()

    val processId: String by lazy {
        System.getenv("BOSS_PROCESS_ID")
            ?: throw IllegalStateException("BOSS_PROCESS_ID environment variable not set")
    }

    val processType: String by lazy {
        System.getenv("BOSS_PROCESS_TYPE")
            ?: throw IllegalStateException("BOSS_PROCESS_TYPE environment variable not set")
    }

    val kernelAddress: String by lazy {
        System.getenv("BOSS_KERNEL_IPC_ADDR")
            ?: throw IllegalStateException("BOSS_KERNEL_IPC_ADDR environment variable not set")
    }

    val processAddress: String by lazy {
        System.getenv("BOSS_IPC_ADDR")
            ?: IpcAddressResolver.resolveAddress(processType.lowercase(), processId)
    }

    /**
     * Connect to the kernel, register, and start heartbeat.
     */
    suspend fun connect(
        manifest: ProcessManifest,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    ): ChildProcessConnection {
        // Brief delay to ensure the kernel gRPC server is accepting connections before
        // our first connect attempt. The kernel spawns child processes and then binds
        // its server; without this delay the first waitForReady often times out on
        // slow machines.
        delay(100)
        logger.info("Connecting to kernel at: {}", kernelAddress)

        val credentials =
            IpcClientCredentials(
                IpcEnvironment.required(IpcEnvironment.KERNEL_CERTIFICATE),
                IpcEnvironment.required(IpcEnvironment.PROCESS_TOKEN),
            )
        val processServer =
            BossIpcServer(
                processAddress,
                ProcessTokenRegistry.forHostController(IpcEnvironment.required(IpcEnvironment.HOST_TOKEN)),
                IpcTlsIdentity.restore(
                    IpcEnvironment.required(IpcEnvironment.SERVER_CERTIFICATE),
                    IpcEnvironment.required(IpcEnvironment.SERVER_PRIVATE_KEY),
                ),
            )
        val kernelClient = BossIpcClient(kernelAddress, credentials)
        var connected = false
        try {
            check(kernelClient.waitForReady(30_000)) { "Failed to establish authenticated kernel IPC" }
            val kernelStub = KernelServiceGrpcKt.KernelServiceCoroutineStub(kernelClient.channel)
            val response =
                kernelStub.registerProcess(
                    RegisterProcessRequest
                        .newBuilder()
                        .setManifest(manifest)
                        .setIpcAddress(processAddress)
                        .build(),
                )
            check(response.success && response.assignedProcessId == processId) { "Kernel registration was refused" }
            val heartbeatJob = scope.launch { startHeartbeat(kernelStub, manifest) }
            val connection =
                ChildProcessConnection(
                    processId = processId,
                    kernelClient = kernelClient,
                    kernelStub = kernelStub,
                    processServer = processServer,
                    heartbeatJob = heartbeatJob,
                    serviceAddresses = response.serviceAddressesMap,
                    scope = scope,
                )
            connected = true
            return connection
        } finally {
            if (!connected) kernelClient.shutdown()
        }
    }

    private suspend fun startHeartbeat(
        kernelStub: KernelServiceGrpcKt.KernelServiceCoroutineStub,
        manifest: ProcessManifest,
    ) {
        val intervalMs = manifest.healthContract.heartbeatIntervalMs.takeIf { it > 0 } ?: 5000L

        try {
            val pingFlow =
                flow {
                    while (currentCoroutineContext().isActive) {
                        emit(
                            HeartbeatPing
                                .newBuilder()
                                .setProcessId(processId)
                                .setTimestamp(System.currentTimeMillis())
                                .setMetrics(collectHealthMetrics())
                                .build(),
                        )
                        delay(intervalMs)
                    }
                }

            kernelStub.heartbeat(pingFlow).collect { pong ->
                if (!pong.acknowledged) {
                    logger.warn("Heartbeat not acknowledged by kernel")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Heartbeat stream failed", e)
        }
    }

    private fun collectHealthMetrics(): ProcessHealthMetrics {
        val runtime = Runtime.getRuntime()
        return ProcessHealthMetrics
            .newBuilder()
            .setHeapUsedBytes(runtime.totalMemory() - runtime.freeMemory())
            .setHeapMaxBytes(runtime.maxMemory())
            .setActiveThreads(Thread.activeCount())
            .setUptimeMs(System.currentTimeMillis() - startTimeMs)
            .build()
    }
}

/**
 * Represents an established connection from a child process to the kernel.
 */
class ChildProcessConnection(
    val processId: String,
    val kernelClient: BossIpcClient,
    val kernelStub: KernelServiceGrpcKt.KernelServiceCoroutineStub,
    val processServer: BossIpcServer,
    val heartbeatJob: Job,
    val serviceAddresses: Map<String, String>,
    private val scope: CoroutineScope,
) {
    /**
     * Start the process's own gRPC server (after adding services to processServer).
     */
    fun startServer(): ChildProcessConnection {
        processServer.start()
        return this
    }

    /**
     * Wait for the process to terminate.
     */
    fun awaitTermination() {
        processServer.awaitTermination()
    }

    /**
     * Create a client to another service process using its address from the kernel.
     */
    fun connectToService(serviceName: String): BossIpcClient? {
        if (serviceName !in serviceAddresses) return null
        error("Direct peer IPC requires a host-authorized channel; request this service through the host")
    }

    /**
     * Gracefully shut down the connection.
     */
    fun shutdown() {
        heartbeatJob.cancel()
        processServer.stop()
        kernelClient.shutdown()
        scope.cancel()
    }
}
