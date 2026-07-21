package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitOperationResultData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GitServiceBridge(
    private val provider: GitDataProvider,
) : GitServiceGrpcKt.GitServiceCoroutineImplBase() {

    override fun watchFileStatus(request: Empty): Flow<GitFileStatusListResponse> = flow {
        provider.fileStatus.collect { statuses ->
            emit(
                GitFileStatusListResponse.newBuilder()
                    .addAllFiles(statuses.map { status ->
                        GitFileStatusProto.newBuilder()
                            .setPath(status.path)
                            .setIndexStatus(status.indexStatus?.name ?: "")
                            .setWorkTreeStatus(status.workTreeStatus?.name ?: "")
                            .setIsStaged(status.isStaged)
                            .setIsUnstaged(status.isUnstaged)
                            .build()
                    })
                    .build()
            )
        }
    }

    override fun watchCommitLog(request: Empty): Flow<GitCommitLogResponse> = flow {
        provider.commitLog.collect { commits ->
            emit(
                GitCommitLogResponse.newBuilder()
                    .addAllCommits(commits.map { commit ->
                        GitCommitInfoProto.newBuilder()
                            .setHash(commit.hash)
                            .setShortHash(commit.shortHash)
                            .setSubject(commit.subject)
                            .setAuthor(commit.author)
                            .setAuthorEmail(commit.authorEmail)
                            .setDate(commit.date)
                            .addAllRefs(commit.refs)
                            .build()
                    })
                    .build()
            )
        }
    }

    override fun watchIsGitRepo(request: Empty): Flow<BoolResponse> = flow {
        provider.isGitRepository.collect { isRepo ->
            emit(BoolResponse.newBuilder().setValue(isRepo).build())
        }
    }

    override fun watchIsLoading(request: Empty): Flow<BoolResponse> = flow {
        provider.isLoading.collect { loading ->
            emit(BoolResponse.newBuilder().setValue(loading).build())
        }
    }

    override suspend fun refreshStatus(request: Empty): Empty {
        provider.refreshStatus()
        return Empty.getDefaultInstance()
    }

    override suspend fun refreshLog(request: RefreshLogRequest): Empty {
        provider.refreshLog(request.limit)
        return Empty.getDefaultInstance()
    }

    override suspend fun stage(request: GitFilePathRequest): GitOperationResultProto {
        return provider.stage(request.path).toProto()
    }

    override suspend fun unstage(request: GitFilePathRequest): GitOperationResultProto {
        return provider.unstage(request.path).toProto()
    }

    override suspend fun stageAll(request: Empty): GitOperationResultProto {
        return provider.stageAll().toProto()
    }

    override suspend fun unstageAll(request: Empty): GitOperationResultProto {
        return provider.unstageAll().toProto()
    }

    override suspend fun discardChanges(request: GitFilePathRequest): GitOperationResultProto {
        return provider.discardChanges(request.path).toProto()
    }

    override suspend fun cherryPick(request: GitHashRequest): GitOperationResultProto {
        return provider.cherryPick(request.hash).toProto()
    }

    override suspend fun revert(request: GitHashRequest): GitOperationResultProto {
        return provider.revert(request.hash).toProto()
    }

    override suspend fun checkout(request: GitRefRequest): GitOperationResultProto {
        return provider.checkout(request.ref).toProto()
    }

    override suspend fun getCurrentProjectPath(request: Empty): StringResponse {
        return StringResponse.newBuilder()
            .setValue(provider.getCurrentProjectPath() ?: "")
            .build()
    }

    override suspend fun openFile(request: GitOpenFileRequest): Empty {
        provider.openFile(request.filePath, request.windowId)
        return Empty.getDefaultInstance()
    }

    private fun GitOperationResultData.toProto(): GitOperationResultProto =
        when (this) {
            is GitOperationResultData.Success -> GitOperationResultProto.newBuilder()
                .setSuccess(true)
                .setMessage(message ?: "")
                .build()
            is GitOperationResultData.Error -> GitOperationResultProto.newBuilder()
                .setSuccess(false)
                .setMessage(message)
                .build()
        }
}
