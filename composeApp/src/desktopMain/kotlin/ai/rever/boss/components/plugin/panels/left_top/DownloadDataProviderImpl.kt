package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.components.plugin.tab_types.fluck.DownloadItem
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadManager
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadStatus
import ai.rever.boss.platform.FileSystemUtils
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.DownloadItemData
import ai.rever.boss.plugin.api.DownloadStatusData
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

private val logger = BossLogger.forComponent("DownloadDataProviderImpl")

/**
 * Engine-level download controls used by [DownloadDataProviderImpl].
 *
 * Every command reports whether the browser engine still owns the download:
 * JxBrowser releases its handle as soon as a download finishes, fails or is
 * cancelled, and a command for an id the engine no longer knows must surface as
 * a failure rather than as a success the engine never performed.
 */
internal interface DownloadEngineController {
    fun pause(id: String): Boolean

    fun resume(id: String): Boolean

    fun cancel(id: String): Boolean
}

/** Routes engine commands to the live JxBrowser downloads owned by FluckEngine. */
internal object FluckDownloadEngineController : DownloadEngineController {
    override fun pause(id: String): Boolean = FluckEngine.pauseDownload(id)

    override fun resume(id: String): Boolean = FluckEngine.resumeDownload(id)

    override fun cancel(id: String): Boolean = FluckEngine.cancelDownload(id)
}

/**
 * True when [path] is a tracked download's destination, compared canonically so a `..` segment or a link
 * cannot name a tracked file from outside it. A blank path is never tracked, whatever a download's own
 * destination says.
 */
internal fun isTrackedDownload(
    downloads: List<DownloadItemData>,
    path: String,
): Boolean {
    val requested = path.takeIf { it.isNotBlank() }?.let { runCatching { File(it).canonicalFile }.getOrNull() }
    return requested != null &&
        downloads.any { item ->
            item.destinationPath.isNotBlank() &&
                runCatching { File(item.destinationPath).canonicalFile }.getOrNull() == requested
        }
}

private fun refuseUntracked(call: String) {
    logger.warn(LogCategory.FILE, "Refused $call: the path is not a tracked download", mapOf("call" to call))
}

/**
 * [manager]'s tracked downloads, read live rather than through its throttled `downloads` flow (sampled
 * every 150ms for the UI). The confinement check needs the download the instant it is tracked and the
 * instant it stops being tracked, not once the throttle catches up - the same reason `getDownload`
 * bypasses it for a single id.
 */
private fun liveDownloads(manager: DownloadManager): List<DownloadItemData> = manager.allDownloads().map { it.toData() }

private fun DownloadItem.toData(): DownloadItemData =
    DownloadItemData(
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
        endTime = finishedAt,
    )

private fun DownloadStatus.toData(): DownloadStatusData =
    when (this) {
        DownloadStatus.QUEUED -> DownloadStatusData.QUEUED
        DownloadStatus.DOWNLOADING -> DownloadStatusData.DOWNLOADING
        DownloadStatus.PAUSED -> DownloadStatusData.PAUSED
        DownloadStatus.COMPLETED -> DownloadStatusData.COMPLETED
        DownloadStatus.FAILED -> DownloadStatusData.FAILED
        DownloadStatus.CANCELLED -> DownloadStatusData.CANCELLED
    }

/**
 * Implementation of DownloadDataProvider that wraps FluckEngine's download management.
 *
 * The [downloadManager], [engine] and [collectorContext] seams exist so the
 * command routing can be tested without starting a JxBrowser engine. They are
 * internal; production constructs this with the public no-arg constructor.
 */
class DownloadDataProviderImpl internal constructor(
    private val downloadManager: DownloadManager,
    private val engine: DownloadEngineController,
    collectorContext: CoroutineContext,
    private val opener: (String) -> Unit = FileSystemUtils::openFile,
    private val revealer: (String) -> Unit = FileSystemUtils::revealInFolder,
) : DownloadDataProvider {
    constructor() : this(
        downloadManager = FluckEngine.downloadManager,
        engine = FluckDownloadEngineController,
        collectorContext = Dispatchers.Main,
    )

    private val scope = CoroutineScope(collectorContext + SupervisorJob())

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

    override suspend fun pauseDownload(id: String): Result<Unit> = engineCommand(id, "pause") { engine.pause(id) }

    override suspend fun resumeDownload(id: String): Result<Unit> = engineCommand(id, "resume") { engine.resume(id) }

    override suspend fun cancelDownload(id: String): Result<Unit> = engineCommand(id, "cancel") { engine.cancel(id) }

    override suspend fun removeDownload(id: String): Result<Unit> =
        try {
            // Read the live map, not the throttled `downloads` flow, which lags
            // by up to a sampling window and can still be missing a download
            // that just started.
            val download = downloadManager.getDownload(id)
            if (download != null && download.status == DownloadStatus.COMPLETED) {
                // A missing file is already removed. Other filesystem failures
                // must retain the entry so the user can retry or reveal it.
                Files.deleteIfExists(Path.of(download.destinationPath))
            } else if (download != null && !download.isTerminal) {
                // Cancel in the engine BEFORE dropping the tracking entry. A
                // download that is still QUEUED, DOWNLOADING or PAUSED is owned
                // by Chromium, which keeps writing bytes to the partial file;
                // removing only the tracking entry leaves that write untracked,
                // unstoppable and never cleaned up.
                val cancelled = engine.cancel(id)
                val settled = downloadManager.getDownload(id)
                check(cancelled || settled == null || settled.isTerminal) {
                    "The download is still active. Could not cancel it; retry removal."
                }
                if (settled?.status == DownloadStatus.COMPLETED) {
                    // Completion can win the race with cancellation. Apply the
                    // same deletion contract as an already-completed download.
                    Files.deleteIfExists(Path.of(settled.destinationPath))
                } else {
                    FileSystemUtils.cleanupPartialFile(download.destinationPath)
                }
            }
            // Unknown ids and already FAILED/CANCELLED downloads need no engine
            // command: the engine released them and its listener cleaned up.
            downloadManager.removeDownload(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to remove download", error = e)
            Result.failure(e)
        }

    override suspend fun clearCompleted(): Result<Unit> =
        try {
            // Delegates so the filtering runs against the live map instead of
            // the throttled snapshot exposed to the UI.
            downloadManager.clearCompleted()
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to clear completed downloads", error = e)
            Result.failure(e)
        }

    /**
     * Opens [path] with the OS default application, but only a path this provider is tracking as a
     * download. `openFile` is a "launch this file" call, and for an executable that is the same action as
     * a double-click; without the confinement every installed plugin could launch any file on disk. The
     * kernel-facing `DownloadServiceBridge` already applies this rule, and it lives here so that the
     * in-process API and the bridge cannot disagree about it.
     */
    override fun openFile(path: String) {
        if (isTrackedDownload(liveDownloads(downloadManager), path)) opener(path) else refuseUntracked("openFile")
    }

    override fun revealInFolder(path: String) {
        val tracked = isTrackedDownload(liveDownloads(downloadManager), path)
        if (tracked) revealer(path) else refuseUntracked("revealInFolder")
    }

    /**
     * Issues an engine command, turning "the engine does not own this download"
     * into a failure so the panel and the download_* MCP tools cannot report a
     * state Chromium is not in.
     */
    private fun engineCommand(
        id: String,
        commandName: String,
        command: () -> Boolean,
    ): Result<Unit> =
        try {
            if (command()) {
                Result.success(Unit)
            } else {
                logger.warn(
                    LogCategory.BROWSER,
                    "Engine rejected download command",
                    mapOf("id" to id, "command" to commandName),
                )
                Result.failure(IllegalStateException("Download $id is not active in the browser engine"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to $commandName download", error = e)
            Result.failure(e)
        }
}
