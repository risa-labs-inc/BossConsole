package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.HeartbeatPing
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.services.KernelServiceImpl
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The heartbeat bookkeeping the kernel-side of the watchdog depends on.
 *
 * Enforcement lives in `ProcessMonitor`, but everything it can do is bounded by what the
 * kernel service records here: a registered child's beat ages out of a generous threshold
 * ([KernelServiceImpl.isHeartbeatTimedOut]) without the test ever sleeping on a wall clock, and
 * a torn-down generation's beat can be forgotten ([KernelServiceImpl.clearHeartbeat]) so the
 * replacement the failure respawns is judged on its own beats, never its predecessor's.
 */
class KernelServiceHeartbeatTest {
    @Test
    fun `a recorded heartbeat ages out and a torn-down generation is forgotten`() =
        runBlocking {
            val kernel = KernelServiceImpl()
            IpcTestServer(kernel).use { host ->
                val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor("alpha", ADDRESS))

                // No beat before registration means "never seen", not "wedged".
                assertEquals(null, kernel.getLastHeartbeat("alpha"))

                assertTrue(stub.registerProcess(registration("alpha")).success)
                assertTrue(
                    stub
                        .heartbeat(flowOf(HeartbeatPing.newBuilder().setProcessId("alpha").build()))
                        .toList()
                        .single()
                        .acknowledged,
                )

                // The fresh beat is inside a generous threshold and past a negative one, so both
                // halves of the timeout comparison are exercised deterministically.
                assertNotNull(kernel.getLastHeartbeat("alpha"))
                assertFalse(kernel.isHeartbeatTimedOut("alpha", thresholdMs = 60_000))
                assertTrue(kernel.isHeartbeatTimedOut("alpha", thresholdMs = -1))

                // Forgetting the generation is what keeps a replacement from inheriting this beat.
                kernel.clearHeartbeat("alpha")
                assertEquals(null, kernel.getLastHeartbeat("alpha"))
            }
        }

    private fun registration(processId: String): RegisterProcessRequest =
        RegisterProcessRequest
            .newBuilder()
            .setManifest(ProcessManifest.newBuilder().setProcessId(processId))
            .setIpcAddress(ADDRESS)
            .build()

    private companion object {
        const val ADDRESS = "tcp://127.0.0.1:59001"
    }
}
