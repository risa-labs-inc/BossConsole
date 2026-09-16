package ai.rever.boss.kernel

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.proto.RepairAction
import ai.rever.boss.process.FailureReason
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessFailure
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RepairAdviceTest {
    @Test
    fun `request boundary handles a retained dead orchestrator then another failure`() =
        runBlocking {
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val child =
                ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-version")
                    .redirectErrorStream(true)
                    .start()
            child.inputStream.use { it.readBytes() }
            child.waitFor()
            val id = KernelBootstrap.ORCHESTRATOR_PROCESS_ID
            val managed = ManagedProcess(ProcessConfig(id, ProcessType.ORCHESTRATOR, id, "unused"), child, "unused")
            val retained = BossIpcClient("tcp://127.0.0.1:1", IpcClientCredentials("unused", "0".repeat(64)))
            retained.shutdown()
            // The setter is module-internal; install the retained handle without launching a kernel.
            ManagedProcess::class.java
                .getDeclaredField("ipcClient")
                .apply { isAccessible = true }
                .set(managed, retained)
            val registry = ProcessRegistry().apply { register(id, managed) }
            val kernel = KernelBootstrap()
            for (failedId in listOf("first", "later")) {
                assertNull(kernel.requestRepairAdvice(registry, ProcessFailure(failedId, FailureReason.PROCESS_EXIT)))
            }
        }

    @Test
    fun `unsupported channel transport does not prevent later advice`() =
        runBlocking {
            assertNull(repairAdviceOrNull("unsupported") { throw UnsupportedOperationException("transport") })
            assertEquals(
                "later",
                repairAdviceOrNull("later") {
                    RepairAction.newBuilder().setDescription("later").build()
                }?.description,
            )
        }

    @Test
    fun `a retained dead client does not stop later recovery`() =
        runBlocking {
            // Closing before first connection needs no live endpoint or certificate.
            val retained = BossIpcClient("tcp://127.0.0.1:1", IpcClientCredentials("unused", "0".repeat(64)))
            retained.shutdown()
            val results = mutableListOf<RepairAction?>()
            for (failure in listOf("first", "later")) {
                results +=
                    repairAdviceOrNull(failure) {
                        if (failure == "first") retained.channel
                        RepairAction.newBuilder().setDescription(failure).build()
                    }
            }
            assertNull(results.first())
            assertEquals("later", results.last()?.description)
        }

    @Test
    fun `caller cancellation is not converted to missing advice`() =
        runBlocking {
            assertFailsWith<CancellationException> {
                repairAdviceOrNull("cancelled") { throw CancellationException("cancelled") }
            }
        }
}
