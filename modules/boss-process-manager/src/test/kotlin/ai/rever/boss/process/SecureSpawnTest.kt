package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.StateKey
import ai.rever.boss.ipc.proto.StateServiceGrpcKt
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecureSpawnTest {
    @Test
    fun `spawned JVM registers over TLS and accepts only its issued host channel`() =
        runBlocking {
            val logs = Files.createTempDirectory("secure-spawn-logs-")
            val tokens = ProcessTokenRegistry()
            val identity = IpcTlsIdentity.create()
            val kernel = KernelServiceImpl()
            val server = BossIpcServer("tcp://127.0.0.1:0", tokens, identity).addService(kernel).start()
            var child: ManagedProcess? = null
            try {
                child =
                    ProcessSpawner("tcp://127.0.0.1:${server.port}", logs.toFile(), null, tokens, identity).spawn(
                        ProcessConfig(
                            processId = "secure",
                            processType = ProcessType.SERVICE,
                            displayName = "Secure spawn fixture",
                            mainClass = SecureSpawnFixture::class.java.name,
                            classpath = System.getProperty("boss.test.classpath"),
                            environment =
                                listOf(
                                    IpcEnvironment.PROCESS_TOKEN,
                                    IpcEnvironment.KERNEL_CERTIFICATE,
                                    IpcEnvironment.SERVER_CERTIFICATE,
                                    IpcEnvironment.SERVER_PRIVATE_KEY,
                                    IpcEnvironment.HOST_TOKEN,
                                ).associateWith { "caller-supplied-invalid-credential" },
                        ),
                    )
                val client = assertNotNull(child.ipcClient)
                assertTrue(
                    client.waitForReady(20_000),
                    "Child bootstrap stderr: ${Files.readString(logs.resolve("secure/stderr.log")).takeLast(8_000)}",
                )
                val channel = client.channel
                val value =
                    StateServiceGrpcKt
                        .StateServiceCoroutineStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getState(StateKey.newBuilder().setKey("ready").build())
                assertEquals("ready", value.key)
                assertEquals(1, kernel.registeredCount)
                child.destroyForcibly()
                assertTrue(child.process.waitFor(10, TimeUnit.SECONDS))
                withTimeout(5_000) { while (!channel.isShutdown) delay(10) }
                assertTrue(channel.isShutdown)
            } finally {
                child?.destroyForcibly()
                child?.process?.waitFor(10, TimeUnit.SECONDS)
                child?.ipcClient?.shutdown(0)
                server.stop(0)
                logs.toFile().deleteRecursively()
            }
        }
}

/** A separate JVM exercises the actual protected environment and child bootstrap. */
object SecureSpawnFixture {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking {
            val bootstrap = ChildProcessBootstrap()
            val manifest = ProcessManifest.newBuilder().setProcessId(bootstrap.processId).build()
            val connection = bootstrap.connect(manifest)
            try {
                connection.processServer.addService(StateServiceImpl())
                connection.startServer().awaitTermination()
            } finally {
                connection.shutdown()
            }
        }
}
