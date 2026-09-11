package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * gRPC implementation of FileSystemService.
 * Provides real file I/O using Java NIO. WatchFileChanges uses
 * java.nio.file.WatchService for live filesystem change events.
 */
class FileSystemServiceImpl : FileSystemServiceGrpcKt.FileSystemServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(FileSystemServiceImpl::class.java)

    override suspend fun scanDirectory(request: ScanDirectoryRequest): ScanDirectoryResponse =
        withContext(Dispatchers.IO) {
            FilePathPolicy.authorize(request.path)
            logger.debug("scanDirectory: path={}, recursive={}", request.path, request.recursive)
            val dir = File(request.path)
            if (!dir.exists() || !dir.isDirectory) {
                return@withContext ScanDirectoryResponse
                    .newBuilder()
                    .setErrorMessage("Directory not found: ${request.path}")
                    .build()
            }

            val entries = BoundedDirectoryScan(request, currentCoroutineContext()).scan(dir.toPath().toAbsolutePath())

            ScanDirectoryResponse
                .newBuilder()
                .addAllEntries(entries)
                .build()
        }

    override suspend fun readFile(request: ReadFileRequest): ReadFileResponse =
        withContext(Dispatchers.IO) {
            FilePathPolicy.authorize(request.path)
            logger.debug("readFile: path={}", request.path)
            val file = File(request.path)
            if (!file.exists()) {
                return@withContext ReadFileResponse
                    .newBuilder()
                    .setErrorMessage("File not found: ${request.path}")
                    .build()
            }
            return@withContext try {
                require(request.offsetBytes >= 0 && request.maxBytes >= 0) {
                    "Read offsets and limits must be nonnegative"
                }
                val maximum =
                    request.maxBytes
                        .takeIf { it > 0 }
                        ?.coerceAtMost(FileSystemLimits.READ_BYTES.toLong())
                        ?.toInt() ?: FileSystemLimits.READ_BYTES
                openRegularFile(file.toPath()).use { reader ->
                    val totalSize = reader.size
                    val bytes = reader.readPage(request.offsetBytes, maximum + 1)
                    currentCoroutineContext().ensureActive()
                    val truncated = bytes.size > maximum
                    // Legacy 'all' readers may ignore truncated. Refuse instead of returning partial text.
                    if (truncated && request.maxBytes == 0L) {
                        throw fileSystemLimit(
                            "File exceeds one response; read it using explicit byte limits and offsets",
                        )
                    }
                    ReadFileResponse
                        .newBuilder()
                        .setContent(ByteString.copyFrom(bytes, 0, bytes.size.coerceAtMost(maximum)))
                        .setTotalSizeBytes(totalSize)
                        .setTruncated(truncated)
                        .build()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReadFileResponse
                    .newBuilder()
                    .setErrorMessage(e.message ?: "Read failed")
                    .build()
            }
        }

    override suspend fun writeFile(request: WriteFileRequest): WriteFileResponse =
        withContext(Dispatchers.IO) {
            FilePathPolicy.authorize(request.path)
            logger.debug("writeFile: path={}", request.path)
            return@withContext try {
                val file = File(request.path)
                if (request.createParents) file.parentFile?.mkdirs()
                if (!request.overwrite && file.exists()) {
                    return@withContext WriteFileResponse
                        .newBuilder()
                        .setSuccess(false)
                        .setErrorMessage("File already exists: ${request.path}")
                        .build()
                }
                val bytes = request.content.toByteArray()
                file.writeBytes(bytes)
                WriteFileResponse
                    .newBuilder()
                    .setSuccess(true)
                    .setBytesWritten(bytes.size.toLong())
                    .build()
            } catch (e: Exception) {
                WriteFileResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.message ?: "Write failed")
                    .build()
            }
        }

    override suspend fun createFile(request: CreateFileRequest): Empty =
        withContext(Dispatchers.IO) {
            FilePathPolicy.authorize(request.path)
            logger.info("createFile: path={}, isDirectory={}", request.path, request.isDirectory)
            val file = File(request.path)
            if (request.createParents) file.parentFile?.mkdirs()
            if (request.isDirectory) file.mkdirs() else file.createNewFile()
            Empty.getDefaultInstance()
        }

    override suspend fun deleteFile(request: DeleteFileRequest): Empty =
        withContext(Dispatchers.IO) {
            FilePathPolicy.authorize(request.path)
            logger.info("deleteFile: path={}, recursive={}", request.path, request.recursive)
            val file = File(request.path)
            val deleted =
                if (request.recursive && file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            // DeleteFileResponse has no error field - like renameFile, the status is the only
            // channel a failure has. Returning Empty here reports every refused delete,
            // including a recursive removal that only partly succeeded, as success.
            if (!deleted) {
                if (file.exists()) {
                    throw status(Status.PERMISSION_DENIED, "Delete failed: ${request.path}", null)
                }
                throw status(Status.NOT_FOUND, "No such file: ${request.path}", null)
            }
            Empty.getDefaultInstance()
        }

    /**
     * Moves [source] onto [dest], replacing [dest] if it exists.
     *
     * **Not `File.renameTo`.** That call's behaviour when the destination exists is
     * platform-dependent in the direction that hides the bug during development: POSIX
     * `rename(2)` replaces the target, so macOS and Linux work, while Win32 `MoveFile` fails with
     * `ERROR_ALREADY_EXISTS`. `overwrite = true` — the request this API explicitly offers — was
     * therefore the one case that could never work on Windows.
     *
     * `ATOMIC_MOVE` is tried first and dropped for a cross-volume move, which unlike the in-process
     * caches is a real possibility for an arbitrary path pair from IPC. The non-atomic form then
     * falls back to copy-and-delete.
     *
     * `composeApp` has an `atomicMoveFrom` doing the same job, but this module builds standalone —
     * a GraalVM native image over `:boss-ipc` alone — so six lines here beat a dependency edge from
     * the microkernel services into the app.
     */
    private fun moveReplacing(
        source: Path,
        dest: Path,
    ) {
        try {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Renames or moves a file.
     *
     * **Throws on failure.** This previously discarded `renameTo`'s boolean and returned `Empty`
     * unconditionally, so every failure — a missing source, a permissions error, the Windows
     * overwrite case above — was reported to the caller as success. A silent no-op is worse than
     * the platform bug it was hiding.
     *
     * Failures are raised as [StatusException] rather than as the underlying [IOException], because
     * only the former reaches the caller intact: gRPC deliberately does not leak exception messages,
     * so an `IOException` out of a handler arrives as a bare `UNKNOWN` with no description, and a
     * plugin author would see the same opaque failure for a missing source as for a refused
     * overwrite. `Empty` leaves no response field to put an error in — unlike `readFile`/`writeFile`
     * here, which hand-roll an `errorMessage` — so the status is the only channel there is.
     */
    override suspend fun renameFile(request: RenameFileRequest): Empty =
        withContext(Dispatchers.IO) {
            FilePathPolicy.authorize(request.sourcePath)
            FilePathPolicy.authorize(request.destinationPath)
            logger.info("renameFile: from={}, to={}", request.sourcePath, request.destinationPath)
            val destinationExists = "Destination already exists: ${request.destinationPath}"
            val source = Paths.get(request.sourcePath)
            val dest = Paths.get(request.destinationPath)
            try {
                if (request.overwrite) {
                    moveReplacing(source, dest)
                } else {
                    // The pre-check is kept for the message; the move enforces it again, so the
                    // clobber window shrinks to the JDK's own stat-then-rename. On Windows MoveFile
                    // refuses at the syscall; on POSIX rename(2) still replaces, so this narrows
                    // the race rather than closing it — closing it needs renameat2(RENAME_NOREPLACE),
                    // which the JDK does not expose.
                    if (Files.exists(dest)) {
                        throw status(Status.ALREADY_EXISTS, destinationExists, null)
                    }
                    Files.move(source, dest)
                }
            } catch (e: FileAlreadyExistsException) {
                throw status(Status.ALREADY_EXISTS, destinationExists, e)
            } catch (e: java.nio.file.NoSuchFileException) {
                throw status(Status.NOT_FOUND, "No such file: ${e.file ?: request.sourcePath}", e)
            } catch (e: AccessDeniedException) {
                throw status(Status.PERMISSION_DENIED, "Access denied: ${e.file ?: request.sourcePath}", e)
            } catch (e: IOException) {
                throw status(Status.INTERNAL, "Rename failed: ${e.message ?: e::class.java.simpleName}", e)
            }
            Empty.getDefaultInstance()
        }

    private fun status(
        code: Status,
        description: String,
        cause: Throwable?,
    ) = StatusException(code.withDescription(description).withCause(cause))

    private val watches = FileWatchRegistry()

    override fun watchFileChanges(request: WatchFileChangesRequest): Flow<FileChangeEvent> {
        FilePathPolicy.authorize(request.path)
        return watches.watch(request)
    }
}
