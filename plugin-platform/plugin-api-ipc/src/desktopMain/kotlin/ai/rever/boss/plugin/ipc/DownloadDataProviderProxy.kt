package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.DownloadItemData
import ai.rever.boss.plugin.api.DownloadStatusData
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
 * IPC proxy implementation of DownloadDataProvider.
 */
class DownloadDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : DownloadDataProvider {

    private val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(channel)

    private val _downloads = MutableStateFlow<List<DownloadItemData>>(emptyList())
    override val downloads: StateFlow<List<DownloadItemData>> = _downloads.asStateFlow()

    init {
        scope.launch { watchDownloads() }
    }

    private suspend fun watchDownloads() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchDownloads(Empty.getDefaultInstance()).collect { response ->
                    _downloads.value = response.downloadsList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    override suspend fun pauseDownload(id: String): Result<Unit> = runOp {
        stub.pauseDownload(DownloadIdRequest.newBuilder().setId(id).build())
    }

    override suspend fun resumeDownload(id: String): Result<Unit> = runOp {
        stub.resumeDownload(DownloadIdRequest.newBuilder().setId(id).build())
    }

    override suspend fun cancelDownload(id: String): Result<Unit> = runOp {
        stub.cancelDownload(DownloadIdRequest.newBuilder().setId(id).build())
    }

    override suspend fun removeDownload(id: String): Result<Unit> = runOp {
        stub.removeDownload(DownloadIdRequest.newBuilder().setId(id).build())
    }

    override suspend fun clearCompleted(): Result<Unit> = runOp {
        stub.clearCompleted(Empty.getDefaultInstance())
    }

    override fun revealInFolder(path: String) {
        scope.launch {
            try { stub.revealInFolder(PathRequest.newBuilder().setPath(path).build()) }
            catch (_: Exception) {}
        }
    }

    override fun openFile(path: String) {
        scope.launch {
            try { stub.openFile(PathRequest.newBuilder().setPath(path).build()) }
            catch (_: Exception) {}
        }
    }

    private suspend fun runOp(block: suspend () -> OperationResult): Result<Unit> = try {
        val result = block()
        if (result.success) Result.success(Unit) else Result.failure(Exception(result.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    private fun DownloadItemProto.toData() = DownloadItemData(
        id = id,
        fileName = fileName,
        destinationPath = destinationPath,
        url = url,
        status = when (status) {
            DownloadStatusProto.DOWNLOAD_STATUS_QUEUED -> DownloadStatusData.QUEUED
            DownloadStatusProto.DOWNLOAD_STATUS_DOWNLOADING -> DownloadStatusData.DOWNLOADING
            DownloadStatusProto.DOWNLOAD_STATUS_PAUSED -> DownloadStatusData.PAUSED
            DownloadStatusProto.DOWNLOAD_STATUS_COMPLETED -> DownloadStatusData.COMPLETED
            DownloadStatusProto.DOWNLOAD_STATUS_FAILED -> DownloadStatusData.FAILED
            DownloadStatusProto.DOWNLOAD_STATUS_CANCELLED -> DownloadStatusData.CANCELLED
            else -> DownloadStatusData.QUEUED
        },
        receivedBytes = receivedBytes,
        totalBytes = if (totalBytes > 0) totalBytes else null,
        speed = speed,
        canPause = canPause,
        canResume = canResume,
        errorReason = errorReason.takeIf { it.isNotEmpty() },
        startTime = startTime,
        endTime = if (endTime > 0) endTime else null,
    )
}
