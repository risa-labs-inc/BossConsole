package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CloseSessionRequest
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.ResizeRequest
import ai.rever.boss.ipc.proto.services.SendInputRequest
import ai.rever.boss.ipc.proto.services.StreamOutputRequest
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import com.google.protobuf.ByteString
import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.kotlin.GrpcContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalLimitsTest {
    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = LinkedBlockingQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queued.add(block)
        }

        fun next(): Runnable = checkNotNull(queued.poll(5, TimeUnit.SECONDS)) { "Expected a dispatched continuation" }
    }

    private val root = Files.createTempDirectory("terminal-limits-")
    private val service = TerminalServiceImpl(activeLimit = 1, historyLimit = 2)
    private val registry = ProcessTokenRegistry()
    private val tls = IpcTlsIdentity.create()
    private val token = registry.issue("limits")
    private val server = BossIpcServer("tcp://127.0.0.1:0", registry, tls).addService(service).start()
    private val client =
        BossIpcClient("tcp://127.0.0.1:${server.port}", IpcClientCredentials(tls.certificateBase64, token))
    private val stub = TerminalServiceGrpcKt.TerminalServiceCoroutineStub(client.channel)
    private val callerContext =
        GrpcContextElement(
            Context.ROOT.withValue(ProcessIdentityInterceptor.CURRENT_PRINCIPAL, { registry.principalFor(token) }),
        )

    @AfterTest
    fun cleanup() =
        runBlocking {
            stub.listSessions(Empty.getDefaultInstance()).sessionsList.forEach { session ->
                stub.closeSession(CloseSessionRequest.newBuilder().setSessionId(session.sessionId).build())
            }
            client.shutdown(0)
            server.stop()
            service.close()
            root.toFile().deleteRecursively()
            Unit
        }

    @Test
    fun `empty command arguments reach the child unchanged`() =
        runBlocking {
            withTimeout(10_000) {
                val response = stub.createSession(request("argument").toBuilder().addCommand("").build())
                val output = stub.streamOutput(stream(response.sessionId)).toList()
                assertTrue(output.any { it.data.toStringUtf8().contains("argument-length=0") })
                assertTrue(output.last().isExit)
            }
        }

    @Test
    fun `invalid environment produces INVALID_ARGUMENT and returns admission`() =
        runBlocking {
            val invalid =
                listOf(
                    CreateSessionRequest
                        .newBuilder()
                        .addCommand("unused")
                        .putEnvironment("bad=name", "value")
                        .build(),
                    CreateSessionRequest
                        .newBuilder()
                        .addCommand("unused")
                        .putEnvironment("KEY", "bad\u0000value")
                        .build(),
                    CreateSessionRequest.newBuilder().addCommand("bad\u0000command").build(),
                )
            for (request in invalid) {
                val failure = assertFailsWith<StatusException> { stub.createSession(request) }
                assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            }
            assertTrue(start("echo").isNotBlank())
        }

    @Test
    fun `late subscription replays output and completes with the exit notification`() =
        runBlocking {
            withTimeout(10_000) {
                val id = start("echo")
                awaitExit(id)
                val output = stub.streamOutput(stream(id)).toList()
                assertTrue(output.joinToString("") { it.data.toStringUtf8() }.contains("hello café 世界"))
                assertTrue(output.last().isExit)
                assertEquals(0, output.last().exitCode)
            }
        }

    @Test
    fun `concurrent launches respect capacity and closing frees it for a new session`() =
        runBlocking {
            withTimeout(15_000) {
                val results =
                    (1..12)
                        .map {
                            async(Dispatchers.Default) {
                                try {
                                    stub.createSession(request("wait")).sessionId
                                } catch (failure: StatusException) {
                                    assertEquals(Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
                                    null
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                assertEquals(1, results.size)
                val id = results.single()
                stub.closeSession(CloseSessionRequest.newBuilder().setSessionId(id).build())
                assertTrue(
                    stub
                        .streamOutput(stream(id))
                        .toList()
                        .last()
                        .isExit,
                )
                val next = start("echo")
                assertTrue(
                    stub
                        .streamOutput(stream(next))
                        .toList()
                        .last()
                        .isExit,
                )
            }
        }

    @Test
    fun `completed sessions and flood output remain bounded`() =
        runBlocking {
            withTimeout(30_000) {
                val first = start("echo")
                stub.streamOutput(stream(first)).toList()
                repeat(3) {
                    val id = start("flood")
                    awaitExit(id)
                    val output = stub.streamOutput(stream(id)).toList()
                    assertTrue(output.sumOf { it.serializedSize } <= 1_048_576)
                    assertTrue(output.last().isExit)
                }
                val retained = stub.listSessions(Empty.getDefaultInstance()).sessionsList
                assertEquals(2, retained.size)
                assertFalse(retained.any { it.sessionId == first })
            }
        }

    @Test
    fun `normal input works and child credentials are stripped after environment overrides`() =
        runBlocking {
            withTimeout(15_000) {
                val input = start("input")
                stub.sendInput(
                    SendInputRequest
                        .newBuilder()
                        .setSessionId(input)
                        .setData(ByteString.copyFromUtf8("received\n"))
                        .build(),
                )
                assertTrue(
                    stub
                        .streamOutput(stream(input))
                        .toList()
                        .joinToString("") { it.data.toStringUtf8() }
                        .contains("received"),
                )
                val environment = start("environment")
                val text = stub.streamOutput(stream(environment)).toList().joinToString("") { it.data.toStringUtf8() }
                assertTrue(text.contains("null:preserved"))
                assertFalse(text.contains("credential-sentinel"))
            }
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

    @Test
    fun `cancellation during return dispatch terminates the unclaimed process`() =
        runBlocking {
            withTimeout(15_000) {
                val dispatcher = PausedDispatcher()
                val scope = CoroutineScope(SupervisorJob() + dispatcher + callerContext)
                val creation = scope.async { service.createSession(request("wait")) }
                dispatcher.next().run()
                val returning = dispatcher.next()
                val unclaimed =
                    stub
                        .listSessions(Empty.getDefaultInstance())
                        .sessionsList
                        .single()
                        .sessionId
                creation.cancel()
                returning.run()
                assertFailsWith<CancellationException> { creation.await() }
                awaitExit(unclaimed)
                val replacement = start("echo")
                assertTrue(
                    stub
                        .streamOutput(stream(replacement))
                        .toList()
                        .last()
                        .isExit,
                )
            }
        }

    @Test
    fun `launch failure releases admission and service shutdown terminates active sessions`() =
        runBlocking {
            withTimeout(15_000) {
                val failed =
                    stub.createSession(
                        request("echo").toBuilder().setCommand(0, root.resolve("missing-command").toString()).build(),
                    )
                assertFalse(failed.success)
                val id = start("wait")
                service.close()
                awaitExit(id)
                assertEquals(
                    Status.Code.UNAVAILABLE,
                    assertFailsWith<StatusException> { stub.createSession(request("echo")) }.status.code,
                )
                Unit
            }
        }

    @Test
    fun `an exited shell releases capacity while a descendant still holds stdout`() =
        runBlocking {
            try {
                // JVM fixture startup is not the lifecycle deadline, especially on a busy Windows runner.
                val id =
                    withTimeout(30_000) {
                        val launched = start("background")
                        while (!Files.exists(root.resolve("descendant.pid"))) delay(10)
                        launched
                    }
                val replacement =
                    withTimeout(5_000) {
                        awaitExit(id)
                        val descendant =
                            ProcessHandle.of(Files.readString(root.resolve("descendant.pid")).toLong()).orElseThrow()
                        assertTrue(descendant.isAlive)
                        assertTrue(
                            stub
                                .streamOutput(stream(id))
                                .toList()
                                .last()
                                .isExit,
                        )
                        // Admission must be reusable inside the same five-second lifecycle bound.
                        start("echo")
                    }
                // The replacement's cold JVM execution has its own deadline, not the parent's cleanup budget.
                withTimeout(10_000) {
                    assertTrue(
                        stub
                            .streamOutput(stream(replacement))
                            .toList()
                            .last()
                            .isExit,
                    )
                }
            } finally {
                val pid = root.resolve("descendant.pid")
                if (Files.exists(pid)) {
                    ProcessHandle.of(Files.readString(pid).toLong()).ifPresent { it.destroyForcibly() }
                }
            }
        }

    @Test
    fun `invalid launch input and resize limits return actionable grpc statuses`() =
        runBlocking {
            withTimeout(10_000) {
                val oversized = request("echo").toBuilder().putEnvironment("LARGE", "x".repeat(131_072)).build()
                val dimensions = request("echo").toBuilder().setCols(1001).build()
                for (invalid in listOf(oversized, dimensions)) {
                    assertEquals(
                        Status.Code.INVALID_ARGUMENT,
                        assertFailsWith<StatusException> { stub.createSession(invalid) }.status.code,
                    )
                }
                val id = start("wait")
                val input =
                    SendInputRequest
                        .newBuilder()
                        .setSessionId(id)
                        .setData(ByteString.copyFrom(ByteArray(65_537)))
                        .build()
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    assertFailsWith<StatusException> { stub.sendInput(input) }.status.code,
                )
                val resize =
                    ResizeRequest
                        .newBuilder()
                        .setSessionId(id)
                        .setCols(0)
                        .setRows(24)
                        .build()
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    assertFailsWith<StatusException> { stub.resize(resize) }.status.code,
                )
            }
        }

    private suspend fun awaitExit(id: String) {
        while (stub
                .listSessions(Empty.getDefaultInstance())
                .sessionsList
                .single { it.sessionId == id }
                .isAlive
        ) {
            delay(10)
        }
        // Let the pipe drain before intentionally connecting as a late reader.
        delay(100)
    }

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
                    root.resolve("descendant.pid").toString(),
                ),
            ).putEnvironment("BOSS_PROCESS_TOKEN", "credential-sentinel")
            .putEnvironment("TERMINAL_TEST_VALUE", "preserved")
            .build()
    }
}
