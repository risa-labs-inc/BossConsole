package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.DownloadStatusData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Kernel-side bridge for `DownloadService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape as [SecretServiceBridge] - see that class's KDoc for the full rationale.
 *
 * [openFile] and [revealInFolder] get a second, narrower guard on top: [FileSystemUtils.openFile]
 * (reached through [DownloadDataProvider.openFile]) shells out to the OS's own "open with default
 * application" command (`open`/`start`/`xdg-open`) for *any* path that exists on disk - it does
 * not check that the path has anything to do with a download. Before this bridge required a
 * caller identity at all, that was an unauthenticated, unconfined "launch this file" primitive:
 * for an executable, `start "" file.exe` and a double-click are the same action, and this is the
 * exact class of thing BossConsole#484's download warning exists to gate - reachable here with no
 * dialog, no confirmation, and (until the identity check above) no attribution at all. Confining
 * both calls to a path this provider's own [DownloadDataProvider.downloads] list currently
 * tracks - canonical-path compared, so a symlink or a `..` cannot walk outside it - turns "open
 * any file that exists" back into "open a file BOSS itself downloaded", which is the only thing
 * either RPC's own name promises to do.
 */
// One method per RPC the generated service base class declares, plus three small private helpers.
@Suppress("TooManyFunctions")
class DownloadServiceBridge(
    private val provider: DownloadDataProvider,
) : DownloadServiceGrpcKt.DownloadServiceCoroutineImplBase() {
    override fun watchDownloads(request: Empty): Flow<DownloadListResponse> =
        flow {
            authenticatedCallerOrRefuse("watchDownloads")
            provider.downloads.collect { downloads ->
                emit(
                    DownloadListResponse
                        .newBuilder()
                        .addAllDownloads(
                            downloads.map { item ->
                                DownloadItemProto
                                    .newBuilder()
                                    .setId(item.id)
                                    .setFileName(item.fileName)
                                    .setDestinationPath(item.destinationPath)
                                    .setUrl(item.url)
                                    .setStatus(
                                        when (item.status) {
                                            DownloadStatusData.QUEUED -> DownloadStatusProto.DOWNLOAD_STATUS_QUEUED
                                            DownloadStatusData.DOWNLOADING -> DownloadStatusProto.DOWNLOAD_STATUS_DOWNLOADING
                                            DownloadStatusData.PAUSED -> DownloadStatusProto.DOWNLOAD_STATUS_PAUSED
                                            DownloadStatusData.COMPLETED -> DownloadStatusProto.DOWNLOAD_STATUS_COMPLETED
                                            DownloadStatusData.FAILED -> DownloadStatusProto.DOWNLOAD_STATUS_FAILED
                                            DownloadStatusData.CANCELLED -> DownloadStatusProto.DOWNLOAD_STATUS_CANCELLED
                                        },
                                    ).setReceivedBytes(item.receivedBytes)
                                    .setTotalBytes(item.totalBytes ?: -1L)
                                    .setSpeed(item.speed)
                                    .setCanPause(item.canPause)
                                    .setCanResume(item.canResume)
                                    .setErrorReason(item.errorReason ?: "")
                                    .setStartTime(item.startTime)
                                    .setEndTime(item.endTime ?: 0L)
                                    .build()
                            },
                        ).build(),
                )
            }
        }

    override suspend fun pauseDownload(request: DownloadIdRequest): OperationResult {
        authenticatedCallerOrRefuse("pauseDownload")
        return provider.pauseDownload(request.id).toOperationResult()
    }

    override suspend fun resumeDownload(request: DownloadIdRequest): OperationResult {
        authenticatedCallerOrRefuse("resumeDownload")
        return provider.resumeDownload(request.id).toOperationResult()
    }

    override suspend fun cancelDownload(request: DownloadIdRequest): OperationResult {
        authenticatedCallerOrRefuse("cancelDownload")
        return provider.cancelDownload(request.id).toOperationResult()
    }

    override suspend fun removeDownload(request: DownloadIdRequest): OperationResult {
        authenticatedCallerOrRefuse("removeDownload")
        return provider.removeDownload(request.id).toOperationResult()
    }

    override suspend fun clearCompleted(request: Empty): OperationResult {
        authenticatedCallerOrRefuse("clearCompleted")
        return provider.clearCompleted().toOperationResult()
    }

    override suspend fun revealInFolder(request: PathRequest): Empty {
        val caller = authenticatedCallerOrRefuse("revealInFolder")
        val path = trackedDownloadPathOrRefuse("revealInFolder", caller, request.path)
        provider.revealInFolder(path)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFile(request: PathRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFile")
        val path = trackedDownloadPathOrRefuse("openFile", caller, request.path)
        provider.openFile(path)
        return Empty.getDefaultInstance()
    }

    /**
     * Confines [openFile]/[revealInFolder] to a path this provider itself is currently tracking
     * as a download's [ai.rever.boss.plugin.api.DownloadItemData.destinationPath] - canonicalized
     * on both sides so a symlink or a `..` segment cannot walk outside the tracked set. See the
     * class KDoc for why this exists on top of the caller-identity check above.
     */
    private fun trackedDownloadPathOrRefuse(
        rpc: String,
        caller: String,
        requestedPath: String,
    ): String {
        val requestedCanonical = runCatching { File(requestedPath).canonicalFile }.getOrNull()
        val isTracked =
            requestedCanonical != null &&
                provider.downloads.value.any { item ->
                    runCatching { File(item.destinationPath).canonicalFile }.getOrNull() == requestedCanonical
                }
        if (!isTracked) {
            logger.warn(
                LogCategory.AUTH,
                "Refused $rpc: path is not a tracked download",
                mapOf("rpc" to rpc, "caller" to caller),
            )
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NOT_A_TRACKED_DOWNLOAD))
        }
        return requestedPath
    }

    /**
     * The verified identity behind this call, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors [SecretServiceBridge]'s own helper (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: run {
            logger.warn(
                LogCategory.AUTH,
                "Refused $rpc: no verified process identity on this call",
                mapOf("rpc" to rpc),
            )
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
        }

    private fun Result<Unit>.toOperationResult(): OperationResult =
        fold(
            onSuccess = {
                OperationResult.newBuilder().setSuccess(true).build()
            },
            onFailure = { error ->
                OperationResult
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )

    private companion object {
        val logger = BossLogger.forComponent("DownloadServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
        const val NOT_A_TRACKED_DOWNLOAD = "This path is not a download tracked by this provider"
    }
}
