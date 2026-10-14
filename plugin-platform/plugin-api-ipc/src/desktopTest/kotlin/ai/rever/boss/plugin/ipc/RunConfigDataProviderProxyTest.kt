package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ExecuteConfigRequest
import ai.rever.boss.ipc.proto.services.RunConfigBoolResponse
import ai.rever.boss.ipc.proto.services.RunConfigListResponse
import ai.rever.boss.ipc.proto.services.RunConfigStringResponse
import ai.rever.boss.ipc.proto.services.RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineImplBase
import ai.rever.boss.ipc.proto.services.ScanProjectRequest
import ai.rever.boss.plugin.api.LanguageData
import ai.rever.boss.plugin.api.RunConfigurationData
import ai.rever.boss.plugin.api.RunConfigurationTypeData
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The client half of BossConsole#634's stale-Run finding: `RunConfigServiceBridge` correctly
 * refuses an `Execute` naming an id it no longer holds (proven server-side by
 * `RunConfigServiceBridgeTest`), but the proxy caught every exception from that refusal and
 * discarded it - so a Run click across a rescan opened no terminal and told the operator nothing.
 * `lastError` is the only client-visible signal this pinned interface has (the run panel already
 * renders it), so this is what a refused `execute` now sets it to.
 *
 * The fake server here doesn't check identity at all - that boundary belongs to
 * `RunConfigServiceBridgeTest`, which runs against the real bridge. This test is only about what
 * the proxy does with a `StatusException` it receives, so the fake refuses or accepts by id alone.
 */
class RunConfigDataProviderProxyTest {
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var proxy: RunConfigDataProviderProxy
    private lateinit var proxyScope: CoroutineScope
    private lateinit var fakeService: FakeRunConfigurationService

    @BeforeTest
    fun setUp() {
        fakeService = FakeRunConfigurationService()
        server =
            ServerBuilder
                .forPort(0)
                .addService(fakeService)
                .build()
                .start()
        channel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        proxyScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        proxy = RunConfigDataProviderProxy(channel, proxyScope)
    }

    @AfterTest
    fun tearDown() {
        proxyScope.cancel()
        channel.shutdownNow()
        channel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `a refused execute becomes a visible client-side error, not silence`() =
        runBlocking {
            withTimeout(10_000) {
                assertNull(proxy.lastError.value, "no error before the call")

                proxy.execute(config("stale-id"), "w1")

                assertNotNull(proxy.lastError.value, "a refused execute must surface something to the run panel")
                assertEquals(1, fakeService.executeAttempts.get(), "the request did reach the server and was refused")
            }
        }

    @Test
    fun `a successful execute without errors keeps lastError empty`() =
        runBlocking {
            withTimeout(10_000) {
                proxy.execute(config(FakeRunConfigurationService.ACCEPTED_ID), "w1")

                assertNull(proxy.lastError.value, "a successful execute must not report a stale-run error")
            }
        }

    @Test
    fun `host empty snapshots do not erase a local refusal and clear dismisses it`() =
        runBlocking {
            withTimeout(10_000) {
                proxy.execute(config("stale-id"), "w1")
                fakeService.errors.send("")
                assertNull(
                    withTimeoutOrNull(300) {
                        proxy.lastError.first { it == null }
                        true
                    },
                )
                assertNotNull(proxy.lastError.value)
                proxy.clearError()
                assertNull(proxy.lastError.value)
            }
        }

    @Test
    fun `successful retry clears the local refusal`() =
        runBlocking {
            withTimeout(10_000) {
                proxy.execute(config("stale-id"), "w1")
                assertNotNull(proxy.lastError.value)
                proxy.execute(config(FakeRunConfigurationService.ACCEPTED_ID), "w1")
                assertNull(proxy.lastError.value)
            }
        }

    @Test
    fun `clearing a local error preserves the latest host error`() =
        runBlocking {
            withTimeout(10_000) {
                fakeService.errors.send("host failure")
                proxy.lastError.first { it == "host failure" }
                proxy.execute(config("stale-id"), "w1")
                proxy.clearError()
                assertEquals("host failure", proxy.lastError.value)
            }
        }

    @Test
    fun `transport failure reports failure without claiming a stale id`() =
        runBlocking {
            withTimeout(10_000) {
                proxy.execute(config("stale-id"), "w1")
                val refusal = proxy.lastError.value
                proxy.execute(config("offline"), "w1")
                val error = assertNotNull(proxy.lastError.value)
                assertEquals(refusal, error)
                assertFalse(error.contains("remote details"))
            }
        }

    @Test
    fun `cancellation propagates without publishing an execution error`() =
        runBlocking {
            withTimeout(10_000) {
                val executing = async { proxy.execute(config("blocked"), "w1") }
                fakeService.blocked.await()
                executing.cancel()
                assertFailsWith<CancellationException> { executing.await() }
                assertNull(proxy.lastError.value)
            }
        }

    @Test
    fun `scan cancellation propagates`() =
        runBlocking {
            withTimeout(10_000) {
                fakeService.blockOperation = "scan"
                val continued = AtomicBoolean(false)
                val calling =
                    async {
                        proxy.scanProject("/repo", "w1")
                        continued.set(true)
                    }
                fakeService.blocked.await()
                calling.cancel()
                assertFailsWith<CancellationException> { calling.await() }
                calling.join()
                assertFalse(continued.get(), "cancellation must not return normally from the proxy call")
                assertNull(proxy.lastError.value)
            }
        }

    @Test
    fun `clear cancellation propagates`() =
        runBlocking {
            withTimeout(10_000) {
                fakeService.blockOperation = "clear"
                val continued = AtomicBoolean(false)
                val calling =
                    async {
                        proxy.clearError()
                        continued.set(true)
                    }
                fakeService.blocked.await()
                calling.cancel()
                assertFailsWith<CancellationException> { calling.await() }
                calling.join()
                assertFalse(continued.get(), "cancellation must not return normally from the proxy call")
                assertNull(proxy.lastError.value)
            }
        }

    @Test
    fun `a new host scan failure supersedes a local run failure`() =
        runBlocking {
            withTimeout(10_000) {
                proxy.execute(config("stale-id"), "w1")
                fakeService.errors.send("new scan failure")
                assertEquals("new scan failure", proxy.lastError.first { it == "new scan failure" })
            }
        }

    @Test
    fun `a successful run preserves a pending host error`() =
        runBlocking {
            withTimeout(10_000) {
                fakeService.errors.send("host failure")
                proxy.lastError.first { it == "host failure" }
                proxy.execute(config(FakeRunConfigurationService.ACCEPTED_ID), "w1")
                assertEquals("host failure", proxy.lastError.value)
            }
        }

    @Test
    fun `a new scan clears local run feedback even when the host error is unchanged`() =
        runBlocking {
            withTimeout(10_000) {
                fakeService.errors.send("host failure")
                proxy.lastError.first { it == "host failure" }
                proxy.execute(config("stale-id"), "w1")
                proxy.scanProject("/repo", "w1")
                assertEquals("host failure", proxy.lastError.value)
            }
        }

    private fun config(id: String): RunConfigurationData =
        RunConfigurationData(
            id = id,
            name = id,
            type = RunConfigurationTypeData.MAIN_FUNCTION,
            filePath = "/repo/Main.kt",
            lineNumber = 1,
            language = LanguageData.KOTLIN,
            command = "kotlin",
            workingDirectory = "/repo",
            environmentVariables = emptyMap(),
            arguments = "",
            isAutoDetected = true,
            timestamp = 0L,
        )

    /**
     * Refuses every id except [ACCEPTED_ID], mirroring `RunConfigServiceBridge`'s allowlist
     * refusal shape (`PERMISSION_DENIED`) without needing the real bridge's identity gating -
     * that boundary is `RunConfigServiceBridgeTest`'s to cover.
     */
    private class FakeRunConfigurationService : RunConfigurationServiceCoroutineImplBase() {
        val executeAttempts = AtomicInteger(0)
        val blocked = CompletableDeferred<Unit>()
        var blockOperation: String? = null
        val errors = Channel<String>(Channel.RENDEZVOUS)

        override fun watchDetectedConfigurations(request: Empty): Flow<RunConfigListResponse> = neverEmits()

        override fun watchIsScanning(request: Empty): Flow<RunConfigBoolResponse> = neverEmits()

        override fun watchLastError(request: Empty): Flow<RunConfigStringResponse> =
            flow {
                for (error in errors) {
                    emit(RunConfigStringResponse.newBuilder().setValue(error).build())
                }
            }

        /**
         * Never emits: the three watch RPCs exist only so the proxy's background watcher
         * coroutines, started from its `init` block, have something to suspend on instead of
         * hot-looping against a stream the fake never serves.
         */
        private fun <T> neverEmits(): Flow<T> = flow { awaitCancellation() }

        override suspend fun scanProject(request: ScanProjectRequest): Empty {
            blockIfRequested("scan")
            return Empty.getDefaultInstance()
        }

        override suspend fun execute(request: ExecuteConfigRequest): Empty {
            executeAttempts.incrementAndGet()
            if (request.configuration.id == "blocked") {
                blocked.complete(Unit)
                awaitCancellation()
            }
            if (request.configuration.id == "offline") {
                throw StatusException(Status.UNAVAILABLE.withDescription("remote details"))
            }
            if (request.configuration.id != ACCEPTED_ID) {
                throw StatusException(Status.PERMISSION_DENIED.withDescription("stale id"))
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun clearError(request: Empty): Empty {
            blockIfRequested("clear")
            return Empty.getDefaultInstance()
        }

        private suspend fun blockIfRequested(operation: String) {
            if (blockOperation == operation) {
                blocked.complete(Unit)
                awaitCancellation()
            }
        }

        companion object {
            const val ACCEPTED_ID = "known-1"
        }
    }

    private companion object {
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
