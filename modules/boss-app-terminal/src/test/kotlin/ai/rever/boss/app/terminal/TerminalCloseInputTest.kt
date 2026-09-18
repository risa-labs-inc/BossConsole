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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `sending input after closing it is refused rather than silently dropped`() =
        runBlocking {
            withTimeout(15_000) {
                val id = start()
                stub.closeInput(CloseInputRequest.newBuilder().setSessionId(id).build())
                // Let the fixture actually observe EOF and start exiting before the next write races it.
                stub.streamOutput(stream(id)).toList()

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

    private suspend fun start(): String {
        val response = stub.createSession(request())
        assertTrue(response.success, response.errorMessage)
        return response.sessionId
    }

    private fun stream(id: String) = StreamOutputRequest.newBuilder().setSessionId(id).build()

    private fun request(): CreateSessionRequest {
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
                listOf(java, "-cp", classes, TerminalTestProcess::class.java.name, "drain-stdin", root.toString()),
            ).build()
    }
}
