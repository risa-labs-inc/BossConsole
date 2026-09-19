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
        service.close()
        clients.forEach { it.shutdown(0) }
        server.stop()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `only the host can start a process`() =
        runBlocking {
            withTimeout(15_000) {
                val unauthorized = client("0".repeat(64))
                val request = request("sentinel")
                refused(Status.Code.UNAUTHENTICATED) { unauthorized.createSession(request) }
                assertFalse(Files.exists(root.resolve("sentinel")))
                val process = caller("plugin")
                refused(Status.Code.PERMISSION_DENIED) { process.createSession(request) }
                assertFalse(Files.exists(root.resolve("sentinel")))
                val host = caller("host", ProcessAuthority.HOST)
                assertEquals(0, host.listSessions(Empty.getDefaultInstance()).sessionsCount)
                val created = host.createSession(request)
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
    fun `process callers cannot list read write resize or close a host terminal`() =
        runBlocking {
            withTimeout(15_000) {
                val host = caller("host", ProcessAuthority.HOST)
                val process = caller("plugin")
                val id = host.createSession(request("wait")).sessionId
                assertEquals(0, process.listSessions(Empty.getDefaultInstance()).sessionsCount)
                refused(Status.Code.PERMISSION_DENIED) {
                    process.sendInput(
                        SendInputRequest
                            .newBuilder()
                            .setSessionId(id)
                            .setData(ByteString.copyFromUtf8("x"))
                            .build(),
                    )
                }
                refused(Status.Code.PERMISSION_DENIED) { process.streamOutput(stream(id)).toList() }
                refused(Status.Code.PERMISSION_DENIED) {
                    process.resize(
                        ResizeRequest
                            .newBuilder()
                            .setSessionId(id)
                            .setCols(80)
                            .setRows(24)
                            .build(),
                    )
                }
                refused(Status.Code.PERMISSION_DENIED) { process.closeSession(close(id)) }
                assertTrue(
                    host
                        .listSessions(Empty.getDefaultInstance())
                        .sessionsList
                        .single()
                        .isAlive,
                )
                assertOwnedOutput(host, id)
                closeAndRemoveHistory(host, id)
                assertEquals(0, host.listSessions(Empty.getDefaultInstance()).sessionsCount)
            }
        }

    @Test
    fun `rotating the host token revokes the old caller without orphaning its terminal`() =
        runBlocking {
            withTimeout(15_000) {
                val original = caller("host", ProcessAuthority.HOST)
                val id = original.createSession(request("wait")).sessionId
                val replacement = caller("host", ProcessAuthority.HOST)
                refused(Status.Code.UNAUTHENTICATED) { original.listSessions(Empty.getDefaultInstance()) }
                val survivingIds =
                    replacement
                        .listSessions(Empty.getDefaultInstance())
                        .sessionsList
                        .map { it.sessionId }
                assertEquals(listOf(id), survivingIds)
                closeAndRemoveHistory(replacement, id)
                assertEquals(0, replacement.listSessions(Empty.getDefaultInstance()).sessionsCount)
            }
        }

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
