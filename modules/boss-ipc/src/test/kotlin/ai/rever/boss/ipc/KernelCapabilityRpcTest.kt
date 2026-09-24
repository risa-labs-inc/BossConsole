package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.CapabilityServiceGrpcKt
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.ipc.proto.InvokeCapabilityResponse
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessType
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.services.KernelServiceImpl
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Capability mediation on KernelService (#1061): child processes cannot reach each other, so
 * the kernel - which owns the registry RegisterProcess populates - brokers every capability
 * invocation and fails closed wherever it cannot prove a target.
 */
class KernelCapabilityRpcTest {
    @Test
    fun `invokeCapability reaches a registered process through the kernel`() =
        runBlocking {
            withTimeout(20_000) {
                IpcTestServer(echoPlugin()).use { plugin ->
                    val pluginClient =
                        BossIpcClient(
                            "tcp://127.0.0.1:${plugin.server.port}",
                            IpcClientCredentials(
                                plugin.identity.certificateBase64,
                                plugin.registry.issue("boss-kernel-host", authority = ProcessAuthority.HOST),
                            ),
                        )
                    val brokered = AtomicInteger()
                    val kernel =
                        KernelServiceImpl(
                            onCapabilityInvocation = { request ->
                                brokered.incrementAndGet()
                                CapabilityServiceGrpcKt
                                    .CapabilityServiceCoroutineStub(pluginClient.channel)
                                    .invokeCapability(request)
                            },
                        )
                    IpcTestServer(kernel).use { host ->
                        val caller = register(host, "boss-mastery-orchestrator")
                        register(
                            host,
                            "capability-plugin",
                            "tcp://127.0.0.1:${plugin.server.port}",
                            capability("echo", "Echoes the say input"),
                        )

                        val response =
                            caller.invokeCapability(
                                InvokeCapabilityRequest
                                    .newBuilder()
                                    .setPluginId("capability-plugin")
                                    .setAction("echo")
                                    .putInput("say", "hello")
                                    .build(),
                            )

                        assertTrue(response.success)
                        assertEquals("hello", response.outputMap["echo"])
                        assertEquals("capability-plugin", response.outputMap["plugin"])
                        assertEquals(1, brokered.get())
                    }
                    pluginClient.shutdown()
                }
            }
        }

    @Test
    fun `invokeCapability fails closed for an unknown process without consulting the broker`() =
        runBlocking {
            withTimeout(20_000) {
                val brokered = AtomicInteger()
                val kernel =
                    KernelServiceImpl(
                        onCapabilityInvocation = { _ ->
                            brokered.incrementAndGet()
                            InvokeCapabilityResponse
                                .newBuilder()
                                .setSuccess(false)
                                .setErrorMessage("broker should not be consulted")
                                .build()
                        },
                    )
                IpcTestServer(kernel).use { host ->
                    val caller = register(host, "boss-mastery-orchestrator")

                    val response =
                        caller.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("ghost")
                                .setAction("echo")
                                .build(),
                        )

                    assertFalse(response.success)
                    assertEquals("Process not found: ghost", response.errorMessage)
                    assertEquals(0, brokered.get())
                }
            }
        }

    @Test
    fun `invokeCapability fails closed when the kernel has no invocation wiring`() =
        runBlocking {
            withTimeout(20_000) {
                IpcTestServer(KernelServiceImpl()).use { host ->
                    val caller = register(host, "boss-mastery-orchestrator")
                    register(host, "capability-plugin")

                    val response =
                        caller.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("capability-plugin")
                                .setAction("echo")
                                .build(),
                        )

                    assertFalse(response.success)
                    assertTrue(response.errorMessage.contains("not configured"))
                }
            }
        }

    @Test
    fun `unregistered callers cannot invoke or list capabilities`() =
        runBlocking {
            withTimeout(20_000) {
                IpcTestServer(KernelServiceImpl()).use { host ->
                    register(host, "capability-plugin")
                    val stranger = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor("stranger"))
                    val invocation =
                        InvokeCapabilityRequest
                            .newBuilder()
                            .setPluginId("capability-plugin")
                            .setAction("echo")
                            .build()

                    refused(Status.Code.PERMISSION_DENIED) { stranger.invokeCapability(invocation) }
                    refused(Status.Code.PERMISSION_DENIED) {
                        stranger.listCapabilities(Empty.getDefaultInstance())
                    }
                }
            }
        }

    @Test
    fun `listCapabilities advertises registered capabilities to registered callers`() =
        runBlocking {
            withTimeout(20_000) {
                IpcTestServer(KernelServiceImpl()).use { host ->
                    register(
                        host,
                        "capability-plugin",
                        ADDRESS,
                        capability("echo", "Echoes the say input"),
                    )
                    val caller = register(host, "boss-mastery-orchestrator")

                    val listed = caller.listCapabilities(Empty.getDefaultInstance())

                    val descriptor = listed.capabilitiesList.single()
                    assertEquals("capability-plugin", descriptor.pluginId)
                    assertEquals("echo", descriptor.action)
                    assertEquals("Echoes the say input", descriptor.description)
                }
            }
        }

    private fun echoPlugin(): CapabilityServiceGrpcKt.CapabilityServiceCoroutineImplBase =
        object : CapabilityServiceGrpcKt.CapabilityServiceCoroutineImplBase() {
            override suspend fun invokeCapability(request: InvokeCapabilityRequest): InvokeCapabilityResponse =
                InvokeCapabilityResponse
                    .newBuilder()
                    .setSuccess(true)
                    .putOutput("echo", request.inputMap["say"] ?: "")
                    .putOutput("plugin", request.pluginId)
                    .build()
        }

    private suspend fun register(
        host: IpcTestServer,
        processId: String,
        ipcAddress: String = ADDRESS,
        vararg capabilities: PluginCapability,
    ): KernelServiceGrpcKt.KernelServiceCoroutineStub {
        val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor(processId, ipcAddress))
        val manifest =
            ProcessManifest
                .newBuilder()
                .setProcessId(processId)
                .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                .apply { capabilities.forEach(::addCapabilities) }
                .build()
        val response =
            stub.registerProcess(
                RegisterProcessRequest
                    .newBuilder()
                    .setManifest(manifest)
                    .setIpcAddress(ipcAddress)
                    .build(),
            )
        assertTrue(response.success)
        return stub
    }

    private fun capability(
        action: String,
        description: String,
    ): PluginCapability =
        PluginCapability
            .newBuilder()
            .setAction(action)
            .setDescription(description)
            .setInputSchemaJson("{}")
            .setOutputSchemaJson("{}")
            .build()

    private suspend fun refused(
        code: Status.Code,
        action: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<StatusException> { action() }
        assertEquals(code, failure.status.code)
    }

    private companion object {
        const val ADDRESS = "tcp://127.0.0.1:59000"
    }
}
