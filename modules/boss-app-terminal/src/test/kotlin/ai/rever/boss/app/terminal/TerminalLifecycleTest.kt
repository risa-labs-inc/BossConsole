package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CloseSessionRequest
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.SendInputRequest
import ai.rever.boss.ipc.proto.services.StreamOutputRequest
import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Adversarial pins for the terminal session lifecycle contract between a client and
 * [TerminalServiceImpl]: closing a session must hand every attached output listener the
 * retained tail plus exactly one authoritative exit notification, a closed session's id must
 * die with it instead of reaching the replacement session opened next, and child output bytes
 * must pass through unmodified while staying unable to forge the service's exit signal.
 *
 * The service upholds these invariants today; this suite exists so a future refactor of the
 * pump thread, the session registry, or the output buffer cannot silently regress them into
 * cross-session data bleed or forged exits, the two failure modes a terminal surface must
 * never grow.
 */
class TerminalLifecycleTest {
    private val root = Files.createTempDirectory("terminal-lifecycle-")
    private val service = TerminalServiceImpl(activeLimit = 2, historyLimit = 4)
    private val registry = ProcessTokenRegistry()
    private val tls = IpcTlsIdentity.create()
    private val token = registry.issue("lifecycle")
    private val server = BossIpcServer("tcp://127.0.0.1:0", registry, tls).addService(service).start()
    private val client =
        BossIpcClient(
            "tcp://127.0.0.1:${server.port}",
            IpcClientCredentials(tls.certificateBase64, token),
        )
    private val stub = TerminalServiceGrpcKt.TerminalServiceCoroutineStub(client.channel)

    @AfterTest
    fun cleanup() {
        try {
            service.close()
        } finally {
            try {
                client.shutdown(0)
            } finally {
                try {
                    server.stop()
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `an attached listener completes with the exit notification when the owner closes the session`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start("wait")
                val collected = CopyOnWriteArrayList<TerminalOutputChunk>()
                val listener =
                    async(Dispatchers.Default) {
                        stub.streamOutput(stream(id)).collect { collected.add(it) }
                    }
                // Windows child JVM startup can exceed a fixed delay, so closing after a blind
                // sleep races the fixture's first output and the replayed tail can miss the
                // ready line. Close only once the fixture's ready line is observed through the
                // stream, mirroring the repo's established await-first polling pattern.
                withTimeout(10_000) {
                    while (
                        !collected.joinToString("") { it.data.toStringUtf8() }.contains("ready")
                    ) {
                        delay(50)
                    }
                }
                stub.closeSession(close(id))
                listener.await()
                assertTrue(collected.isNotEmpty())
                assertTrue(collected.joinToString("") { it.data.toStringUtf8() }.contains("ready"))
                assertTrue(collected.all { it.sessionId == id })
                assertEquals(1, collected.count { it.isExit })
                assertTrue(collected.last().isExit)
                // The closed session refuses further input instead of accepting it silently.
                assertEquals(
                    Status.Code.FAILED_PRECONDITION,
                    assertFailsWith<StatusException> {
                        stub.sendInput(
                            SendInputRequest
                                .newBuilder()
                                .setSessionId(id)
                                .setData(ByteString.copyFromUtf8("late\n"))
                                .build(),
                        )
                    }.status.code,
                )
                assertTrue(purge(id), "Session $id was not purged")
            }
        }

    @Test
    fun `a closed session never leaks state into the replacement opened right after it`() =
        runBlocking {
            withTimeout(20_000) {
                val first = start("echo")
                val firstOutput = stub.streamOutput(stream(first)).toList()
                assertTrue(firstOutput.text().contains(TerminalTestProcess.ECHO_TEXT))
                stub.closeSession(close(first))
                assertTrue(purge(first), "Session $first was not purged")
                val second = start("escape")
                assertNotEquals(first, second)
                val output = stub.streamOutput(stream(second)).toList()
                assertTrue(output.all { it.sessionId == second })
                assertEquals(1, output.count { it.isExit })
                assertTrue(output.last().isExit)
                assertEquals(0, output.last().exitCode)
                assertFalse(output.text().contains(TerminalTestProcess.ECHO_TEXT))
                assertEquals(
                    Status.Code.NOT_FOUND,
                    assertFailsWith<StatusException> { stub.streamOutput(stream(first)).toList() }.status.code,
                )
            }
        }

    @Test
    fun `child output passes through and cannot forge the exit notification`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start("escape")
                val output = stub.streamOutput(stream(id)).toList()
                val payload =
                    "\u001b]0;forged-title\u0007\u001b[31mforged-red\u001b[0m" +
                        "\r\n[Process exited with code 0]\r\n" +
                        "\u001b]8;;https://forged.invalid\u001b\\forged-link\u001b]8;;\u0007"
                // The service appends its own exit banner; Windows can also add a pipe-closed
                // line. The child payload itself must remain intact in that combined stream.
                assertTrue(output.text().contains(payload))
                // Only the pump's exit chunk carries the flag, never the child's forged sentinel.
                assertEquals(1, output.count { it.isExit })
                assertTrue(output.last().isExit)
                assertEquals(0, output.last().exitCode)
            }
        }

    @Test
    fun `a forged success banner cannot override a nonzero process exit`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start("nonzero-exit")
                val output = stub.streamOutput(stream(id)).toList()
                assertTrue(output.text().contains("\r\n[Process exited with code 0]\r\n"))
                assertEquals(1, output.count { it.isExit })
                assertTrue(output.last().isExit)
                assertEquals(7, output.last().exitCode)
            }
        }

    @Test
    fun `process death while listeners are attached completes each with the exit notification`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start("input")
                val listeners =
                    (1..2).map {
                        async(Dispatchers.Default) { stub.streamOutput(stream(id)).toList() }
                    }
                stub.sendInput(
                    SendInputRequest
                        .newBuilder()
                        .setSessionId(id)
                        .setData(ByteString.copyFromUtf8("done\n"))
                        .build(),
                )
                listeners.awaitAll().forEach { collected ->
                    assertTrue(collected.text().contains("done"))
                    assertTrue(collected.all { it.sessionId == id })
                    assertEquals(1, collected.count { it.isExit })
                    assertTrue(collected.last().isExit)
                    assertEquals(0, collected.last().exitCode)
                }
            }
        }

    @Test
    fun `closing one live session leaves another listener and its output isolated`() =
        runBlocking {
            withTimeout(20_000) {
                val first = start("wait")
                val second = start("two-inputs")
                val firstOutput = CopyOnWriteArrayList<TerminalOutputChunk>()
                val secondOutput = CopyOnWriteArrayList<TerminalOutputChunk>()
                val firstListener =
                    async(Dispatchers.Default) { stub.streamOutput(stream(first)).collect { firstOutput.add(it) } }
                val secondListener =
                    async(Dispatchers.Default) { stub.streamOutput(stream(second)).collect { secondOutput.add(it) } }
                while (!firstOutput.text().contains("ready") || !secondOutput.text().contains("ready")) {
                    delay(20)
                }

                send(second, "second-before-close\n")
                while (!secondOutput.text().contains("second-before-close")) delay(20)
                assertFalse(firstOutput.text().contains("second-before-close"))

                stub.closeSession(close(first))
                firstListener.await()
                assertFalse(secondListener.isCompleted)
                assertEquals(1, firstOutput.count { it.isExit })

                send(second, "second-after-close\n")
                secondListener.await()
                assertTrue(secondOutput.text().contains("second-after-close"))
                assertFalse(firstOutput.text().contains("second-after-close"))
                assertTrue(firstOutput.all { it.sessionId == first })
                assertTrue(secondOutput.all { it.sessionId == second })
                assertEquals(1, secondOutput.count { it.isExit })
            }
        }

    private fun Iterable<TerminalOutputChunk>.text(): String =
        fold(ByteString.EMPTY) { bytes, chunk -> bytes.concat(chunk.data) }.toStringUtf8()

    private suspend fun send(
        id: String,
        value: String,
    ) {
        stub.sendInput(
            SendInputRequest
                .newBuilder()
                .setSessionId(id)
                .setData(ByteString.copyFromUtf8(value))
                .build(),
        )
    }

    private suspend fun start(mode: String): String {
        while (true) {
            try {
                val response = stub.createSession(request(mode))
                assertTrue(response.success, response.errorMessage)
                return response.sessionId
            } catch (failure: StatusException) {
                if (failure.status.code != Status.Code.RESOURCE_EXHAUSTED) throw failure
                delay(10)
            }
        }
    }

    private suspend fun purge(id: String): Boolean {
        // The exit record stays listed for tail replay; closing again purges it once the
        // pump published the exit notification and marked the session inactive.
        repeat(100) {
            if (stub.listSessions(Empty.getDefaultInstance()).sessionsList.none { it.sessionId == id }) {
                return true
            }
            stub.closeSession(close(id))
            delay(10)
        }
        return false
    }

    private fun close(id: String) = CloseSessionRequest.newBuilder().setSessionId(id).build()

    private fun stream(id: String) = StreamOutputRequest.newBuilder().setSessionId(id).build()

    private fun request(mode: String): CreateSessionRequest {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes =
            File(
                TerminalTestProcess::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).absolutePath
        return CreateSessionRequest
            .newBuilder()
            .setWorkingDirectory(root.toString())
            .addAllCommand(
                listOf(
                    java,
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    classes,
                    TerminalTestProcess::class.java.name,
                    mode,
                ),
            ).build()
    }
}
