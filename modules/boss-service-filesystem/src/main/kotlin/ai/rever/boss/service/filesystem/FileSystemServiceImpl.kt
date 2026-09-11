package ai.rever.boss.service.filesystem

import ai.rever.boss.files.CreationPermissions
import ai.rever.boss.files.CrossDeviceMoveException
import ai.rever.boss.files.NativeDirectory
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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Filesystem RPCs operate only through owned handles after canonical-path authorization. */
class FileSystemServiceImpl internal constructor(
    private val access: FileAccess,
) : FileSystemServiceGrpcKt.FileSystemServiceCoroutineImplBase() {
    constructor() : this(FileAccess())

    override suspend fun scanDirectory(request: ScanDirectoryRequest): ScanDirectoryResponse =
        withContext(Dispatchers.IO) {
            access.policy.validate(request.path)
            try {
                access.directory(request.path).use { root ->
                    val entries = BoundedDirectoryScan(request, currentCoroutineContext(), access.policy).scan(root)
                    ScanDirectoryResponse.newBuilder().addAllEntries(entries).build()
                }
            } catch (_: NoSuchFileException) {
                ScanDirectoryResponse.newBuilder().setErrorMessage("Directory not found: ${request.path}").build()
            } catch (_: NotDirectoryException) {
                ScanDirectoryResponse.newBuilder().setErrorMessage("Directory not found: ${request.path}").build()
            }
        }

    @Suppress("TooGenericExceptionCaught") // Preserve the RPC response error contract for file/read failures.
    override suspend fun readFile(request: ReadFileRequest): ReadFileResponse =
        withContext(Dispatchers.IO) {
            access.policy.validate(request.path)
            try {
                require(request.offsetBytes >= 0 && request.maxBytes >= 0) {
                    "Read offsets and limits must be nonnegative"
                }
                val maximum = readLimit(request)
                access.entry(request.path).use { entry ->
                    entry.parent.file(entry.name).use { reader ->
                        val total = reader.size()
                        val buffer = ByteBuffer.allocate(maximum + 1)
                        if (request.offsetBytes < total) {
                            reader.position(request.offsetBytes)
                            while (buffer.hasRemaining()) {
                                currentCoroutineContext().ensureActive()
                                if (reader.read(buffer) < 0) break
                            }
                        }
                        val truncated = buffer.position() > maximum
                        if (truncated && request.maxBytes == 0L) {
                            throw fileSystemLimit(
                                "File exceeds one response; read it using explicit byte limits and offsets",
                            )
                        }
                        ReadFileResponse
                            .newBuilder()
                            .setContent(ByteString.copyFrom(buffer.array(), 0, buffer.position().coerceAtMost(maximum)))
                            .setTotalSizeBytes(total)
                            .setTruncated(truncated)
                            .build()
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: FilePathDeniedException) {
                throw failure
            } catch (failure: Exception) {
                ReadFileResponse.newBuilder().setErrorMessage(failure.message ?: "Read failed").build()
            }
        }

    private fun readLimit(request: ReadFileRequest): Int =
        request.maxBytes
            .takeIf { it > 0 }
            ?.coerceAtMost(FileSystemLimits.READ_BYTES.toLong())
            ?.toInt() ?: FileSystemLimits.READ_BYTES

    @Suppress("TooGenericExceptionCaught") // Preserve the RPC response error contract for file/write failures.
    override suspend fun writeFile(request: WriteFileRequest): WriteFileResponse =
        withContext(Dispatchers.IO) {
            access.policy.validate(request.path)
            try {
                access.entry(request.path, request.createParents, followLeaf = true).use { entry ->
                    val writer =
                        try {
                            entry.parent.file(
                                entry.name,
                                create = true,
                                readable = false,
                                permissions = CreationPermissions.INHERIT,
                            )
                        } catch (failure: FileAlreadyExistsException) {
                            if (!request.overwrite) throw failure
                            entry.parent.file(
                                entry.name,
                                writable = true,
                                readable = false,
                                permissions = CreationPermissions.INHERIT,
                            )
                        }
                    writer.use {
                        it.truncate(0)
                        val bytes = request.content.asReadOnlyByteBuffer()
                        while (bytes.hasRemaining()) {
                            currentCoroutineContext().ensureActive()
                            it.write(bytes)
                        }
                    }
                    WriteFileResponse
                        .newBuilder()
                        .setSuccess(true)
                        .setBytesWritten(request.content.size().toLong())
                        .build()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: FilePathDeniedException) {
                throw failure
            } catch (failure: Exception) {
                WriteFileResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(failure.message ?: "Write failed")
                    .build()
            }
        }

    override suspend fun createFile(request: CreateFileRequest): Empty =
        withContext(Dispatchers.IO) {
            access.entry(request.path, request.createParents || request.isDirectory).use { entry ->
                // Preserve createFile's existing idempotence when the entry already exists.
                if (entry.parent.info(entry.name) == null) {
                    try {
                        if (request.isDirectory) {
                            entry.parent
                                .child(entry.name, create = true, permissions = CreationPermissions.INHERIT)
                                .close()
                        } else {
                            entry.parent
                                .file(entry.name, create = true, permissions = CreationPermissions.INHERIT)
                                .close()
                        }
                    } catch (_: FileAlreadyExistsException) {
                        // Another creator won the exclusive create. Its entry is left intact.
                    }
                }
            }
            Empty.getDefaultInstance()
        }

    override suspend fun deleteFile(request: DeleteFileRequest): Empty =
        withContext(Dispatchers.IO) {
            access.policy.validate(request.path)
            try {
                access.entry(request.path).use { entry ->
                    access.policy.authorizeMutation(entry.canonical)
                    delete(entry.parent, entry.name, entry.canonical, request.recursive, currentCoroutineContext())
                }
            } catch (failure: IOException) {
                throw fileStatus(failure, "Delete", request.path)
            }
            Empty.getDefaultInstance()
        }

    private fun delete(
        parent: NativeDirectory,
        name: String,
        path: Path,
        recursive: Boolean,
        context: CoroutineContext,
    ) {
        context.ensureActive()
        access.policy.authorize(path)
        val info = parent.info(name) ?: throw NoSuchFileException(path.toString())
        val directory = info.isDirectory && !info.isLink
        if (recursive && directory) {
            parent.child(name).use { child ->
                child.entries { descendant ->
                    delete(child, descendant, path.resolve(descendant), true, context)
                    true
                }
            }
        }
        // unlinkat / disposition operates on the entry, including a final symlink, without traversal.
        parent.delete(name, directory)
    }

    override suspend fun renameFile(request: RenameFileRequest): Empty =
        withContext(Dispatchers.IO) {
            access.policy.validate(request.sourcePath)
            access.policy.validate(request.destinationPath)
            try {
                access.entry(request.sourcePath).use { source ->
                    access.entry(request.destinationPath).use { destination ->
                        access.policy.authorizeMutation(source.canonical)
                        access.policy.authorizeMutation(destination.canonical)
                        try {
                            source.parent.move(source.name, destination.parent, destination.name, request.overwrite)
                        } catch (_: CrossDeviceMoveException) {
                            moveAcrossVolumes(source, destination, request.overwrite)
                        }
                    }
                }
            } catch (failure: FileAlreadyExistsException) {
                throw fileStatus(failure, "Rename", request.destinationPath)
            } catch (failure: IOException) {
                throw fileStatus(failure, "Rename", request.sourcePath)
            }
            Empty.getDefaultInstance()
        }

    private suspend fun moveAcrossVolumes(
        source: FileEntryHandle,
        destination: FileEntryHandle,
        overwrite: Boolean,
    ) {
        val info = source.parent.info(source.name) ?: throw NoSuchFileException(source.visible.toString())
        val temporary = ".boss-move-${UUID.randomUUID()}"
        var installed = false
        try {
            source.parent.copyEntry(source.name, destination.parent, temporary)
            currentCoroutineContext().ensureActive()
            check(source.parent.info(source.name)?.identity == info.identity) {
                "Source changed during cross-volume move"
            }
            destination.parent.move(temporary, destination.parent, destination.name, overwrite)
            installed = true
            source.parent.delete(source.name, info.isDirectory && !info.isLink)
        } finally {
            if (!installed) {
                destination.parent.info(temporary)?.let {
                    destination.parent.delete(temporary, it.isDirectory && !it.isLink)
                }
            }
        }
    }

    private val watches = FileWatchRegistry(access)

    override fun watchFileChanges(request: WatchFileChangesRequest): Flow<FileChangeEvent> = watches.watch(request)
}

private fun fileStatus(
    failure: IOException,
    operation: String,
    path: String,
): StatusException {
    val code =
        when (failure) {
            is FileAlreadyExistsException -> Status.ALREADY_EXISTS
            is NoSuchFileException -> Status.NOT_FOUND
            is AccessDeniedException -> Status.PERMISSION_DENIED
            else -> Status.INTERNAL
        }
    return StatusException(code.withDescription("$operation failed: $path (${failure.message})").withCause(failure))
}
