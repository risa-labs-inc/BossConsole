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
import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        service.close()
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
                // File creation becomes visible before the child finishes writing its readiness marker.
                val sentinel = root.resolve("sentinel")
                while (!Files.exists(sentinel) || Files.readString(sentinel) != "started") {
                    delay(20)
                }
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
                closeAndRemoveHistory(host, id)
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
                closeAndRemoveHistory(host, id)
                assertEquals(0, host.listSessions(Empty.getDefaultInstance()).sessionsCount)
            }
        }

    // streamOutput checks ownership once at stream start (#1321). These pin the safety net that rule relies on:
    // ProcessIdentityInterceptor must still end an admitted stream as soon as its credential is revoked.
    @Test
    fun `revoking the owner credential closes an idle terminal output stream`() =
        runBlocking {
            withTimeout(15_000) {
                val owner = caller("idle-owner")
                val host = caller("host", ProcessAuthority.HOST)
                val id = owner.createSession(request("echo")).sessionId
                val received = Channel<TerminalOutputChunk>(Channel.UNLIMITED)
                val subscription =
                    async {
                        refused(Status.Code.UNAUTHENTICATED) {
                            owner.streamOutput(stream(id)).collect { received.send(it) }
                        }
                    }
                awaitOutput(owner, id, received, "before-revoke")
                // No further input, so the admitted stream is idle with nothing left to emit when access is revoked.
                registry.revoke("idle-owner")
                subscription.await()
                assertTrue(isAlive(host, id), "The stream must close because of revocation, not process exit")
                closeAndRemoveHistory(host, id)
            }
        }

    @Test
    fun `replacing the owner credential closes an active terminal output stream while output keeps flowing`() =
        runBlocking {
            withTimeout(15_000) {
                val owner = caller("active-owner")
                val host = caller("host", ProcessAuthority.HOST)
                val id = owner.createSession(request("echo")).sessionId
                val received = Channel<TerminalOutputChunk>(Channel.UNLIMITED)
                val subscription =
                    async {
                        refused(Status.Code.UNAUTHENTICATED) {
                            owner.streamOutput(stream(id)).collect { received.send(it) }
                        }
                    }
                awaitOutput(owner, id, received, "before-revoke")
                // Keep the session producing output, so the stream is mid-emission when the credential goes away.
                val pump =
                    launch {
                        while (true) {
                            host.sendInput(input(id, "still-running\n"))
                            delay(10)
                        }
                    }
                received.receive()
                // Re-issuing the same process id revokes the previous credential.
                caller("active-owner")
                subscription.await()
                pump.cancelAndJoin()
                // The session still produces output for an authorized reader; only the revoked reader was cut off.
                assertOwnedOutput(host, id)
                closeAndRemoveHistory(host, id)
            }
        }

    private suspend fun awaitOutput(
        client: TerminalServiceGrpcKt.TerminalServiceCoroutineStub,
        id: String,
        received: Channel<TerminalOutputChunk>,
        marker: String,
    ) {
        client.sendInput(input(id, "$marker\n"))
        // Skip any earlier chunks until the marker has been echoed back through the stream.
        do {
            val text = received.receive().data.toStringUtf8()
        } while (!text.contains(marker))
    }

    private suspend fun isAlive(
        host: TerminalServiceGrpcKt.TerminalServiceCoroutineStub,
        id: String,
    ) = host
        .listSessions(Empty.getDefaultInstance())
        .sessionsList
        .single { it.sessionId == id }
        .isAlive

    private fun input(
        id: String,
        text: String,
    ) = SendInputRequest
        .newBuilder()
        .setSessionId(id)
        .setData(ByteString.copyFromUtf8(text))
        .build()

    private suspend fun closeAndRemoveHistory(
        host: TerminalServiceGrpcKt.TerminalServiceCoroutineStub,
        id: String,
    ) {
        host.closeSession(close(id))
        // Bounded terminal history retains exit output until a stopped session is explicitly removed.
        while (host.listSessions(Empty.getDefaultInstance()).sessionsList.any { it.sessionId == id }) {
            host.closeSession(close(id))
            delay(10)
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
            .putEnvironment("BOSS_TEST_NORMAL", "preserved")
            .putEnvironment("BOSS_PROCESS_TOKEN", "synthetic-process-token")
            .putEnvironment("BOSS_KERNEL_TLS_CERT", "synthetic-kernel-cert")
            .putEnvironment("BOSS_IPC_TLS_CERT", "synthetic-server-cert")
            .putEnvironment("BOSS_IPC_TLS_KEY", "synthetic-server-key")
            .putEnvironment("BOSS_HOST_TOKEN", "synthetic-host-token")
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
