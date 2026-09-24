package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.HeartbeatPing
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessState
import ai.rever.boss.ipc.proto.ProcessStatusRequest
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.proto.ShutdownRequest
import ai.rever.boss.ipc.services.KernelServiceImpl
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A crashed child must leave the kernel's process table when it dies, not only on a clean
 * requestShutdown (#1180). Dead ids otherwise report RUNNING forever via getProcessStatus and
 * listProcesses, and every new child is handed the dead id's stale ipcAddress.
 *
 * [KernelServiceImpl.deregisterProcess] is the hook the kernel's failure path calls
 * (ProcessMonitor reports the death, KernelBootstrap.handleFailure evicts before respawning);
 * these tests drive that hook exactly the way the wiring does.
 */
class KernelDeadChildDeregistrationTest {
    @Test
    fun `a deregistered crash stops reporting RUNNING and forgets the heartbeat`() =
        runBlocking {
            val kernel = KernelServiceImpl()
            IpcTestServer(kernel).use { host ->
                val alpha =
                    KernelServiceGrpcKt.KernelServiceCoroutineStub(
                        host.channelFor("alpha", ADDRESS_ALPHA),
                    )
                assertTrue(alpha.registerProcess(registration("alpha", ADDRESS_ALPHA)).success)
                assertEquals(
                    ProcessState.PROCESS_STATE_RUNNING,
                    alpha.getProcessStatus(status("alpha")).state,
                )
                assertTrue(
                    alpha
                        .heartbeat(flowOf(HeartbeatPing.newBuilder().setProcessId("alpha").build()))
                        .toList()
                        .single()
                        .acknowledged,
                )
                assertTrue(kernel.getLastHeartbeat("alpha") != null)

                // The failure path evicts the dead child before any respawn takes the id back.
                assertTrue(kernel.deregisterProcess("alpha"))

                assertEquals(
                    ProcessState.PROCESS_STATE_STOPPED,
                    alpha.getProcessStatus(status("alpha")).state,
                )
                assertNull(kernel.getLastHeartbeat("alpha"))
                val supervisor =
                    KernelServiceGrpcKt.KernelServiceCoroutineStub(
                        host.channelFor("supervisor", authority = ProcessAuthority.SUPERVISOR),
                    )
                assertTrue(
                    supervisor
                        .listProcesses(Empty.getDefaultInstance())
                        .processesList
                        .none { it.processId == "alpha" },
                )
                assertFalse(kernel.deregisterProcess("alpha"))

                // A respawn re-registers the same id and is live again.
                assertTrue(alpha.registerProcess(registration("alpha", ADDRESS_ALPHA)).success)
                assertEquals(
                    ProcessState.PROCESS_STATE_RUNNING,
                    alpha.getProcessStatus(status("alpha")).state,
                )
            }
        }

    @Test
    fun `new registrants are handed live addresses only`() =
        runBlocking {
            val kernel = KernelServiceImpl()
            IpcTestServer(kernel).use { host ->
                register(host, "alpha", ADDRESS_ALPHA)
                register(host, "beta", ADDRESS_BETA)
                assertTrue(kernel.deregisterProcess("alpha"))

                val gamma =
                    KernelServiceGrpcKt.KernelServiceCoroutineStub(
                        host.channelFor("gamma", ADDRESS_GAMMA),
                    )
                val response = gamma.registerProcess(registration("gamma", ADDRESS_GAMMA))
                assertTrue(response.success)
                assertEquals(1, response.serviceAddressesCount)
                assertEquals(ADDRESS_BETA, response.serviceAddressesMap["beta"])
                assertFalse(response.serviceAddressesMap.containsKey("alpha"))
            }
        }

    @Test
    fun `the healthy register and shutdown path still deregisters on its own`() =
        runBlocking {
            val kernel = KernelServiceImpl(onShutdownRequested = { _, _ -> true })
            IpcTestServer(kernel).use { host ->
                val alpha =
                    KernelServiceGrpcKt.KernelServiceCoroutineStub(
                        host.channelFor("alpha", ADDRESS_ALPHA),
                    )
                assertTrue(alpha.registerProcess(registration("alpha", ADDRESS_ALPHA)).success)
                assertEquals(
                    ProcessState.PROCESS_STATE_RUNNING,
                    alpha.getProcessStatus(status("alpha")).state,
                )
                assertTrue(
                    alpha
                        .requestShutdown(ShutdownRequest.newBuilder().setProcessId("alpha").build())
                        .success,
                )
                assertEquals(
                    ProcessState.PROCESS_STATE_STOPPED,
                    alpha.getProcessStatus(status("alpha")).state,
                )
                // The clean shutdown already evicted the entry; a late failure eviction is a no-op.
                assertFalse(kernel.deregisterProcess("alpha"))
            }
        }

    private suspend fun register(
        host: IpcTestServer,
        processId: String,
        address: String,
    ) {
        val stub =
            KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor(processId, address))
        assertTrue(stub.registerProcess(registration(processId, address)).success)
    }

    private fun status(processId: String): ProcessStatusRequest =
        ProcessStatusRequest
            .newBuilder()
            .setProcessId(processId)
            .build()

    private fun registration(
        processId: String,
        address: String,
    ): RegisterProcessRequest =
        RegisterProcessRequest
            .newBuilder()
            .setManifest(ProcessManifest.newBuilder().setProcessId(processId))
            .setIpcAddress(address)
            .build()

    private companion object {
        const val ADDRESS_ALPHA = "tcp://127.0.0.1:59001"
        const val ADDRESS_BETA = "tcp://127.0.0.1:59002"
        const val ADDRESS_GAMMA = "tcp://127.0.0.1:59003"
    }
}
