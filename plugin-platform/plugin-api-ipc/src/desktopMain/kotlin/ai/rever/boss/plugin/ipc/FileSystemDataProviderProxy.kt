package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.EventBusServiceGrpcKt
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import ai.rever.boss.ipc.proto.services.WriteFileRequest
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.NodeLoadingStateData
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * IPC proxy implementation of FileSystemDataProvider.
 *
 * Data-access operations (scan, read, write, create, delete, rename) are
 * forwarded to the FileSystemService gRPC endpoint.
 *
 * UI-trigger operations (openFile, revealInFileManager, copyToClipboard)
 * publish JSON events to the EventBusService so the kernel-side UI can
 * act on them. Methods that are pure system queries (home/downloads dir,
 * hasChildren) are answered locally without an IPC round-trip.
 */
class FileSystemDataProviderProxy(
    private val fsChannel: ManagedChannel,
    private val eventChannel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : FileSystemDataProvider {
    private val fsStub = FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub(fsChannel)
    private val eventStub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(eventChannel)

    // ---- Data-access methods (via gRPC) ----
    // Scan results are re-filtered through ProviderScanFilter: the kernel's
    // general-purpose service doesn't enforce provider-contract semantics.

    override suspend fun scanDirectory(path: String): FileNodeData? = scanDirectory(path, showHidden = false)

    override suspend fun scanDirectory(
        path: String,
        showHidden: Boolean,
    ): FileNodeData? =
        withContext(Dispatchers.Default) {
            try {
                val response =
                    fsStub.scanDirectory(
                        ScanDirectoryRequest
                            .newBuilder()
                            .setPath(path)
                            .setRecursive(false)
                            .setIncludeHidden(showHidden)
                            .build(),
                    )
                if (response.errorMessage.isNotBlank()) return@withContext null
                val dir = File(path)
                FileNodeData(
                    name = dir.name,
                    path = path,
                    isDirectory = true,
                    children =
                        response.entriesList
                            .filter { ProviderScanFilter.isVisibleProviderEntry(path, it.path, showHidden) }
                            .map { entry ->
                                FileNodeData(
                                    name = entry.name,
                                    path = entry.path,
                                    isDirectory = entry.isDirectory,
                                    loadingState = NodeLoadingStateData.LOADED,
                                )
                            },
                    loadingState = NodeLoadingStateData.LOADED,
                )
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
    ): FileNodeData? = scanDirectoryWithDepth(path, maxDepth, startDepth, showHidden = false)

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
        showHidden: Boolean,
    ): FileNodeData? =
        withContext(Dispatchers.Default) {
            try {
                val response =
                    fsStub.scanDirectory(
                        ScanDirectoryRequest
                            .newBuilder()
                            .setPath(path)
                            .setRecursive(maxDepth > 1)
                            .setMaxDepth(maxDepth)
                            .setIncludeHidden(showHidden)
                            .build(),
                    )
                if (response.errorMessage.isNotBlank()) return@withContext null
                val dir = File(path)
                FileNodeData(
                    name = dir.name,
                    path = path,
                    isDirectory = true,
                    children =
                        response.entriesList
                            .filter { ProviderScanFilter.isVisibleProviderEntry(path, it.path, showHidden) }
                            .map { entry ->
                                FileNodeData(
                                    name = entry.name,
                                    path = entry.path,
                                    isDirectory = entry.isDirectory,
                                    loadingState = NodeLoadingStateData.LOADED,
                                )
                            },
                    loadingState = NodeLoadingStateData.LOADED,
                )
            } catch (_: Exception) {
                null
            }
        }

    /** Answered locally — cheap O(1) check without a gRPC round-trip. */
    override fun directoryHasChildren(path: String): Boolean = directoryHasChildren(path, showHidden = false)

    override fun directoryHasChildren(
        path: String,
        showHidden: Boolean,
    ): Boolean = File(path).listFiles()?.any { ProviderScanFilter.isVisibleLocalEntry(it.name, showHidden) } ?: false

    // The FileSystemService proto has carried include_hidden since v1 and the
    // kernel-side service honors it, so the showHidden overloads work
    // end-to-end over IPC. The provider-contract skip-list (build/
    // node_modules) is applied proxy-side via isVisibleProviderEntry so scan
    // results match the in-process provider regardless of transport.
    override val supportsHiddenEntries: Boolean get() = true

    override suspend fun createFile(
        parentPath: String,
        fileName: String,
    ): Result<String> =
        withContext(Dispatchers.Default) {
            try {
                val fullPath = "$parentPath/$fileName"
                fsStub.createFile(
                    CreateFileRequest
                        .newBuilder()
                        .setPath(fullPath)
                        .setIsDirectory(false)
                        .setCreateParents(true)
                        .build(),
                )
                Result.success(fullPath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun createFolder(
        parentPath: String,
        folderName: String,
    ): Result<String> =
        withContext(Dispatchers.Default) {
            try {
                val fullPath = "$parentPath/$folderName"
                fsStub.createFile(
                    CreateFileRequest
                        .newBuilder()
                        .setPath(fullPath)
                        .setIsDirectory(true)
                        .setCreateParents(true)
                        .build(),
                )
                Result.success(fullPath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.Default) {
            try {
                fsStub.deleteFile(
                    DeleteFileRequest
                        .newBuilder()
                        .setPath(path)
                        .setRecursive(true)
                        .build(),
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun rename(
        path: String,
        newName: String,
    ): Result<String> =
        withContext(Dispatchers.Default) {
            try {
                val parent = File(path).parent ?: ""
                val newPath = if (parent.isNotBlank()) "$parent/$newName" else newName
                fsStub.renameFile(
                    RenameFileRequest
                        .newBuilder()
                        .setSourcePath(path)
                        .setDestinationPath(newPath)
                        .setOverwrite(false)
                        .build(),
                )
                Result.success(newPath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun writeFile(
        path: String,
        content: String,
    ): Result<Unit> =
        withContext(Dispatchers.Default) {
            try {
                fsStub.writeFile(
                    WriteFileRequest
                        .newBuilder()
                        .setPath(path)
                        .setContent(ByteString.copyFromUtf8(content))
                        .setCreateParents(true)
                        .setOverwrite(true)
                        .build(),
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun readFile(path: String): Result<String> =
        withContext(Dispatchers.Default) {
            try {
                val content = ByteArrayOutputStream()
                var expectedSize: Long? = null
                do {
                    val response =
                        fsStub.readFile(
                            ReadFileRequest
                                .newBuilder()
                                .setPath(path)
                                .setOffsetBytes(content.size().toLong())
                                .setMaxBytes(1_048_576)
                                .build(),
                        )
                    check(response.errorMessage.isBlank()) { response.errorMessage }
                    check(
                        response.totalSizeBytes in 0..MAX_TEXT_BYTES.toLong() &&
                            content.size() + response.content.size() <= MAX_TEXT_BYTES,
                    ) {
                        "Text files larger than 8 MiB cannot be read through the plugin provider"
                    }
                    check(expectedSize == null || expectedSize == response.totalSizeBytes) {
                        "File changed during read; retry"
                    }
                    expectedSize = response.totalSizeBytes
                    check(!response.truncated || !response.content.isEmpty) {
                        "File service returned an empty partial page"
                    }
                    response.content.writeTo(content)
                } while (response.truncated)
                check(content.size().toLong() == expectedSize) { "File changed during read; retry" }
                Result.success(content.toString(Charsets.UTF_8))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---- UI-trigger methods (via EventBus) ----

    override fun openFile(
        path: String,
        windowId: String,
    ) {
        scope.launch {
            publishEvent(
                type = "boss.ui.OpenFileEvent",
                payload = """{"path":${path.jsonEscape()},"windowId":${windowId.jsonEscape()}}""",
                windowId = windowId,
            )
        }
    }

    override fun revealInFileManager(path: String): Result<Unit> {
        scope.launch {
            publishEvent(
                type = "boss.ui.RevealInFileManagerEvent",
                payload = """{"path":${path.jsonEscape()}}""",
            )
        }
        return Result.success(Unit)
    }

    override fun copyToClipboard(text: String): Result<Unit> {
        scope.launch {
            publishEvent(
                type = "boss.ui.CopyToClipboardEvent",
                payload = """{"text":${text.jsonEscape()}}""",
            )
        }
        return Result.success(Unit)
    }

    // ---- Pure system queries (answered locally) ----

    override fun getDownloadsDirectory(): String = System.getProperty("user.home") + "/Downloads"

    override fun getHomeDirectory(): String = System.getProperty("user.home")

    // ---- Helpers ----

    private suspend fun publishEvent(
        type: String,
        payload: String,
        windowId: String = "",
    ) {
        try {
            eventStub.publish(
                EventEnvelope
                    .newBuilder()
                    .setEventType(type)
                    .setPayload(
                        com.google.protobuf.ByteString
                            .copyFromUtf8(payload),
                    ).setSourceWindowId(windowId)
                    .setTimestamp(System.currentTimeMillis())
                    .build(),
            )
        } catch (_: Exception) {
        }
    }

    private fun String.jsonEscape(): String {
        val sb = StringBuilder("\"")
        for (ch in this) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}

// This provider returns a complete in-memory String. Larger files require a streaming editor API.
private const val MAX_TEXT_BYTES = 8_388_608
