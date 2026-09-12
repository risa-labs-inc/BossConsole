package ai.rever.boss.service.filesystem

import ai.rever.boss.files.DirectoryChange
import ai.rever.boss.files.DirectoryWatchOverflowException
import ai.rever.boss.files.DirectoryWatchSession
import ai.rever.boss.files.NativeDirectory
import ai.rever.boss.ipc.proto.services.FileChangeEvent
import ai.rever.boss.ipc.proto.services.FileChangeType
import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.Semaphore
import kotlin.coroutines.CoroutineContext

/** Each cold collector owns authorized handles and registrations until cancellation or root removal. */
internal class FileWatchRegistry(
    private val access: FileAccess,
) {
    private val slots = Semaphore(32)

    fun watch(request: WatchFileChangesRequest): Flow<FileChangeEvent> =
        flow {
            if (!slots.tryAcquire()) throw fileSystemLimit("Too many active file watches")
            try {
                access.directory(request.path, followLeaf = false).use { root ->
                    Registrations(root, access.policy, currentCoroutineContext()).use { registrations ->
                        registrations.start(request.recursive)
                        while (registrations.active) {
                            currentCoroutineContext().ensureActive()
                            registrations.poll(request.recursive) { path, kind ->
                                val type =
                                    when (kind) {
                                        DirectoryChange.Kind.CREATED -> FileChangeType.FILE_CHANGE_TYPE_CREATED
                                        DirectoryChange.Kind.MODIFIED -> FileChangeType.FILE_CHANGE_TYPE_MODIFIED
                                        DirectoryChange.Kind.DELETED -> FileChangeType.FILE_CHANGE_TYPE_DELETED
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
                            delay(250)
                        }
                    }
                }
            } catch (_: DirectoryWatchOverflowException) {
                throw fileSystemLimit("File watch overflow; rescan and reconnect")
            } finally {
                slots.release()
            }
        }.flowOn(Dispatchers.IO)
}

private data class ParentEntry(
    val directory: NativeDirectory,
    val name: String,
)

private class Registration(
    val directory: NativeDirectory,
    val canonical: Path,
    val visible: Path,
    val parent: ParentEntry?,
    val depth: Int,
    session: DirectoryWatchSession,
) {
    private val identity = directory.identity
    val watch = directory.watch(session)

    fun present(): Boolean =
        parent?.let {
            val current = it.directory.info(it.name)
            current != null && !current.isLink && current.identity == identity
        } ?: true
}

private class WatchScanWork(
    var visited: Int = 0,
)

private class Registrations(
    private val root: FileDirectoryHandle,
    private val policy: FilePathPolicy,
    private val context: CoroutineContext,
) : AutoCloseable {
    private val entries = mutableListOf<Registration>()
    private val session = DirectoryWatchSession()
    val active: Boolean get() = entries.any { it.directory === root.directory }

    fun start(recursive: Boolean) {
        val parent = root.entry?.let { ParentEntry(it.parent, it.name) }
        val first = register(root.directory, root.canonical, root.visible, parent, 0)
        if (recursive) scan(first, WatchScanWork())
    }

    suspend fun poll(
        recursive: Boolean,
        emit: suspend (Path, DirectoryChange.Kind) -> Unit,
    ) {
        for (registration in entries.toList()) {
            if (registration in entries) {
                if (registration.present()) {
                    pollDirectory(registration, recursive, emit)
                } else {
                    remove(registration)
                }
            }
        }
    }

    private suspend fun pollDirectory(
        registration: Registration,
        recursive: Boolean,
        emit: suspend (Path, DirectoryChange.Kind) -> Unit,
    ) {
        // A parent's removal can have removed this record since the loop snapshot was made.
        if (registration !in entries) return
        val batch = registration.watch.poll()
        for (event in batch.events) {
            context.ensureActive()
            if (policy.allowed(registration.canonical.resolve(event.name))) {
                if (recursive && event.kind == DirectoryChange.Kind.CREATED) {
                    addChild(registration, event.name, WatchScanWork())
                }
                emit(registration.visible.resolve(event.name), event.kind)
            }
        }
        if (!batch.valid) remove(registration)
    }

    private fun register(
        directory: NativeDirectory,
        canonical: Path,
        visible: Path,
        parent: ParentEntry?,
        depth: Int,
    ): Registration {
        context.ensureActive()
        enforceFileSystemLimit(depth <= FileSystemLimits.SCAN_DEPTH, "File watch depth limit reached")
        enforceFileSystemLimit(entries.size < 1024, "File watch directory limit reached")
        return Registration(directory, canonical, visible, parent, depth, session).also(entries::add)
    }

    private fun scan(
        parent: Registration,
        work: WatchScanWork,
    ) {
        parent.directory.entries { name ->
            context.ensureActive()
            enforceFileSystemLimit(++work.visited <= FileSystemLimits.SCAN_ENTRIES, "File watch scan limit reached")
            addChild(parent, name, work)
            true
        }
    }

    private fun addChild(
        parent: Registration,
        name: String,
        work: WatchScanWork,
    ) {
        val canonical = parent.canonical.resolve(name)
        val info = if (policy.allowed(canonical)) parent.directory.info(name) else null
        if (info == null || !info.isDirectory || info.isLink) return
        val existing = entries.firstOrNull { it.canonical == canonical }
        if (existing?.present() == true) return
        if (existing != null) remove(existing)
        try {
            val child = parent.directory.child(name)
            var registered = false
            try {
                val registration =
                    register(
                        child,
                        canonical,
                        parent.visible.resolve(name),
                        ParentEntry(parent.directory, name),
                        parent.depth + 1,
                    )
                registered = true
                scan(registration, work)
            } finally {
                if (!registered) child.close()
            }
        } catch (failure: IOException) {
            if (!disappeared(parent.directory, name, failure)) throw failure
        }
    }

    private fun disappeared(
        parent: NativeDirectory,
        name: String,
        failure: IOException,
    ): Boolean {
        if (failure is NoSuchFileException) return true
        var absent = false
        if (System.getProperty("os.name").startsWith("Windows")) {
            repeat(5) {
                context.ensureActive()
                if (!absent) {
                    Thread.sleep(10)
                    try {
                        absent = parent.info(name) == null
                    } catch (_: IOException) {
                        // Pending deletion can deny opens; only proven absence permits suppression.
                    }
                }
            }
        }
        return absent
    }

    private fun remove(registration: Registration) {
        val removed = entries.filter { it.canonical.startsWith(registration.canonical) }.asReversed()
        for (item in removed) {
            entries.remove(item)
            try {
                item.watch.close()
            } finally {
                if (item.directory !== root.directory) item.directory.close()
            }
        }
    }

    override fun close() {
        try {
            entries.firstOrNull()?.let(::remove)
        } finally {
            session.close()
        }
    }
}
