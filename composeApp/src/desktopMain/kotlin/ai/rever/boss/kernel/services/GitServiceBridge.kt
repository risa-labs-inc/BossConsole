package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Kernel-side bridge for `GitService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. Stream revocation is checked
 * before each emission; an idle revoked stream is not proactively disconnected. Before this, any process able to
 * open a connection to the kernel IPC server - not only the plugins the host itself loaded -
 * could read the open project's full commit history and working-tree status, and could
 * [discardChanges] (destroying uncommitted work), [checkout], [cherryPick] or [revert] against
 * the project's real git repository with no credential at all.
 */
// One method per RPC the generated service base class declares, plus one small private helper.
@Suppress("TooManyFunctions")
class GitServiceBridge(
    private val provider: GitDataProvider,
) : GitServiceGrpcKt.GitServiceCoroutineImplBase() {
    override fun watchFileStatus(request: Empty): Flow<GitFileStatusListResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchFileStatus")
            provider.fileStatus.collect { statuses ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchFileStatus")
                }
                emit(
                    GitFileStatusListResponse
                        .newBuilder()
                        .addAllFiles(
                            statuses.map { status ->
                                GitFileStatusProto
                                    .newBuilder()
                                    .setPath(status.path)
                                    .setIndexStatus(status.indexStatus?.name ?: "")
                                    .setWorkTreeStatus(status.workTreeStatus?.name ?: "")
                                    .setIsStaged(status.isStaged)
                                    .setIsUnstaged(status.isUnstaged)
                                    .build()
                            },
                        ).build(),
                )
            }
        }
    }

    override fun watchCommitLog(request: Empty): Flow<GitCommitLogResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchCommitLog")
            provider.commitLog.collect { commits ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchCommitLog")
                }
                emit(
                    GitCommitLogResponse
                        .newBuilder()
                        .addAllCommits(
                            commits.map { commit ->
                                GitCommitInfoProto
                                    .newBuilder()
                                    .setHash(commit.hash)
                                    .setShortHash(commit.shortHash)
                                    .setSubject(commit.subject)
                                    .setAuthor(commit.author)
                                    .setAuthorEmail(commit.authorEmail)
                                    .setDate(commit.date)
                                    .addAllRefs(commit.refs)
                                    .build()
                            },
                        ).build(),
                )
            }
        }
    }

    override fun watchIsGitRepo(request: Empty): Flow<BoolResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchIsGitRepo")
            provider.isGitRepository.collect { isRepo ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchIsGitRepo")
                }
                emit(BoolResponse.newBuilder().setValue(isRepo).build())
            }
        }
    }

    override fun watchIsLoading(request: Empty): Flow<BoolResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchIsLoading")
            provider.isLoading.collect { loading ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchIsLoading")
                }
                emit(BoolResponse.newBuilder().setValue(loading).build())
            }
        }
    }

    override suspend fun refreshStatus(request: Empty): Empty {
        authenticatedCallerOrRefuse("refreshStatus")
        provider.refreshStatus()
        return Empty.getDefaultInstance()
    }

    override suspend fun refreshLog(request: RefreshLogRequest): Empty {
        authenticatedCallerOrRefuse("refreshLog")
        provider.refreshLog(request.limit)
        return Empty.getDefaultInstance()
    }

    override suspend fun stage(request: GitFilePathRequest): GitOperationResultProto {
        authenticatedCallerOrRefuse("stage")
        return provider.stage(request.path).toProto()
    }

    override suspend fun unstage(request: GitFilePathRequest): GitOperationResultProto {
        authenticatedCallerOrRefuse("unstage")
        return provider.unstage(request.path).toProto()
    }

    override suspend fun stageAll(request: Empty): GitOperationResultProto {
        authenticatedCallerOrRefuse("stageAll")
        return provider.stageAll().toProto()
    }

    override suspend fun unstageAll(request: Empty): GitOperationResultProto {
        authenticatedCallerOrRefuse("unstageAll")
        return provider.unstageAll().toProto()
    }

    override suspend fun discardChanges(request: GitFilePathRequest): GitOperationResultProto {
        authenticatedCallerOrRefuse("discardChanges")
        return provider.discardChanges(request.path).toProto()
    }

    override suspend fun cherryPick(request: GitHashRequest): GitOperationResultProto {
        authenticatedCallerOrRefuse("cherryPick")
        return provider.cherryPick(request.hash).toProto()
    }

    override suspend fun revert(request: GitHashRequest): GitOperationResultProto {
        authenticatedCallerOrRefuse("revert")
        return provider.revert(request.hash).toProto()
    }

    override suspend fun checkout(request: GitRefRequest): GitOperationResultProto {
        authenticatedCallerOrRefuse("checkout")
        return provider.checkout(request.ref).toProto()
    }

    override suspend fun getCurrentProjectPath(request: Empty): StringResponse {
        authenticatedCallerOrRefuse("getCurrentProjectPath")
        return StringResponse
            .newBuilder()
            .setValue(provider.getCurrentProjectPath() ?: "")
            .build()
    }

    override suspend fun openFile(request: GitOpenFileRequest): Empty {
        authenticatedCallerOrRefuse("openFile")
        provider.openFile(request.filePath, request.windowId)
        return Empty.getDefaultInstance()
    }

    /**
     * Unary RPCs only: the verified per-call identity, or `PERMISSION_DENIED`.
     * Streams must capture CURRENT_IDENTITY synchronously and recheck it before each emission.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun refuseIdentity(rpc: String): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private fun GitOperationResultData.toProto(): GitOperationResultProto =
        when (this) {
            is GitOperationResultData.Success -> {
                GitOperationResultProto
                    .newBuilder()
                    .setSuccess(true)
                    .setMessage(message ?: "")
                    .build()
            }

            is GitOperationResultData.Error -> {
                GitOperationResultProto
                    .newBuilder()
                    .setSuccess(false)
                    .setMessage(message)
                    .build()
            }
        }

    private companion object {
        val logger = BossLogger.forComponent("GitServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
