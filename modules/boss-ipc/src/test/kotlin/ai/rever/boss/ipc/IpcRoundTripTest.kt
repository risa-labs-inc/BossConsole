package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.*
import ai.rever.boss.ipc.services.KernelServiceImpl
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for the IPC round-trip: server start → client connect → gRPC call → response.
 *
 * Uses TCP (not UDS) for simplicity in tests — avoids platform-specific socket configuration.
 */
class IpcRoundTripTest {

    private var server: io.grpc.Server? = null
    private var channel: io.grpc.ManagedChannel? = null
    private var port: Int = 0

    private lateinit var kernelService: KernelServiceImpl

    @Before
    fun setUp() {
        // Find available port
        port = ServerSocket(0).use { it.localPort }

        kernelService = KernelServiceImpl()

        server = ServerBuilder.forPort(port)
            .addService(kernelService)
            .build()
            .start()

        channel = ManagedChannelBuilder.forAddress("localhost", port)
            .usePlaintext()
            .build()
    }

    @After
    fun tearDown() {
        channel?.shutdownNow()
        server?.shutdownNow()
    }

    @Test
    fun `registerProcess returns success with assigned process ID`() = runBlocking {
        val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(channel!!)

        val manifest = ProcessManifest.newBuilder()
            .setProcessId("test-process-001")
            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
            .setDisplayName("Test Process")
            .setVersion("1.0.0")
            .setMainClass("ai.rever.boss.test.TestMain")
            .setHealthContract(
                HealthContract.newBuilder()
                    .setHeartbeatIntervalMs(5000)
                    .setStartupTimeoutMs(10000)
                    .build()
            )
            .build()

        val response = stub.registerProcess(
            RegisterProcessRequest.newBuilder()
                .setManifest(manifest)
                .setIpcAddress("tcp://localhost:59999")
                .build()
        )

        assertTrue(response.success, "Registration should succeed")
        assertEquals("test-process-001", response.assignedProcessId)
        assertEquals(1, kernelService.registeredCount)
    }

    @Test
    fun `registerProcess callback is invoked`() = runBlocking {
        var callbackProcessId: String? = null

        val kernelWithCallback = KernelServiceImpl(
            onProcessRegistered = { id, _, _ -> callbackProcessId = id }
        )
        val testPort = ServerSocket(0).use { it.localPort }
        val testServer = ServerBuilder.forPort(testPort)
            .addService(kernelWithCallback)
            .build()
            .start()
        val testChannel = ManagedChannelBuilder.forAddress("localhost", testPort)
            .usePlaintext()
            .build()

        try {
            val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(testChannel)
            val manifest = ProcessManifest.newBuilder()
                .setProcessId("callback-test-process")
                .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                .build()

            stub.registerProcess(
                RegisterProcessRequest.newBuilder()
                    .setManifest(manifest)
                    .setIpcAddress("tcp://localhost:59998")
                    .build()
            )

            assertEquals("callback-test-process", callbackProcessId)
        } finally {
            testChannel.shutdownNow()
            testServer.shutdownNow()
        }
    }

    @Test
    fun `heartbeat stream sends pongs for each ping`() = runBlocking {
        withTimeout(10_000) {
            val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(channel!!)

            // Register first
            val manifest = ProcessManifest.newBuilder()
                .setProcessId("heartbeat-test-process")
                .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                .build()
            stub.registerProcess(
                RegisterProcessRequest.newBuilder()
                    .setManifest(manifest)
                    .setIpcAddress("tcp://localhost:59997")
                    .build()
            )

            // Send 3 heartbeat pings
            val pingFlow = flow {
                repeat(3) { i ->
                    emit(
                        HeartbeatPing.newBuilder()
                            .setProcessId("heartbeat-test-process")
                            .setTimestamp(System.currentTimeMillis())
                            .build()
                    )
                }
            }

            val pongs = stub.heartbeat(pingFlow).take(3).toList()

            assertEquals(3, pongs.size, "Should receive 3 pongs")
            pongs.forEach { pong ->
                assertTrue(pong.acknowledged, "Each pong should be acknowledged")
                assertEquals("heartbeat-test-process", pong.processId)
            }
        }
    }

    @Test
    fun `getProcessStatus returns STOPPED for unknown process`() = runBlocking {
        val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(channel!!)

        val status = stub.getProcessStatus(
            ProcessStatusRequest.newBuilder()
                .setProcessId("nonexistent-process")
                .build()
        )

        assertEquals(ProcessState.PROCESS_STATE_STOPPED, status.state)
    }

    @Test
    fun `getProcessStatus returns RUNNING for registered process`() = runBlocking {
        val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(channel!!)

        val manifest = ProcessManifest.newBuilder()
            .setProcessId("status-test-process")
            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
            .build()
        stub.registerProcess(
            RegisterProcessRequest.newBuilder()
                .setManifest(manifest)
                .setIpcAddress("tcp://localhost:59996")
                .build()
        )

        val status = stub.getProcessStatus(
            ProcessStatusRequest.newBuilder()
                .setProcessId("status-test-process")
                .build()
        )

        assertEquals(ProcessState.PROCESS_STATE_RUNNING, status.state)
    }

    @Test
    fun `listProcesses returns all registered processes`() = runBlocking {
        val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(channel!!)

        repeat(3) { i ->
            val manifest = ProcessManifest.newBuilder()
                .setProcessId("list-test-process-$i")
                .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                .build()
            stub.registerProcess(
                RegisterProcessRequest.newBuilder()
                    .setManifest(manifest)
                    .setIpcAddress("tcp://localhost:${59990 + i}")
                    .build()
            )
        }

        val list = stub.listProcesses(Empty.getDefaultInstance())

        assertEquals(3, list.processesCount, "Should list all 3 registered processes")
    }

    @Test
    fun `requestShutdown removes process from registry`() = runBlocking {
        val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(channel!!)

        val manifest = ProcessManifest.newBuilder()
            .setProcessId("shutdown-test-process")
            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
            .build()
        stub.registerProcess(
            RegisterProcessRequest.newBuilder()
                .setManifest(manifest)
                .setIpcAddress("tcp://localhost:59989")
                .build()
        )

        assertEquals(1, kernelService.registeredCount)

        stub.requestShutdown(
            ShutdownRequest.newBuilder()
                .setProcessId("shutdown-test-process")
                .setForce(false)
                .setReason("test shutdown")
                .build()
        )

        assertEquals(0, kernelService.registeredCount, "Process should be removed after shutdown")
    }
}
