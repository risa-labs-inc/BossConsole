package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.util.concurrent.TimeUnit

/**
 * gRPC implementation of FileSystemService.
 * Provides real file I/O using Java NIO. WatchFileChanges uses
 * java.nio.file.WatchService for live filesystem change events.
 */
class FileSystemServiceImpl : FileSystemServiceGrpcKt.FileSystemServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(FileSystemServiceImpl::class.java)

    /** Paths that must not be accessed via IPC — prevents privilege-escalation via path injection. */
    private val BLOCKED_PATH_PREFIXES = listOf("/etc", "/sys", "/proc")

    /**
     * Validates that [path] does not traverse outside its intended root and does not
     * target sensitive system directories. Throws [IllegalArgumentException] on violation.
     */
    private fun validatePath(path: String) {
        require(!path.contains("..")) { "Path traversal sequences ('..') are not allowed: $path" }
        BLOCKED_PATH_PREFIXES.forEach { prefix ->
            require(!path.startsWith(prefix)) { "Access to system path '$prefix' is not allowed: $path" }
        }
    }

    override suspend fun scanDirectory(request: ScanDirectoryRequest): ScanDirectoryResponse =
        withContext(Dispatchers.IO) {
            logger.debug("scanDirectory: path={}, recursive={}", request.path, request.recursive)
            validatePath(request.path)
            val dir = File(request.path)
            if (!dir.exists() || !dir.isDirectory) {
                return@withContext ScanDirectoryResponse.newBuilder()
                    .setErrorMessage("Directory not found: ${request.path}")
                    .build()
            }

            val maxDepth = if (request.maxDepth > 0) request.maxDepth else Int.MAX_VALUE
            val sequence: Sequence<File> = if (request.recursive) {
                dir.walkTopDown().maxDepth(maxDepth)
            } else {
                dir.listFiles()?.asSequence() ?: emptySequence()
            }

            val filterExtensions = request.extensionsList.toSet()

            val entries = sequence
                .filter { it != dir }
                .filter { request.includeHidden || !it.name.startsWith(".") }
                .filter { it.isDirectory || filterExtensions.isEmpty() || it.extension in filterExtensions }
                .map { f ->
                    FileEntry.newBuilder()
                        .setPath(f.absolutePath)
                        .setName(f.name)
                        .setIsDirectory(f.isDirectory)
                        .setSizeBytes(if (f.isDirectory) 0L else f.length())
                        .setModifiedAt(f.lastModified())
                        .setIsHidden(f.name.startsWith("."))
                        .build()
                }.toList()

            ScanDirectoryResponse.newBuilder()
                .addAllEntries(entries)
                .build()
        }

    override suspend fun readFile(request: ReadFileRequest): ReadFileResponse =
        withContext(Dispatchers.IO) {
            logger.debug("readFile: path={}", request.path)
            validatePath(request.path)
            val file = File(request.path)
            if (!file.exists()) {
                return@withContext ReadFileResponse.newBuilder()
                    .setErrorMessage("File not found: ${request.path}")
                    .build()
            }
            return@withContext try {
                val totalSize = file.length()
                val bytes = file.readBytes()
                val offsetBytes = request.offsetBytes.coerceAtLeast(0L).toInt()
                val slice = if (offsetBytes > 0 && offsetBytes < bytes.size) bytes.drop(offsetBytes).toByteArray() else bytes
                val maxBytes = request.maxBytes
                val (content, truncated) = if (maxBytes > 0 && slice.size > maxBytes) {
                    slice.take(maxBytes.toInt()).toByteArray() to true
                } else {
                    slice to false
                }
                ReadFileResponse.newBuilder()
                    .setContent(ByteString.copyFrom(content))
                    .setTotalSizeBytes(totalSize)
                    .setTruncated(truncated)
                    .build()
            } catch (e: Exception) {
                ReadFileResponse.newBuilder()
                    .setErrorMessage(e.message ?: "Read failed")
                    .build()
            }
        }

    override suspend fun writeFile(request: WriteFileRequest): WriteFileResponse =
        withContext(Dispatchers.IO) {
            logger.debug("writeFile: path={}", request.path)
            validatePath(request.path)
            return@withContext try {
                val file = File(request.path)
                if (request.createParents) file.parentFile?.mkdirs()
                if (!request.overwrite && file.exists()) {
                    return@withContext WriteFileResponse.newBuilder()
                        .setSuccess(false)
                        .setErrorMessage("File already exists: ${request.path}")
                        .build()
                }
                val bytes = request.content.toByteArray()
                file.writeBytes(bytes)
                WriteFileResponse.newBuilder()
                    .setSuccess(true)
                    .setBytesWritten(bytes.size.toLong())
                    .build()
            } catch (e: Exception) {
                WriteFileResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.message ?: "Write failed")
                    .build()
            }
        }

    override suspend fun createFile(request: CreateFileRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("createFile: path={}, isDirectory={}", request.path, request.isDirectory)
            validatePath(request.path)
            val file = File(request.path)
            if (request.createParents) file.parentFile?.mkdirs()
            if (request.isDirectory) file.mkdirs() else file.createNewFile()
            Empty.getDefaultInstance()
        }

    override suspend fun deleteFile(request: DeleteFileRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("deleteFile: path={}, recursive={}", request.path, request.recursive)
            validatePath(request.path)
            val file = File(request.path)
            if (request.recursive && file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            Empty.getDefaultInstance()
        }

    override suspend fun renameFile(request: RenameFileRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("renameFile: from={}, to={}", request.sourcePath, request.destinationPath)
            validatePath(request.sourcePath)
            validatePath(request.destinationPath)
            val dest = File(request.destinationPath)
            if (!request.overwrite && dest.exists()) {
                throw IllegalStateException("Destination already exists: ${request.destinationPath}")
            }
            File(request.sourcePath).renameTo(dest)
            Empty.getDefaultInstance()
        }

    override fun watchFileChanges(request: WatchFileChangesRequest): Flow<FileChangeEvent> = flow {
        logger.info("watchFileChanges: path={}, recursive={}", request.path, request.recursive)
        validatePath(request.path)
        val root = Paths.get(request.path)
        if (!Files.exists(root)) {
            logger.warn("watchFileChanges: path not found: {}", request.path)
            return@flow
        }

        val kinds = arrayOf(
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )

        val watchService = FileSystems.getDefault().newWatchService()
        try {
            // Register root (and subdirs if recursive)
            root.register(watchService, *kinds)
            if (request.recursive && Files.isDirectory(root)) {
                Files.walk(root)
                    .filter { Files.isDirectory(it) && it != root }
                    .forEach { dir ->
                        try { dir.register(watchService, *kinds) } catch (_: Exception) {}
                    }
            }

            while (currentCoroutineContext().isActive) {
                val key = withContext(Dispatchers.IO) {
                    watchService.poll(500, TimeUnit.MILLISECONDS)
                } ?: continue

                for (event in key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue

                    @Suppress("UNCHECKED_CAST")
                    val ev = event as WatchEvent<Path>
                    val dir = key.watchable() as Path
                    val filePath = dir.resolve(ev.context())

                    val changeType = when (event.kind()) {
                        StandardWatchEventKinds.ENTRY_CREATE -> FileChangeType.FILE_CHANGE_TYPE_CREATED
                        StandardWatchEventKinds.ENTRY_MODIFY -> FileChangeType.FILE_CHANGE_TYPE_MODIFIED
                        StandardWatchEventKinds.ENTRY_DELETE -> FileChangeType.FILE_CHANGE_TYPE_DELETED
                        else -> FileChangeType.FILE_CHANGE_TYPE_UNSPECIFIED
                    }

                    // Register newly created directories for recursive watching
                    if (request.recursive
                        && event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                        && Files.isDirectory(filePath)
                    ) {
                        try { filePath.register(watchService, *kinds) } catch (_: Exception) {}
                    }

                    emit(
                        FileChangeEvent.newBuilder()
                            .setPath(filePath.toAbsolutePath().toString())
                            .setChangeType(changeType)
                            .setTimestamp(System.currentTimeMillis())
                            .build()
                    )
                }

                if (!key.reset()) {
                    logger.info("watchFileChanges: watch key invalid, stopping: {}", request.path)
                    break
                }
            }
        } finally {
            watchService.close()
        }
    }
}
