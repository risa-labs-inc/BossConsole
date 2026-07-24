package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import ai.rever.boss.plugin.api.GitOperationResultData
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * IPC proxy implementation of GitDataProvider.
 */
class GitDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : GitDataProvider {
    private val stub = GitServiceGrpcKt.GitServiceCoroutineStub(channel)

    private val _fileStatus = MutableStateFlow<List<GitFileStatusData>>(emptyList())
    override val fileStatus: StateFlow<List<GitFileStatusData>> = _fileStatus.asStateFlow()

    private val _commitLog = MutableStateFlow<List<GitCommitInfoData>>(emptyList())
    override val commitLog: StateFlow<List<GitCommitInfoData>> = _commitLog.asStateFlow()

    private val _isGitRepository = MutableStateFlow(false)
    override val isGitRepository: StateFlow<Boolean> = _isGitRepository.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        scope.launch { watchFileStatus() }
        scope.launch { watchCommitLog() }
        scope.launch { watchIsGitRepo() }
        scope.launch { watchIsLoading() }
    }

    private suspend fun watchFileStatus() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchFileStatus(Empty.getDefaultInstance()).collect { response ->
                    _fileStatus.value = response.filesList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun watchCommitLog() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchCommitLog(Empty.getDefaultInstance()).collect { response ->
                    _commitLog.value = response.commitsList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun watchIsGitRepo() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchIsGitRepo(Empty.getDefaultInstance()).collect { response ->
                    _isGitRepository.value = response.value
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun watchIsLoading() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchIsLoading(Empty.getDefaultInstance()).collect { response ->
                    _isLoading.value = response.value
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    override suspend fun refreshStatus() {
        try {
            stub.refreshStatus(Empty.getDefaultInstance())
        } catch (_: Exception) {
        }
    }

    override suspend fun refreshLog(limit: Int) {
        try {
            stub.refreshLog(RefreshLogRequest.newBuilder().setLimit(limit).build())
        } catch (_: Exception) {
        }
    }

    override suspend fun stage(filePath: String) = gitOp { stub.stage(GitFilePathRequest.newBuilder().setPath(filePath).build()) }

    override suspend fun unstage(filePath: String) = gitOp { stub.unstage(GitFilePathRequest.newBuilder().setPath(filePath).build()) }

    override suspend fun stageAll() = gitOp { stub.stageAll(Empty.getDefaultInstance()) }

    override suspend fun unstageAll() = gitOp { stub.unstageAll(Empty.getDefaultInstance()) }

    override suspend fun discardChanges(filePath: String) =
        gitOp {
            stub.discardChanges(GitFilePathRequest.newBuilder().setPath(filePath).build())
        }

    override suspend fun cherryPick(commitHash: String) = gitOp { stub.cherryPick(GitHashRequest.newBuilder().setHash(commitHash).build()) }

    override suspend fun revert(commitHash: String) = gitOp { stub.revert(GitHashRequest.newBuilder().setHash(commitHash).build()) }

    override suspend fun checkout(ref: String) = gitOp { stub.checkout(GitRefRequest.newBuilder().setRef(ref).build()) }

    override fun getCurrentProjectPath(): String? =
        try {
            kotlinx.coroutines.runBlocking {
                val resp = stub.getCurrentProjectPath(Empty.getDefaultInstance())
                resp.value.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }

    override fun openFile(
        filePath: String,
        windowId: String,
    ) {
        scope.launch {
            try {
                stub.openFile(
                    GitOpenFileRequest
                        .newBuilder()
                        .setFilePath(filePath)
                        .setWindowId(windowId)
                        .build(),
                )
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun gitOp(block: suspend () -> GitOperationResultProto): GitOperationResultData =
        try {
            val result = block()
            if (result.success) {
                GitOperationResultData.Success(result.message.takeIf { it.isNotEmpty() })
            } else {
                GitOperationResultData.Error(result.message)
            }
        } catch (e: Exception) {
            GitOperationResultData.Error(e.message ?: "Unknown error")
        }

    private fun GitFileStatusProto.toData() =
        GitFileStatusData(
            path = path,
            indexStatus = indexStatus.takeIf { it.isNotEmpty() }?.let { parseStatusType(it) },
            workTreeStatus = workTreeStatus.takeIf { it.isNotEmpty() }?.let { parseStatusType(it) },
            isStaged = isStaged,
            isUnstaged = isUnstaged,
        )

    private fun parseStatusType(value: String): GitFileStatusTypeData? =
        try {
            GitFileStatusTypeData.valueOf(value)
        } catch (_: Exception) {
            null
        }

    private fun GitCommitInfoProto.toData() =
        GitCommitInfoData(
            hash = hash,
            shortHash = shortHash,
            subject = subject,
            author = author,
            authorEmail = authorEmail,
            date = date,
            refs = refsList,
        )
}
