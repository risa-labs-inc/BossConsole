package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.DownloadStatusData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DownloadServiceBridge(
    private val provider: DownloadDataProvider,
) : DownloadServiceGrpcKt.DownloadServiceCoroutineImplBase() {

    override fun watchDownloads(request: Empty): Flow<DownloadListResponse> = flow {
        provider.downloads.collect { downloads ->
            emit(
                DownloadListResponse.newBuilder()
                    .addAllDownloads(downloads.map { item ->
                        DownloadItemProto.newBuilder()
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
                                }
                            )
                            .setReceivedBytes(item.receivedBytes)
                            .setTotalBytes(item.totalBytes ?: -1L)
                            .setSpeed(item.speed)
                            .setCanPause(item.canPause)
                            .setCanResume(item.canResume)
                            .setErrorReason(item.errorReason ?: "")
                            .setStartTime(item.startTime)
                            .setEndTime(item.endTime ?: 0L)
                            .build()
                    })
                    .build()
            )
        }
    }

    override suspend fun pauseDownload(request: DownloadIdRequest): OperationResult {
        return provider.pauseDownload(request.id).toOperationResult()
    }

    override suspend fun resumeDownload(request: DownloadIdRequest): OperationResult {
        return provider.resumeDownload(request.id).toOperationResult()
    }

    override suspend fun cancelDownload(request: DownloadIdRequest): OperationResult {
        return provider.cancelDownload(request.id).toOperationResult()
    }

    override suspend fun removeDownload(request: DownloadIdRequest): OperationResult {
        return provider.removeDownload(request.id).toOperationResult()
    }

    override suspend fun clearCompleted(request: Empty): OperationResult {
        return provider.clearCompleted().toOperationResult()
    }

    override suspend fun revealInFolder(request: PathRequest): Empty {
        provider.revealInFolder(request.path)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFile(request: PathRequest): Empty {
        provider.openFile(request.path)
        return Empty.getDefaultInstance()
    }

    private fun Result<Unit>.toOperationResult(): OperationResult =
        fold(
            onSuccess = {
                OperationResult.newBuilder().setSuccess(true).build()
            },
            onFailure = { error ->
                OperationResult.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            }
        )
}
