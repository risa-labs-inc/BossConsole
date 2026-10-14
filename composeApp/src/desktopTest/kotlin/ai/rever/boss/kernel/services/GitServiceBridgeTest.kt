package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.GitFilePathRequest
import ai.rever.boss.ipc.proto.services.GitHashRequest
import ai.rever.boss.ipc.proto.services.GitOpenFileRequest
import ai.rever.boss.ipc.proto.services.GitRefRequest
import ai.rever.boss.ipc.proto.services.GitServiceGrpcKt
import ai.rever.boss.ipc.proto.services.RefreshLogRequest
import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitOperationResultData
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
 * The Git bridge, exercised over a real gRPC server (BossConsole#53).
 *
 * Before this bridge checked identity, any process able to open a connection to the kernel IPC
 * server - not only the plugins the host itself loaded - could read the open project's full
 * commit history and working-tree status, and could [GitServiceBridge.discardChanges] (destroying
 * uncommitted work), [GitServiceBridge.checkout], [GitServiceBridge.cherryPick] or
 * [GitServiceBridge.revert] against the real repository with no credential at all.
 */
class GitServiceBridgeTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeGitDataProvider
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticated: GitServiceGrpcKt.GitServiceCoroutineStub
    private lateinit var anonymous: GitServiceGrpcKt.GitServiceCoroutineStub

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        provider = FakeGitDataProvider()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(GitServiceBridge(provider))
                .build()
                .start()
        authenticatedChannel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        anonymousChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        authenticated = GitServiceGrpcKt.GitServiceCoroutineStub(authenticatedChannel)
        anonymous = GitServiceGrpcKt.GitServiceCoroutineStub(anonymousChannel)
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
    fun `every RPC is refused with no credential, and never reaches the repository`() =
        runBlocking {
            assertRefused { anonymous.watchFileStatus(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.watchCommitLog(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.watchIsGitRepo(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.watchIsLoading(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.refreshStatus(Empty.getDefaultInstance()) }
            assertRefused { anonymous.refreshLog(RefreshLogRequest.newBuilder().setLimit(10).build()) }
            assertRefused { anonymous.stage(GitFilePathRequest.newBuilder().setPath("a.kt").build()) }
            assertRefused { anonymous.unstage(GitFilePathRequest.newBuilder().setPath("a.kt").build()) }
            assertRefused { anonymous.stageAll(Empty.getDefaultInstance()) }
            assertRefused { anonymous.unstageAll(Empty.getDefaultInstance()) }
            assertRefused { anonymous.discardChanges(GitFilePathRequest.newBuilder().setPath("a.kt").build()) }
            assertRefused { anonymous.cherryPick(GitHashRequest.newBuilder().setHash("abc123").build()) }
            assertRefused { anonymous.revert(GitHashRequest.newBuilder().setHash("abc123").build()) }
            assertRefused { anonymous.checkout(GitRefRequest.newBuilder().setRef("main").build()) }
            assertRefused { anonymous.getCurrentProjectPath(Empty.getDefaultInstance()) }
            assertRefused {
                anonymous.openFile(
                    GitOpenFileRequest
                        .newBuilder()
                        .setFilePath("a.kt")
                        .setWindowId("w1")
                        .build(),
                )
            }

            assertTrue(provider.calls.isEmpty(), "a refused call must never reach the repository provider")
        }

    @Test
    fun `an authenticated caller can watch status and read the repository`() =
        runBlocking {
            provider.setFileStatus(listOf(fileStatus("a.kt")))
            provider.setCommitLog(listOf(commit("abc123")))
            provider.projectPath = "/repo"

            val status =
                authenticated
                    .watchFileStatus(Empty.getDefaultInstance())
                    .take(1)
                    .toList()
                    .single()
            assertEquals("a.kt", status.filesList.single().path)

            val log =
                authenticated
                    .watchCommitLog(Empty.getDefaultInstance())
                    .take(1)
                    .toList()
                    .single()
            assertEquals("abc123", log.commitsList.single().hash)

            val path = authenticated.getCurrentProjectPath(Empty.getDefaultInstance())
            assertEquals("/repo", path.value)
        }

    @Test
    fun `an authenticated caller can mutate the repository`() =
        runBlocking {
            authenticated.stage(GitFilePathRequest.newBuilder().setPath("a.kt").build())
            authenticated.checkout(GitRefRequest.newBuilder().setRef("main").build())
            authenticated.discardChanges(GitFilePathRequest.newBuilder().setPath("a.kt").build())

            assertEquals(listOf("stage:a.kt", "checkout:main", "discardChanges:a.kt"), provider.calls)
        }

    @Test
    fun `revoked credentials cannot invoke a unary operation`() =
        runBlocking {
            tokenRegistry.revoke(CALLER)
            val failure = assertFailsWith<StatusException> { authenticated.stageAll(Empty.getDefaultInstance()) }
            assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
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
                            authenticated.watchFileStatus(Empty.getDefaultInstance()).collect { received.send(Unit) }
                        }
                    try {
                        received.receive()
                        tokenRegistry.revoke(CALLER)
                        provider.setFileStatus(listOf(fileStatus("after-revocation.kt")))
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

    private fun fileStatus(path: String): GitFileStatusData =
        GitFileStatusData(
            path = path,
            indexStatus = null,
            workTreeStatus = null,
            isStaged = false,
            isUnstaged = true,
        )

    private fun commit(hash: String): GitCommitInfoData =
        GitCommitInfoData(
            hash = hash,
            shortHash = hash.take(7),
            subject = "subject",
            author = "author",
            authorEmail = "author@example.com",
            date = 0L,
            refs = emptyList(),
        )

    /** Records every method it was actually asked to perform, so a refusal can be proven silent. */
    private class FakeGitDataProvider : GitDataProvider {
        val calls = mutableListOf<String>()
        var projectPath: String? = null
        private val _fileStatus = MutableStateFlow<List<GitFileStatusData>>(emptyList())
        override val fileStatus: StateFlow<List<GitFileStatusData>> = _fileStatus
        private val _commitLog = MutableStateFlow<List<GitCommitInfoData>>(emptyList())
        override val commitLog: StateFlow<List<GitCommitInfoData>> = _commitLog
        private val _isGitRepository = MutableStateFlow(true)
        override val isGitRepository: StateFlow<Boolean> = _isGitRepository
        private val _isLoading = MutableStateFlow(false)
        override val isLoading: StateFlow<Boolean> = _isLoading

        fun setFileStatus(statuses: List<GitFileStatusData>) {
            _fileStatus.value = statuses
        }

        fun setCommitLog(commits: List<GitCommitInfoData>) {
            _commitLog.value = commits
        }

        override suspend fun refreshStatus() {
            calls += "refreshStatus"
        }

        override suspend fun refreshLog(limit: Int) {
            calls += "refreshLog"
        }

        override suspend fun stage(filePath: String): GitOperationResultData {
            calls += "stage:$filePath"
            return GitOperationResultData.Success(null)
        }

        override suspend fun unstage(filePath: String): GitOperationResultData {
            calls += "unstage:$filePath"
            return GitOperationResultData.Success(null)
        }

        override suspend fun stageAll(): GitOperationResultData {
            calls += "stageAll"
            return GitOperationResultData.Success(null)
        }

        override suspend fun unstageAll(): GitOperationResultData {
            calls += "unstageAll"
            return GitOperationResultData.Success(null)
        }

        override suspend fun discardChanges(filePath: String): GitOperationResultData {
            calls += "discardChanges:$filePath"
            return GitOperationResultData.Success(null)
        }

        override suspend fun cherryPick(commitHash: String): GitOperationResultData {
            calls += "cherryPick:$commitHash"
            return GitOperationResultData.Success(null)
        }

        override suspend fun revert(commitHash: String): GitOperationResultData {
            calls += "revert:$commitHash"
            return GitOperationResultData.Success(null)
        }

        override suspend fun checkout(ref: String): GitOperationResultData {
            calls += "checkout:$ref"
            return GitOperationResultData.Success(null)
        }

        override fun getCurrentProjectPath(): String? {
            calls += "getCurrentProjectPath"
            return projectPath
        }

        override fun openFile(
            filePath: String,
            windowId: String,
        ) {
            calls += "openFile:$filePath"
        }
    }

    private companion object {
        const val CALLER = "git-panel-plugin"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
