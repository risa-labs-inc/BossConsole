package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.FileChangeEvent
import ai.rever.boss.ipc.proto.services.FileChangeType
import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Every collector owns its registrations until cancellation, failure, or deletion of its root. */
internal class FileWatchRegistry {
    private val slots = Semaphore(32)

    fun watch(request: WatchFileChangesRequest): Flow<FileChangeEvent> =
        flow {
            if (!slots.tryAcquire()) throw fileSystemLimit("Too many active file watches")
            try {
                val root = Path.of(request.path).toAbsolutePath().normalize()
                require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Watch root must be a directory" }
                FileSystems.getDefault().newWatchService().use { service ->
                    val registrations = Registrations(service, root, currentCoroutineContext())
                    registrations.add(root, request.recursive)
                    var active = true
                    while (active) {
                        currentCoroutineContext().ensureActive()
                        val key = service.poll(500, TimeUnit.MILLISECONDS)
                        if (key != null) {
                            emitChanges(key, request.recursive, registrations)
                            if (!key.reset()) {
                                registrations.remove(key)
                                active = key.watchable() != root
                            }
                        }
                    }
                }
            } finally {
                slots.release()
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<FileChangeEvent>.emitChanges(
        key: WatchKey,
        recursive: Boolean,
        registrations: Registrations,
    ) {
        val directory = key.watchable() as Path
        for (event in key.pollEvents()) {
            currentCoroutineContext().ensureActive()
            if (event.kind() == OVERFLOW) throw fileSystemLimit("File watch overflow; rescan and reconnect")
            val relative = event.context() as? Path ?: continue
            val path = directory.resolve(relative)
            if (recursive && event.kind() == ENTRY_CREATE && Files.isDirectory(path, NOFOLLOW_LINKS)) {
                registrations.add(path, true)
            }
            val type =
                when (event.kind()) {
                    ENTRY_CREATE -> FileChangeType.FILE_CHANGE_TYPE_CREATED
                    ENTRY_MODIFY -> FileChangeType.FILE_CHANGE_TYPE_MODIFIED
                    ENTRY_DELETE -> FileChangeType.FILE_CHANGE_TYPE_DELETED
                    else -> FileChangeType.FILE_CHANGE_TYPE_UNSPECIFIED
                }
            emit(
                FileChangeEvent
                    .newBuilder()
                    .setPath(path.toString())
                    .setChangeType(type)
                    .setTimestamp(System.currentTimeMillis())
                    .build(),
            )
        }
    }

    private class Registrations(
        private val service: WatchService,
        private val root: Path,
        private val context: CoroutineContext,
    ) {
        private val keys = mutableSetOf<WatchKey>()

        fun remove(key: WatchKey) {
            keys.remove(key)
        }

        fun add(
            path: Path,
            recursive: Boolean,
        ) {
            var visited = 0
            Files.walkFileTree(
                path,
                emptySet(),
                if (recursive) FileSystemLimits.SCAN_DEPTH + 1 else 0,
                object : SimpleFileVisitor<Path>() {
                    private fun visit(
                        entry: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        context.ensureActive()
                        enforceFileSystemLimit(
                            ++visited <= FileSystemLimits.SCAN_ENTRIES,
                            "File watch scan limit reached",
                        )
                        if (attrs.isDirectory) {
                            enforceFileSystemLimit(
                                root.relativize(entry).nameCount <= FileSystemLimits.SCAN_DEPTH,
                                "File watch depth limit reached",
                            )
                            keys.removeAll { !it.isValid }
                            enforceFileSystemLimit(keys.size < 1024, "File watch directory limit reached")
                            try {
                                keys.add(entry.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE))
                            } catch (e: NoSuchFileException) {
                                if (entry == root) throw e
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        context.ensureActive()
                        if (exc is NoSuchFileException && file != root) return FileVisitResult.CONTINUE
                        throw exc
                    }

                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ) = visit(dir, attrs)

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ) = visit(file, attrs)
                },
            )
        }
    }
}
