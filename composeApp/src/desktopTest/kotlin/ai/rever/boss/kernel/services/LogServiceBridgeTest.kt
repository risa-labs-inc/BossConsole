package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.LogFilterProto
import ai.rever.boss.ipc.proto.services.LogServiceGrpcKt
import ai.rever.boss.ipc.proto.services.LogStringRequest
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogEntryData
import ai.rever.boss.plugin.api.LogFilterData
import ai.rever.boss.plugin.api.LogSourceData
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The application-log bridge, exercised over a real gRPC server (BossConsole#53).
 *
 * Before this bridge checked identity, any process able to open a connection to the kernel IPC
 * server - not only the plugins the host itself loaded - could watch the live stdout/stderr
 * stream of the running BOSS process, or export its whole history, with no credential at all.
 */
class LogServiceBridgeTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeLogDataProvider
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticated: LogServiceGrpcKt.LogServiceCoroutineStub
    private lateinit var anonymous: LogServiceGrpcKt.LogServiceCoroutineStub

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        provider = FakeLogDataProvider()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(LogServiceBridge(provider))
                .build()
                .start()
        authenticatedChannel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        anonymousChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        authenticated = LogServiceGrpcKt.LogServiceCoroutineStub(authenticatedChannel)
        anonymous = LogServiceGrpcKt.LogServiceCoroutineStub(anonymousChannel)
    }

    @AfterTest
    fun tearDown() {
        authenticatedChannel.shutdownNow()
        authenticatedChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        anonymousChannel.shutdownNow()
        anonymousChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `every RPC is refused with no credential, and never reaches the log provider`() =
        runBlocking {
            assertRefused { anonymous.watchLogs(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.watchFilter(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.watchSearchQuery(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.watchAutoScroll(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.setFilter(LogFilterProto.getDefaultInstance()) }
            assertRefused { anonymous.setSearchQuery(LogStringRequest.newBuilder().setValue("q").build()) }
            assertRefused { anonymous.toggleAutoScroll(Empty.getDefaultInstance()) }
            assertRefused { anonymous.clearLogs(Empty.getDefaultInstance()) }
            assertRefused { anonymous.exportLogs(Empty.getDefaultInstance()) }

            assertTrue(provider.calls.isEmpty(), "a refused call must never reach the log provider")
        }

    @Test
    fun `an authenticated caller can watch and export logs`() =
        runBlocking {
            provider.setLogs(listOf(LogEntryData(timestamp = 1L, message = "hello", source = LogSourceData.STDOUT)))

            val response =
                authenticated
                    .watchLogs(Empty.getDefaultInstance())
                    .take(1)
                    .toList()
                    .single()
            assertEquals("hello", response.entriesList.single().message)

            val exported = authenticated.exportLogs(Empty.getDefaultInstance())
            assertEquals("exported", exported.value)

            authenticated.setSearchQuery(LogStringRequest.newBuilder().setValue("q").build())
            authenticated.clearLogs(Empty.getDefaultInstance())

            assertEquals(listOf("exportLogs", "setSearchQuery", "clearLogs"), provider.calls)
        }

    @Test
    fun `revoked caller cannot invoke a unary RPC`() =
        runBlocking {
            tokenRegistry.revoke(CALLER)
            assertRefused { authenticated.exportLogs(Empty.getDefaultInstance()) }
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `revoked watcher cannot receive a later snapshot`() =
        runBlocking {
            withTimeout(10_000) {
                supervisorScope {
                    val received = Channel<Unit>(Channel.UNLIMITED)
                    val watching =
                        async {
                            authenticated.watchLogs(Empty.getDefaultInstance()).collect { received.send(Unit) }
                        }
                    try {
                        received.receive()
                        tokenRegistry.revoke(CALLER)
                        provider.setLogs(listOf(LogEntryData(1L, "after", LogSourceData.STDOUT)))
                        val failure = assertFailsWith<StatusException> { watching.await() }
                        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
                        assertTrue(received.tryReceive().isFailure, "revocation must prevent the next snapshot")
                    } finally {
                        watching.cancel()
                        received.close()
                    }
                }
            }
        }

    private suspend fun assertRefused(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
    }

    /** Records every method it was actually asked to perform, so a refusal can be proven silent. */
    private class FakeLogDataProvider : LogDataProvider {
        val calls = mutableListOf<String>()
        private val _logs = MutableStateFlow<List<LogEntryData>>(emptyList())
        override val logs: StateFlow<List<LogEntryData>> = _logs
        private val _filter = MutableStateFlow(LogFilterData.ALL)
        override val filter: StateFlow<LogFilterData> = _filter
        private val _searchQuery = MutableStateFlow("")
        override val searchQuery: StateFlow<String> = _searchQuery
        private val _autoScroll = MutableStateFlow(true)
        override val autoScroll: StateFlow<Boolean> = _autoScroll

        fun setLogs(entries: List<LogEntryData>) {
            _logs.value = entries
        }

        override fun setFilter(filter: LogFilterData) {
            calls += "setFilter"
            _filter.value = filter
        }

        override fun setSearchQuery(query: String) {
            calls += "setSearchQuery"
            _searchQuery.value = query
        }

        override fun toggleAutoScroll() {
            calls += "toggleAutoScroll"
            _autoScroll.value = !_autoScroll.value
        }

        override fun clearLogs() {
            calls += "clearLogs"
            _logs.value = emptyList()
        }

        override fun exportLogs(): String {
            calls += "exportLogs"
            return "exported"
        }
    }

    private companion object {
        const val CALLER = "log-panel-plugin"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
