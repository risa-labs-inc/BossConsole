package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.ipc.proto.InvokeCapabilityResponse
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessType
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.services.KernelServiceImpl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signature of the host-wired broker that invokes a registered process's capability. */
private typealias CapabilityBroker = suspend (InvokeCapabilityRequest) -> InvokeCapabilityResponse

/**
 * Capability-dispatch admission at the kernel seam (#1061): [KernelServiceImpl.invokeCapability]
 * checks the requested action against the registered process's own manifest before the broker
 * is consulted, from the same table [KernelServiceImpl.listCapabilities] advertises, so the
 * set the kernel dispatches can never be wider than the set it advertises. A mastery node
 * names any (pluginId, action) pair it wants executed; these tests drive the real service over
 * the authenticated transport with a recording broker and pin the fail-closed contract: every
 * out-of-scope request is answered with a refusal that names its cause and never reaches the
 * broker, and the request that does reach it is exactly the admitted pair.
 *
 * Ported from the refusal tests of #1363 (ProcessRegistryCapabilityScopeTest and
 * ProcessRegistryCapabilityResolverDispatchTest), whose ProcessRegistryCapabilityResolver seam
 * #1153 retired. The manifest dimension moved to the manifest RegisterProcess stores, and the
 * liveness dimension the old registry admission covered is this table's own eviction: a
 * crashed or shut-down process is gone from it, which is what the evicted case pins.
 */
class KernelCapabilityAdmissionTest {
    @Test
    fun `an action the plugin never advertised is refused without consulting the broker`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val brokered = AtomicInteger()
                val kernel =
                    KernelServiceImpl(
                        onCapabilityInvocation = unconsultedBroker(brokered),
                    )
                IpcTestServer(kernel).use { host ->
                    val caller = register(host, "boss-mastery-orchestrator")
                    register(
                        host,
                        "capability-plugin",
                        ADDRESS,
                        capability("run_command", "Runs a terminal command"),
                    )

                    val response =
                        caller.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("capability-plugin")
                                .setAction("read_file")
                                .build(),
                        )

                    assertFalse(response.success)
                    assertEquals(
                        "Plugin capability-plugin does not advertise capability: read_file",
                        response.errorMessage,
                    )
                    assertEquals(0, brokered.get())
                }
            }
        }

    @Test
    fun `another plugin's action cannot be dispatched under a different owner id`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val brokered = AtomicInteger()
                val kernel =
                    KernelServiceImpl(
                        onCapabilityInvocation = unconsultedBroker(brokered),
                    )
                IpcTestServer(kernel).use { host ->
                    val caller = register(host, "boss-mastery-orchestrator")
                    register(
                        host,
                        "boss-app-terminal",
                        ADDRESS,
                        capability("run_command", "Runs a terminal command"),
                    )
                    register(
                        host,
                        "boss-service-filesystem",
                        ADDRESS,
                        capability("read_file", "Reads a file"),
                    )

                    // read_file belongs to the filesystem service's namespace; naming the
                    // terminal as the owner must not route it there.
                    val response =
                        caller.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("boss-app-terminal")
                                .setAction("read_file")
                                .build(),
                        )

                    assertFalse(response.success)
                    assertEquals(
                        "Plugin boss-app-terminal does not advertise capability: read_file",
                        response.errorMessage,
                    )
                    assertEquals(0, brokered.get())
                }
            }
        }

    @Test
    fun `a respawn that advertises fewer capabilities loses the dropped actions`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val brokered = AtomicInteger()
                val kernel =
                    KernelServiceImpl(
                        onCapabilityInvocation = unconsultedBroker(brokered),
                    )
                IpcTestServer(kernel).use { host ->
                    val caller = register(host, "boss-mastery-orchestrator")
                    register(
                        host,
                        "capability-plugin",
                        ADDRESS,
                        capability("echo", "Echoes the say input"),
                        capability("read_file", "Reads a file"),
                    )
                    // A respawn re-registers the same id and RegisterProcess replaces the
                    // stored manifest, so the action it dropped is no longer dispatchable.
                    register(
                        host,
                        "capability-plugin",
                        ADDRESS,
                        capability("echo", "Echoes the say input"),
                    )

                    val response =
                        caller.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("capability-plugin")
                                .setAction("read_file")
                                .build(),
                        )

                    assertFalse(response.success)
                    assertEquals(
                        "Plugin capability-plugin does not advertise capability: read_file",
                        response.errorMessage,
                    )
                    assertEquals(0, brokered.get())
                }
            }
        }

    @Test
    fun `a process evicted after failure is refused as not found`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val brokered = AtomicInteger()
                val kernel =
                    KernelServiceImpl(
                        onCapabilityInvocation = unconsultedBroker(brokered),
                    )
                IpcTestServer(kernel).use { host ->
                    val caller = register(host, "boss-mastery-orchestrator")
                    register(
                        host,
                        "capability-plugin",
                        ADDRESS,
                        capability("echo", "Echoes the say input"),
                    )
                    assertTrue(kernel.deregisterProcess("capability-plugin"))

                    val response =
                        caller.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("capability-plugin")
                                .setAction("echo")
                                .build(),
                        )

                    assertFalse(response.success)
                    assertEquals("Process not found: capability-plugin", response.errorMessage)
                    assertEquals(0, brokered.get())
                }
            }
        }

    @Test
    fun `a granted call is dispatched with exactly the admitted pair`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val brokeredRequests = CopyOnWriteArrayList<InvokeCapabilityRequest>()
                val kernel =
                    KernelServiceImpl(
                        onCapabilityInvocation = { request ->
                            brokeredRequests.add(request)
                            InvokeCapabilityResponse
                                .newBuilder()
                                .setSuccess(true)
                                .putOutput("echo", request.action)
                                .build()
                        },
                    )
                IpcTestServer(kernel).use { host ->
                    val caller = register(host, "boss-mastery-orchestrator")
                    register(
                        host,
                        "capability-plugin",
                        ADDRESS,
                        capability("run_command", "Runs a terminal command"),
                    )

                    val response =
                        caller.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("capability-plugin")
                                .setAction("run_command")
                                .putInput("command", "ls")
                                .build(),
                        )

                    assertTrue(response.success)
                    assertEquals(mapOf("echo" to "run_command"), response.outputMap)
                    val dispatched = brokeredRequests.single()
                    assertEquals("capability-plugin", dispatched.pluginId)
                    assertEquals("run_command", dispatched.action)
                }
            }
        }

    /** Records a consultation and answers with a refusal: only granted calls reach it. */
    private fun unconsultedBroker(brokered: AtomicInteger): CapabilityBroker =
        { _ ->
            brokered.incrementAndGet()
            InvokeCapabilityResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("broker should not be consulted")
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

    private companion object {
        const val ADDRESS = "tcp://127.0.0.1:59000"
    }
}
