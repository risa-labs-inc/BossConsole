package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CloseSessionRequest
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.ResizeRequest
import ai.rever.boss.ipc.proto.services.SendInputRequest
import ai.rever.boss.ipc.proto.services.StreamOutputRequest
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalOwnershipTest {
    private val root = Files.createTempDirectory("terminal-ownership-")
    private val registry = ProcessTokenRegistry()
    private val identity = IpcTlsIdentity.create()
    private val service = TerminalServiceImpl()
    private val server = BossIpcServer("tcp://127.0.0.1:0", registry, identity).addService(service).start()
    private val clients = mutableListOf<BossIpcClient>()

    @AfterTest
    fun cleanup() {
        runBlocking {
            val host = caller("cleanup", ProcessAuthority.HOST)
            host.listSessions(Empty.getDefaultInstance()).sessionsList.forEach {
                host.closeSession(close(it.sessionId))
            }
        }
        clients.forEach { it.shutdown(0) }
        server.stop()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `unrecognized caller cannot start a process and an authorized caller can`() =
        runBlocking {
            withTimeout(15_000) {
                val unauthorized = client("0".repeat(64))
                val request = request("sentinel")
                refused(Status.Code.UNAUTHENTICATED) { unauthorized.createSession(request) }
                assertFalse(Files.exists(root.resolve("sentinel")))
                val owner = caller("owner")
                assertEquals(0, owner.listSessions(Empty.getDefaultInstance()).sessionsCount)
                val created = owner.createSession(request)
                assertTrue(created.success)
                while (!Files.exists(root.resolve("sentinel"))) delay(20)
                assertEquals("started", Files.readString(root.resolve("sentinel")))
            }
        }

    @Test
    fun `owners cannot list read write resize or close another terminal while the host can administer it`() =
        runBlocking {
            withTimeout(15_000) {
                val alpha = caller("alpha")
                val beta = caller("beta")
                val host = caller("host", ProcessAuthority.HOST)
                val id = alpha.createSession(request("wait")).sessionId
                assertEquals(0, beta.listSessions(Empty.getDefaultInstance()).sessionsCount)
                refused(Status.Code.PERMISSION_DENIED) {
                    beta.sendInput(
                        SendInputRequest
                            .newBuilder()
                            .setSessionId(id)
                            .setData(ByteString.copyFromUtf8("x"))
                            .build(),
                    )
                }
                refused(Status.Code.PERMISSION_DENIED) { beta.streamOutput(stream(id)).toList() }
                refused(Status.Code.PERMISSION_DENIED) {
                    beta.resize(
                        ResizeRequest
                            .newBuilder()
                            .setSessionId(id)
                            .setCols(80)
                            .setRows(24)
                            .build(),
                    )
                }
                refused(Status.Code.PERMISSION_DENIED) { beta.closeSession(close(id)) }
                assertTrue(
                    alpha
                        .listSessions(Empty.getDefaultInstance())
                        .sessionsList
                        .single()
                        .isAlive,
                )
                val betaSession = beta.createSession(request("echo")).sessionId
                assertOwnedOutput(beta, betaSession)
                val betaSessions = beta.listSessions(Empty.getDefaultInstance()).sessionsList
                assertEquals(listOf(betaSession), betaSessions.map { it.sessionId })
                assertEquals(2, host.listSessions(Empty.getDefaultInstance()).sessionsCount)
                host.closeSession(close(id))
                assertEquals(0, alpha.listSessions(Empty.getDefaultInstance()).sessionsCount)
            }
        }

    @Test
    fun `reusing a process id cannot acquire the previous instances terminal`() =
        runBlocking {
            withTimeout(15_000) {
                val original = caller("same-id")
                val id = original.createSession(request("wait")).sessionId
                val replacement = caller("same-id")
                refused(Status.Code.UNAUTHENTICATED) { original.listSessions(Empty.getDefaultInstance()) }
                assertEquals(0, replacement.listSessions(Empty.getDefaultInstance()).sessionsCount)
                refused(Status.Code.PERMISSION_DENIED) { replacement.streamOutput(stream(id)).toList() }
                val host = caller("host", ProcessAuthority.HOST)
                host.closeSession(close(id))
                assertEquals(0, host.listSessions(Empty.getDefaultInstance()).sessionsCount)
            }
        }

    private suspend fun assertOwnedOutput(
        client: TerminalServiceGrpcKt.TerminalServiceCoroutineStub,
        id: String,
    ) = coroutineScope {
        val output =
            async {
                client.streamOutput(stream(id)).first { it.data.toStringUtf8().contains("owned-output") }
            }
        while (!output.isCompleted) {
            client.sendInput(
                SendInputRequest
                    .newBuilder()
                    .setSessionId(id)
                    .setData(ByteString.copyFromUtf8("owned-output\n"))
                    .build(),
            )
            delay(20)
        }
        assertTrue(
            output
                .await()
                .data
                .toStringUtf8()
                .contains("owned-output"),
        )
    }

    private fun caller(
        id: String,
        authority: ProcessAuthority = ProcessAuthority.PROCESS,
    ) = client(registry.issue(id, authority))

    private fun client(token: String): TerminalServiceGrpcKt.TerminalServiceCoroutineStub {
        val client =
            BossIpcClient(
                "tcp://127.0.0.1:${server.port}",
                IpcClientCredentials(identity.certificateBase64, token),
            )
        clients.add(client)
        return TerminalServiceGrpcKt.TerminalServiceCoroutineStub(client.channel)
    }

    private fun request(mode: String): CreateSessionRequest {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes =
            File(
                TerminalOwnershipProcess::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).absolutePath
        return CreateSessionRequest
            .newBuilder()
            .setWorkingDirectory(root.toString())
            .addAllCommand(listOf(java, "-cp", classes, TerminalOwnershipProcess::class.java.name, mode))
            .putEnvironment("TERMINAL_SENTINEL", root.resolve("sentinel").toString())
            .build()
    }

    private fun stream(id: String) = StreamOutputRequest.newBuilder().setSessionId(id).build()

    private fun close(id: String) = CloseSessionRequest.newBuilder().setSessionId(id).build()

    private suspend fun refused(
        code: Status.Code,
        action: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<StatusException> { action() }
        assertEquals(code, failure.status.code)
    }
}
