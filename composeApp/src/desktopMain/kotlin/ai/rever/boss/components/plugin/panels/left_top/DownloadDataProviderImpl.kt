package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.components.plugin.tab_types.fluck.DownloadItem
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadManager
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadStatus
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.platform.FileSystemUtils
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.DownloadItemData
import ai.rever.boss.plugin.api.DownloadStatusData
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private val logger = BossLogger.forComponent("DownloadDataProviderImpl")

/**
 * Implementation of DownloadDataProvider that wraps FluckEngine's download management.
 */
class DownloadDataProviderImpl : DownloadDataProvider {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val downloadManager: DownloadManager = FluckEngine.downloadManager

    private val _downloads = MutableStateFlow<List<DownloadItemData>>(emptyList())
    override val downloads: StateFlow<List<DownloadItemData>> = _downloads

    init {
        // Collect from DownloadManager and map to plugin API types
        scope.launch {
            downloadManager.downloads.collect { items ->
                _downloads.value = items.map { it.toData() }
            }
        }
    }

    override suspend fun pauseDownload(id: String): Result<Unit> {
        return try {
            FluckEngine.pauseDownload(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to pause download", error = e)
            Result.failure(e)
        }
    }

    override suspend fun resumeDownload(id: String): Result<Unit> {
        return try {
            FluckEngine.resumeDownload(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to resume download", error = e)
            Result.failure(e)
        }
    }

    override suspend fun cancelDownload(id: String): Result<Unit> {
        return try {
            FluckEngine.cancelDownload(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to cancel download", error = e)
            Result.failure(e)
        }
    }

    override suspend fun removeDownload(id: String): Result<Unit> {
        return try {
            // Find the download to get its path
            val download = downloadManager.downloads.value.find { it.id == id }
            if (download != null && download.status == DownloadStatus.COMPLETED) {
                // Delete the file if it exists
                val file = File(download.destinationPath)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        logger.debug(LogCategory.FILE, "Deleted file", mapOf("path" to download.destinationPath))
                    } else {
                        logger.warn(LogCategory.FILE, "Failed to delete file", mapOf("path" to download.destinationPath))
                    }
                }
            } else if (download?.status == DownloadStatus.PAUSED) {
                // Clean up partial file for paused downloads
                FileSystemUtils.cleanupPartialFile(download.destinationPath)
            }
            downloadManager.removeDownload(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to remove download", error = e)
            Result.failure(e)
        }
    }

    override suspend fun clearCompleted(): Result<Unit> {
        return try {
            val completedIds = downloadManager.downloads.value
                .filter { it.status == DownloadStatus.COMPLETED }
                .map { it.id }
            completedIds.forEach { id ->
                downloadManager.removeDownload(id)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to clear completed downloads", error = e)
            Result.failure(e)
        }
    }

    override fun revealInFolder(path: String) {
        FileSystemUtils.revealInFolder(path)
    }

    override fun openFile(path: String) {
        FileSystemUtils.openFile(path)
    }

    // ===== Type Conversion Extension =====

    private fun DownloadItem.toData(): DownloadItemData = DownloadItemData(
        id = id,
        fileName = fileName,
        destinationPath = destinationPath,
        url = url,
        status = status.toData(),
        receivedBytes = receivedBytes,
        totalBytes = totalBytes,
        speed = speed,
        canPause = canPause,
        canResume = canResume,
        errorReason = errorReason,
        startTime = startedAt,
        endTime = finishedAt
    )

    private fun DownloadStatus.toData(): DownloadStatusData = when (this) {
        DownloadStatus.QUEUED -> DownloadStatusData.QUEUED
        DownloadStatus.DOWNLOADING -> DownloadStatusData.DOWNLOADING
        DownloadStatus.PAUSED -> DownloadStatusData.PAUSED
        DownloadStatus.COMPLETED -> DownloadStatusData.COMPLETED
        DownloadStatus.FAILED -> DownloadStatusData.FAILED
        DownloadStatus.CANCELLED -> DownloadStatusData.CANCELLED
    }
}
