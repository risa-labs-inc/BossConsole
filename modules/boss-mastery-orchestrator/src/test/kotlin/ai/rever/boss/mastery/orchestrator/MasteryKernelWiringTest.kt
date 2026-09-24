package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.ChildProcessConnection
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.CapabilityServiceGrpcKt
import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.ipc.proto.InvokeCapabilityResponse
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessType
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.mastery.MasteryDefinition
import ai.rever.boss.mastery.MasteryNode
import ai.rever.boss.mastery.MasteryProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Wiring tests for the kernel-mediated resolution MasteryOrchestratorMain installs (#1061).
 *
 * [KernelFixture] stands up a real kernel - a KernelServiceImpl on an authenticated IPC
 * server - plus a fake capability process that registers through the real RegisterProcess RPC.
 * The kernel-side callback is wired the way KernelBootstrap wires it: resolve the process in
 * the registry that registration populates, then invoke it over that process's IPC client.
 * The factory under test is the same one main() uses. Against the old wiring (a locally
 * constructed, never-populated ProcessRegistry) the end-to-end test below is exactly the one
 * that failed with "Process not found" for every node.
 */
class MasteryKernelWiringTest {
    @Test
    fun `executor reaches a capability process registered through the kernel`() =
        runBlocking {
            withTimeout(30_000) {
                KernelFixture().use { fixture ->
                    fixture.registerPlugin("capability-plugin")
                    val executor = createMasteryExecutor(fixture.connectMastery())

                    val progress =
                        executor
                            .execute(echoMastery("capability-plugin"), mapOf("msg" to "hello"))
                            .toList()

                    val nodeDone = progress.filterIsInstance<MasteryProgress.NodeCompleted>().single()
                    assertEquals("hello", nodeDone.output["echo"])
                    val completed = progress.filterIsInstance<MasteryProgress.Completed>().single()
                    assertEquals("hello", completed.output["echo"])
                    assertEquals("capability-plugin", completed.output["plugin"])
                    assertTrue(progress.none { it is MasteryProgress.Failed })
                }
            }
        }

    @Test
    fun `missing plugin fails closed with the documented process not found error`() =
        runBlocking {
            withTimeout(30_000) {
                KernelFixture().use { fixture ->
                    val executor = createMasteryExecutor(fixture.connectMastery())

                    val progress = executor.execute(echoMastery("ghost"), mapOf("msg" to "hello")).toList()

                    val failed = progress.filterIsInstance<MasteryProgress.Failed>().single()
                    assertTrue("unexpected failure: ${failed.error}") {
                        failed.error.contains("Process not found: ghost")
                    }
                    assertTrue(progress.none { it is MasteryProgress.NodeCompleted })
                }
            }
        }

    @Test
    fun `resolver is bound to the kernel registry registration populates`() =
        runBlocking {
            withTimeout(30_000) {
                KernelFixture().use { fixture ->
                    fixture.registerPlugin("capability-plugin")
                    val connection = fixture.connectMastery()

                    // Constructed exactly as createMasteryExecutor constructs it: from
                    // the registration-authenticated kernel stub. The never-populated
                    // local ProcessRegistry of #1061 saw none of the registrations the
                    // kernel holds; the kernel-bound resolver must see the capability
                    // the plugin registered.
                    val resolver = KernelCapabilityResolver(connection.kernelStub)
                    assertSame(connection.kernelStub, resolver.kernelStub)
                    val echo = resolver.getAvailableCapabilities().single { it.action == "echo" }
                    assertEquals("capability-plugin", echo.pluginId)
                }
            }
        }

    @Test
    fun `factory refuses to start against a kernel that cannot mediate capabilities`() =
        runBlocking {
            withTimeout(30_000) {
                KernelFixture(oldKernel = true).use { fixture ->
                    val connection = fixture.connectMastery()

                    val failure = assertFailsWith<IllegalStateException> { createMasteryExecutor(connection) }
                    assertTrue(requireNotNull(failure.message).contains("refusing to start"))
                }
            }
        }

    private fun echoMastery(pluginId: String) =
        MasteryDefinition(
            id = "echo-mastery",
            name = "Echo",
            description = "Echoes the mastery input through one capability",
            nodes =
                listOf(
                    MasteryNode(
                        id = "echo-node",
                        pluginId = pluginId,
                        action = "echo",
                        inputMapping = mapOf("say" to "INPUT.msg"),
                        timeoutMs = 10_000,
                        displayName = "echo-node",
                    ),
                ),
            edges = emptyList(),
        )
}

/**
 * A real in-process kernel: an authenticated IPC server hosting a KernelServiceImpl, plus a
 * fake capability process. Registration goes through the real RPC, and the capability
 * callback mirrors KernelBootstrap - the process is resolved in the map RegisterProcess
 * populates and invoked over that process's IPC client.
 */
private class KernelFixture(
    private val oldKernel: Boolean = false,
) : AutoCloseable {
    private val tokens = ProcessTokenRegistry()
    private val kernelIdentity = IpcTlsIdentity.create()
    private val pluginIdentity = IpcTlsIdentity.create()
    private val pluginClients = ConcurrentHashMap<String, BossIpcClient>()
    private val clients = mutableListOf<BossIpcClient>()
    private val pluginServer =
        BossIpcServer("tcp://127.0.0.1:0", tokens, pluginIdentity)
            .also {
                it.addService(echoCapabilityService())
                it.start()
            }
    private val kernelService: KernelServiceGrpcKt.KernelServiceCoroutineImplBase =
        if (oldKernel) {
            // A kernel from before capability mediation existed: every RPC is UNIMPLEMENTED.
            object : KernelServiceGrpcKt.KernelServiceCoroutineImplBase() {}
        } else {
            KernelServiceImpl(
                onProcessRegistered = { id, _, address ->
                    if (address.isNotBlank()) pluginClients[id] = pluginClient(address)
                },
                onCapabilityInvocation = { request ->
                    val client = pluginClients[request.pluginId]
                    if (client == null) {
                        capabilityFailure("Process not found: ${request.pluginId}")
                    } else {
                        val stub =
                            CapabilityServiceGrpcKt.CapabilityServiceCoroutineStub(client.channel)
                        stub.invokeCapability(request)
                    }
                },
            )
        }
    private val kernelServer =
        BossIpcServer("tcp://127.0.0.1:0", tokens, kernelIdentity)
            .also {
                it.addService(kernelService)
                it.start()
            }

    suspend fun registerPlugin(pluginId: String) {
        val address = "tcp://127.0.0.1:${pluginServer.port}"
        val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(client(pluginId, address).channel)
        val response =
            stub.registerProcess(
                RegisterProcessRequest
                    .newBuilder()
                    .setManifest(
                        ProcessManifest
                            .newBuilder()
                            .setProcessId(pluginId)
                            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                            .addCapabilities(
                                PluginCapability
                                    .newBuilder()
                                    .setAction("echo")
                                    .setDescription("Echoes the say input")
                                    .build(),
                            ).build(),
                    ).setIpcAddress(address)
                    .build(),
            )
        assertTrue(response.success)
    }

    /** Registers the mastery process through the same registerProcess RPC bootstrap uses. */
    suspend fun connectMastery(): ChildProcessConnection {
        val kernelClient = client(MASTERY_PROCESS_ID, MASTERY_ADDRESS)
        val connection =
            ChildProcessConnection(
                processId = MASTERY_PROCESS_ID,
                kernelClient = kernelClient,
                kernelStub = KernelServiceGrpcKt.KernelServiceCoroutineStub(kernelClient.channel),
                processServer = BossIpcServer("tcp://127.0.0.1:0", tokens, IpcTlsIdentity.create()),
                heartbeatJob = Job(),
                serviceAddresses = emptyMap(),
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            )
        if (oldKernel) return connection
        val response =
            connection.kernelStub.registerProcess(
                RegisterProcessRequest
                    .newBuilder()
                    .setManifest(
                        ProcessManifest
                            .newBuilder()
                            .setProcessId(MASTERY_PROCESS_ID)
                            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                            .build(),
                    ).setIpcAddress(MASTERY_ADDRESS)
                    .build(),
            )
        assertTrue(response.success)
        return connection
    }

    private fun echoCapabilityService() =
        object : CapabilityServiceGrpcKt.CapabilityServiceCoroutineImplBase() {
            override suspend fun invokeCapability(request: InvokeCapabilityRequest): InvokeCapabilityResponse =
                InvokeCapabilityResponse
                    .newBuilder()
                    .setSuccess(true)
                    .putOutput("echo", request.inputMap["say"] ?: "")
                    .putOutput("plugin", request.pluginId)
                    .build()
        }

    private fun capabilityFailure(message: String): InvokeCapabilityResponse =
        InvokeCapabilityResponse
            .newBuilder()
            .setSuccess(false)
            .setErrorMessage(message)
            .build()

    private fun client(
        processId: String,
        expectedAddress: String,
    ): BossIpcClient =
        BossIpcClient(
            "tcp://127.0.0.1:${kernelServer.port}",
            IpcClientCredentials(
                kernelIdentity.certificateBase64,
                tokens.issue(processId, expectedAddress = expectedAddress),
            ),
        ).also { clients.add(it) }

    /**
     * One host credential for every broker dial: issuing a second token for the same id
     * revokes the first, exactly as ProcessTokenRegistry treats a replacement incarnation.
     */
    private val hostCredentials =
        IpcClientCredentials(
            pluginIdentity.certificateBase64,
            tokens.issue("boss-kernel-host", authority = ProcessAuthority.HOST),
        )

    private fun pluginClient(pluginAddress: String): BossIpcClient {
        val client = BossIpcClient(pluginAddress, hostCredentials)
        clients.add(client)
        return client
    }

    override fun close() {
        clients.forEach { client -> client.shutdown() }
        kernelServer.stop(2_000)
        pluginServer.stop(2_000)
    }

    private companion object {
        const val MASTERY_PROCESS_ID = "boss-mastery-orchestrator"
        const val MASTERY_ADDRESS = "tcp://127.0.0.1:59999"
    }
}
