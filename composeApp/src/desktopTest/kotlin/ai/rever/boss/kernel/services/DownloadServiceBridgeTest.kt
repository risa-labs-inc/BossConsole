package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.DownloadIdRequest
import ai.rever.boss.ipc.proto.services.DownloadServiceGrpcKt
import ai.rever.boss.ipc.proto.services.PathRequest
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.DownloadItemData
import ai.rever.boss.plugin.api.DownloadStatusData
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `DownloadServiceBridge`, exercised over a real gRPC server with a real client - see
 * [PluginUIServiceBridgeTest]'s own KDoc for why this repo tests bridges this way rather than
 * calling the class's methods directly: a Kotlin-level call bypasses [ProcessIdentityInterceptor]
 * entirely, which is exactly the thing under test (BossConsole#53).
 *
 * Two properties matter for every refusal case here, not just "a `PERMISSION_DENIED` came back":
 * the [FakeDownloadDataProvider] must never have been invoked ([FakeDownloadDataProvider.calls]
 * stays empty), since a refusal that still reached the provider would defeat the point of gating
 * it at all - and for [openFile]/[revealInFolder] specifically, an authenticated caller naming a
 * path this provider is not tracking as a download must be refused too (the path-confinement
 * guard the class KDoc on [DownloadServiceBridge] explains).
 */
class DownloadServiceBridgeTest {
    private lateinit var provider: FakeDownloadDataProvider
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var authenticated: DownloadServiceGrpcKt.DownloadServiceCoroutineStub
    private val extraChannels = mutableListOf<ManagedChannel>()

    @BeforeTest
    fun setUp() {
        provider = FakeDownloadDataProvider()
        tokenRegistry = ProcessTokenRegistry()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(DownloadServiceBridge(provider))
                .build()
                .start()
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        authenticated = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(channel)
    }

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        channel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        extraChannels.forEach { it.shutdownNow() }
        extraChannels.forEach { it.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun anonymous(): DownloadServiceGrpcKt.DownloadServiceCoroutineStub {
        val unauthenticatedChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        extraChannels += unauthenticatedChannel
        return DownloadServiceGrpcKt.DownloadServiceCoroutineStub(unauthenticatedChannel)
    }

    // ---- BossConsole#53: every RPC requires a verified caller identity ----

    @Test
    fun `pauseDownload with no credential is refused and never reaches the provider`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymous().pauseDownload(DownloadIdRequest.newBuilder().setId("d1").build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `resumeDownload with no credential is refused and never reaches the provider`() =
        runBlocking {
            assertFailsWith<StatusException> {
                anonymous().resumeDownload(DownloadIdRequest.newBuilder().setId("d1").build())
            }
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `cancelDownload with no credential is refused and never reaches the provider`() =
        runBlocking {
            assertFailsWith<StatusException> {
                anonymous().cancelDownload(DownloadIdRequest.newBuilder().setId("d1").build())
            }
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `removeDownload with no credential is refused and never reaches the provider`() =
        runBlocking {
            assertFailsWith<StatusException> {
                anonymous().removeDownload(DownloadIdRequest.newBuilder().setId("d1").build())
            }
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `clearCompleted with no credential is refused and never reaches the provider`() =
        runBlocking {
            assertFailsWith<StatusException> { anonymous().clearCompleted(Empty.getDefaultInstance()) }
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `watchDownloads with no credential is refused before any item is streamed`() =
        runBlocking {
            provider.setDownloads(listOf(trackedItem("d1", "/tmp/tracked/d1.txt")))
            val failure =
                assertFailsWith<StatusException> {
                    anonymous().watchDownloads(Empty.getDefaultInstance()).first()
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
        }

    @Test
    fun `an authenticated caller can drive the ordinary operations`() =
        runBlocking {
            assertTrue(authenticated.pauseDownload(DownloadIdRequest.newBuilder().setId("d1").build()).success)
            assertTrue(authenticated.resumeDownload(DownloadIdRequest.newBuilder().setId("d1").build()).success)
            assertTrue(authenticated.cancelDownload(DownloadIdRequest.newBuilder().setId("d1").build()).success)
            assertTrue(authenticated.removeDownload(DownloadIdRequest.newBuilder().setId("d1").build()).success)
            assertTrue(authenticated.clearCompleted(Empty.getDefaultInstance()).success)
            assertEquals(
                listOf("pause:d1", "resume:d1", "cancel:d1", "remove:d1", "clearCompleted"),
                provider.calls,
            )
        }

    // ---- Path confinement: openFile / revealInFolder ----

    @Test
    fun `openFile for a path this provider is not tracking is refused even for an authenticated caller`() =
        runBlocking {
            provider.setDownloads(listOf(trackedItem("d1", tempTrackedFile().absolutePath)))

            val failure =
                assertFailsWith<StatusException> {
                    authenticated.openFile(PathRequest.newBuilder().setPath(untrackedExecutable().absolutePath).build())
                }

            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertTrue(provider.calls.isEmpty(), "the OS-level open must never have been reached")
        }

    @Test
    fun `revealInFolder for a path this provider is not tracking is refused even for an authenticated caller`() =
        runBlocking {
            provider.setDownloads(listOf(trackedItem("d1", tempTrackedFile().absolutePath)))

            val failure =
                assertFailsWith<StatusException> {
                    authenticated.revealInFolder(
                        PathRequest.newBuilder().setPath(untrackedExecutable().absolutePath).build(),
                    )
                }

            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `openFile for a path this provider IS tracking as a download reaches the provider`() =
        runBlocking {
            val tracked = tempTrackedFile()
            provider.setDownloads(listOf(trackedItem("d1", tracked.absolutePath)))

            authenticated.openFile(PathRequest.newBuilder().setPath(tracked.absolutePath).build())

            assertEquals(listOf("openFile:${tracked.absolutePath}"), provider.calls)
        }

    @Test
    fun `openFile with no credential is refused before the path-confinement check even runs`() =
        runBlocking {
            val tracked = tempTrackedFile()
            provider.setDownloads(listOf(trackedItem("d1", tracked.absolutePath)))

            val failure =
                assertFailsWith<StatusException> {
                    anonymous().openFile(PathRequest.newBuilder().setPath(tracked.absolutePath).build())
                }

            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertTrue(provider.calls.isEmpty())
        }

    // ---- Helpers ----

    private fun tempTrackedFile() =
        kotlin.io.path
            .createTempFile("boss-download-test", ".txt")
            .toFile()
            .apply { deleteOnExit() }

    private fun untrackedExecutable() =
        kotlin.io.path
            .createTempFile("boss-not-a-download", ".exe")
            .toFile()
            .apply { deleteOnExit() }

    private fun trackedItem(
        id: String,
        destinationPath: String,
    ) = DownloadItemData(
        id = id,
        fileName = destinationPath.substringAfterLast('/').substringAfterLast('\\'),
        destinationPath = destinationPath,
        url = "https://example.test/$id",
        status = DownloadStatusData.COMPLETED,
        receivedBytes = 100L,
        totalBytes = 100L,
        speed = 0.0,
        canPause = false,
        canResume = false,
        errorReason = null,
        startTime = 0L,
        endTime = 0L,
    )

    /**
     * Records every call it actually receives, so a refusal at the bridge (identity or path
     * confinement) can be told apart from a refusal the provider itself would have raised.
     */
    private class FakeDownloadDataProvider : DownloadDataProvider {
        val calls = mutableListOf<String>()
        private val _downloads = MutableStateFlow<List<DownloadItemData>>(emptyList())
        override val downloads: StateFlow<List<DownloadItemData>> = _downloads

        fun setDownloads(items: List<DownloadItemData>) {
            _downloads.value = items
        }

        override suspend fun pauseDownload(id: String): Result<Unit> {
            calls += "pause:$id"
            return Result.success(Unit)
        }

        override suspend fun resumeDownload(id: String): Result<Unit> {
            calls += "resume:$id"
            return Result.success(Unit)
        }

        override suspend fun cancelDownload(id: String): Result<Unit> {
            calls += "cancel:$id"
            return Result.success(Unit)
        }

        override suspend fun removeDownload(id: String): Result<Unit> {
            calls += "remove:$id"
            return Result.success(Unit)
        }

        override suspend fun clearCompleted(): Result<Unit> {
            calls += "clearCompleted"
            return Result.success(Unit)
        }

        override fun revealInFolder(path: String) {
            calls += "revealInFolder:$path"
        }

        override fun openFile(path: String) {
            calls += "openFile:$path"
        }
    }

    private companion object {
        const val CALLER = "test-caller"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
