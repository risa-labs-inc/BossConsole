package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.services.CloseInputRequest
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.SendInputRequest
import ai.rever.boss.ipc.proto.services.StreamOutputRequest
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression coverage for issue #928: a stdin-consuming one-shot command (`sort`, `grep`, `cat`
 * with no args, ...) blocked forever because nothing in [TerminalSession] ever delivered EOF on
 * the child's stdin. `CloseInput` fixes that; these tests drive the real gRPC service (not just
 * the in-process [TerminalSession] object) with `TerminalTestProcess`'s `drain-stdin` fixture,
 * which mirrors that exact class of command: it reads until EOF, never until a newline, so it can
 * only exit once stdin is actually closed.
 */
class TerminalCloseInputTest {
    private val root = Files.createTempDirectory("terminal-close-input-")
    private val service = TerminalServiceImpl()
    private val registry = ProcessTokenRegistry()
    private val tls = IpcTlsIdentity.create()
    private val token = registry.issue("close-input")
    private val server = BossIpcServer("tcp://127.0.0.1:0", registry, tls).addService(service).start()
    private val client =
        BossIpcClient("tcp://127.0.0.1:${server.port}", IpcClientCredentials(tls.certificateBase64, token))
    private val stub = TerminalServiceGrpcKt.TerminalServiceCoroutineStub(client.channel)

    @AfterTest
    fun cleanup() {
        service.close()
        client.shutdown(0)
        server.stop()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `closing input delivers EOF so a stdin-draining command exits with its output`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start()
                sendInput(id, "hello")

                stub.closeInput(CloseInputRequest.newBuilder().setSessionId(id).build())

                val output = stub.streamOutput(stream(id)).toList()
                val text = output.joinToString("") { it.data.toStringUtf8() }
                assertTrue(text.contains("drained:5"), "expected the 5 bytes sent before EOF to be reported: $text")
                assertTrue(output.last().isExit, "the process must actually exit once EOF is delivered")
                assertEquals(0, output.last().exitCode)
            }
        }

    @Test
    fun `closing input with nothing sent still delivers EOF`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start()

                stub.closeInput(CloseInputRequest.newBuilder().setSessionId(id).build())

                val output = stub.streamOutput(stream(id)).toList()
                assertTrue(output.joinToString("") { it.data.toStringUtf8() }.contains("drained:0"))
                assertTrue(output.last().isExit)
            }
        }

    @Test
    fun `closing input twice is a no-op, not an error`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start()
                val request = CloseInputRequest.newBuilder().setSessionId(id).build()

                stub.closeInput(request)
                stub.closeInput(request)

                assertTrue(
                    stub
                        .streamOutput(stream(id))
                        .toList()
                        .last()
                        .isExit,
                )
            }
        }

    @Test
    fun `sending input after closing it is refused while the process is still alive`() =
        runBlocking {
            withTimeout(15_000) {
                // drain-stdin-hold stays alive after EOF, so the refusal below has to come from the
                // closed-stdin precondition - not from the process having already exited.
                val id = start("drain-stdin-hold")
                stub.closeInput(CloseInputRequest.newBuilder().setSessionId(id).build())
                val seen = StringBuilder()
                stub
                    .streamOutput(stream(id))
                    .takeWhile { chunk ->
                        assertTrue(!chunk.isExit, "the fixture must still be running: $seen")
                        seen.append(chunk.data.toStringUtf8())
                        "drained:" !in seen
                    }.collect()

                val failure =
                    assertFailsWith<StatusException> {
                        stub.sendInput(
                            SendInputRequest
                                .newBuilder()
                                .setSessionId(id)
                                .setData(ByteString.copyFromUtf8("too-late"))
                                .build(),
                        )
                    }
                assertEquals(Status.Code.FAILED_PRECONDITION, failure.status.code)
                assertEquals("Terminal input has been closed", failure.status.description)
            }
        }

    @Test
    fun `closing input while a write holds the input lock answers busy instead of hanging`() =
        runBlocking {
            val process =
                ProcessBuilder(fixtureCommand("hold-output", root.resolve("pid").toString()))
                    .redirectErrorStream(true)
                    .start()
            val session =
                TerminalSession(
                    "wedged",
                    root.toString(),
                    emptyList(),
                    process,
                    80,
                    24,
                    inputWriteTimeoutMillis = 30_000,
                    inputQueueTimeoutMillis = QUEUE_TIMEOUT_MILLIS,
                )
            // hold-output never reads stdin, and 1 MiB is past any OS pipe buffer, so this send
            // holds the input lock until its own (long) write deadline.
            val writer = launch(Dispatchers.IO) { runCatching { session.send(ByteArray(1 shl 20)) } }
            try {
                // An empty send takes a free lock and returns; it is answered busy once the writer holds it.
                val deadline = System.currentTimeMillis() + 10_000
                while (runCatching { session.send(ByteArray(0)) }.isSuccess) {
                    assertTrue(System.currentTimeMillis() < deadline, "the writer never took the input lock")
                    delay(10)
                }

                val started = System.nanoTime()
                // Bounded, so a close that waits on the lock without limit fails here instead of
                // hanging the suite until the writer's 30 s deadline.
                val failure =
                    withTimeoutOrNull(10_000) { runCatching { session.closeStdin() }.exceptionOrNull() }
                        ?: fail("closeStdin did not answer while the input lock was held")
                val waitedMs = (System.nanoTime() - started) / 1_000_000

                assertTrue(failure is StatusRuntimeException, "expected a gRPC status, got $failure")
                assertEquals(Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
                assertEquals("Terminal input is busy", failure.status.description)
                assertTrue(waitedMs >= QUEUE_TIMEOUT_MILLIS - 50, "gave up after only ${waitedMs}ms")
            } finally {
                session.terminate()
                writer.join()
            }
        }

    private suspend fun sendInput(
        id: String,
        text: String,
    ) {
        stub.sendInput(
            SendInputRequest
                .newBuilder()
                .setSessionId(id)
                .setData(ByteString.copyFromUtf8(text))
                .build(),
        )
    }

    private suspend fun start(mode: String = "drain-stdin"): String {
        val response = stub.createSession(request(mode))
        assertTrue(response.success, response.errorMessage)
        return response.sessionId
    }

    private fun stream(id: String) = StreamOutputRequest.newBuilder().setSessionId(id).build()

    private fun request(mode: String): CreateSessionRequest =
        CreateSessionRequest
            .newBuilder()
            .setWorkingDirectory(root.toString())
            .addAllCommand(fixtureCommand(mode, root.toString()))
            .build()

    private fun fixtureCommand(
        mode: String,
        argument: String,
    ): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes =
            File(
                TerminalTestProcess::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).absolutePath
        return listOf(java, "-cp", classes, TerminalTestProcess::class.java.name, mode, argument)
    }

    private companion object {
        const val QUEUE_TIMEOUT_MILLIS = 300L
    }
}
