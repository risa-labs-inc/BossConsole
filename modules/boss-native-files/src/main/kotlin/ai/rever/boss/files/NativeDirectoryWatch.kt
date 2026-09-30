package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds

/** Nonrecursive events from the held directory. The directory must outlive this registration. */
interface NativeDirectoryWatch : AutoCloseable {
    fun poll(): DirectoryChanges
}

data class DirectoryChange(
    val name: String,
    val kind: Kind,
) {
    enum class Kind { CREATED, MODIFIED, DELETED }
}

data class DirectoryChanges(
    val events: List<DirectoryChange>,
    val valid: Boolean = true,
)

class DirectoryWatchOverflowException : IOException("File watch overflow; rescan and reconnect")

/** A recursive Linux watch shares one inotify service and worker thread across all registrations. */
class DirectoryWatchSession : AutoCloseable {
    internal val linux = if (Platform.isLinux()) FileSystems.getDefault().newWatchService() else null

    override fun close() {
        linux?.close()
    }
}

internal class LinuxDirectoryWatch(
    descriptor: Int,
    session: DirectoryWatchSession?,
) : NativeDirectoryWatch {
    private val ownsService = session == null
    private val service = session?.linux ?: FileSystems.getDefault().newWatchService()
    private val key = register(descriptor)

    private fun register(descriptor: Int): java.nio.file.WatchKey {
        var registered = false
        try {
            // procfs resolves this descriptor to its held object after ancestor renames.
            return Path
                .of("/proc/self/fd/$descriptor")
                .register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                ).also { registered = true }
        } finally {
            if (!registered && ownsService) service.close()
        }
    }

    override fun poll(): DirectoryChanges {
        // Dequeue signals without consuming another directory's events. Each registration reads its own key.
        while (service.poll() != null) { /* drain the shared signal queue */ }
        val events =
            key.pollEvents().map { event ->
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) throw DirectoryWatchOverflowException()
                val kind =
                    when (event.kind()) {
                        StandardWatchEventKinds.ENTRY_CREATE -> DirectoryChange.Kind.CREATED
                        StandardWatchEventKinds.ENTRY_DELETE -> DirectoryChange.Kind.DELETED
                        else -> DirectoryChange.Kind.MODIFIED
                    }
                DirectoryChange(component(event.context().toString()), kind)
            }
        return DirectoryChanges(events, key.reset())
    }

    override fun close() {
        key.cancel()
        if (ownsService) service.close()
    }
}

/** The macOS JDK also polls directory snapshots. Enumeration here stays relative to the held object. */
internal class SnapshotDirectoryWatch(
    private val directory: NativeDirectory,
) : NativeDirectoryWatch {
    private var previous = snapshot()

    private fun snapshot(): Map<String, FileInfo> {
        val result = mutableMapOf<String, FileInfo>()
        var visited = 0
        directory.entries { name ->
            if (++visited > 10_000) throw DirectoryWatchOverflowException()
            directory.info(name)?.let { result[name] = it }
            true
        }
        return result
    }

    override fun poll(): DirectoryChanges {
        val next = snapshot()
        val events = mutableListOf<DirectoryChange>()
        for ((name, info) in next) {
            val old = previous[name]
            if (old == null) {
                events.add(DirectoryChange(name, DirectoryChange.Kind.CREATED))
            } else if (old != info) {
                events.add(DirectoryChange(name, DirectoryChange.Kind.MODIFIED))
            }
        }
        for (name in previous.keys - next.keys) events.add(DirectoryChange(name, DirectoryChange.Kind.DELETED))
        previous = next
        return DirectoryChanges(events)
    }

    override fun close() = Unit
}

/** Owns an asynchronous relative open and keeps native buffers alive through cancellation completion. */
internal class WindowsDirectoryWatch(
    parent: Pointer,
) : NativeDirectoryWatch {
    private val handle = WindowsOpen.reopen(parent).use { it.open(0x81, 1, 1, asynchronous = true) }
    private val buffer = Memory(64 * 1024L)
    private val overlapped = Memory(32)
    private val transferred = Memory(4)
    private var event: Pointer? = null
    private var closed = false
    private var pending = false

    init {
        var ready = false
        try {
            event = WindowsApi.kernel.getFunction("CreateEventW").invokePointer(arrayOf<Any?>(null, 1, 0, null))
            if (event == null) throw WindowsApi.error("Create watch event")
            arm()
            ready = true
        } finally {
            if (!ready) close()
        }
    }

    private fun arm() {
        overlapped.clear()
        overlapped.setPointer(24, event)
        val filter = 0x1 or 0x2 or 0x4 or 0x8 or 0x10 or 0x40
        val result =
            WindowsApi.kernel.getFunction("ReadDirectoryChangesW").invokeInt(
                arrayOf<Any?>(handle, buffer, buffer.size().toInt(), 0, filter, null, overlapped, null),
            )
        WindowsApi.check(result, "Watch directory")
        pending = true
    }

    override fun poll(): DirectoryChanges {
        check(!closed)
        val result =
            WindowsApi.kernel.getFunction("GetOverlappedResult").invokeInt(
                arrayOf<Any>(handle, overlapped, transferred, 0),
            )
        if (result == 0) return completionFailure(Native.getLastError())
        pending = false
        val events = readEvents(transferred.getInt(0))
        arm()
        return DirectoryChanges(events)
    }

    private fun completionFailure(error: Int): DirectoryChanges {
        if (error == 996) return DirectoryChanges(emptyList()) // ERROR_IO_INCOMPLETE
        pending = false
        return when (error) {
            5, 995 -> DirectoryChanges(emptyList(), false)
            1022 -> throw DirectoryWatchOverflowException()
            else -> throw WindowsApi.error("Watch directory", error)
        }
    }

    private fun readEvents(count: Int): List<DirectoryChange> {
        if (count == 0) throw DirectoryWatchOverflowException()
        require(count in 12..buffer.size().toInt()) { "Invalid watch buffer size" }
        val events = mutableListOf<DirectoryChange>()
        var offset = 0
        while (true) {
            require(offset + 12 <= count) { "Invalid watch record" }
            val next = buffer.getInt(offset.toLong())
            val action = buffer.getInt(offset + 4L)
            val length = buffer.getInt(offset + 8L)
            require(length > 0 && length % 2 == 0 && length <= count - offset - 12) { "Invalid watch name" }
            val name = component(String(buffer.getByteArray(offset + 12L, length), Charsets.UTF_16LE))
            val kind =
                when (action) {
                    1, 5 -> DirectoryChange.Kind.CREATED
                    2, 4 -> DirectoryChange.Kind.DELETED
                    else -> DirectoryChange.Kind.MODIFIED
                }
            events.add(DirectoryChange(name, kind))
            if (next == 0) break
            require(next >= 12 + length && next <= count - offset - 12) { "Invalid watch record offset" }
            offset += next
        }
        return events
    }

    override fun close() {
        if (closed) return
        closed = true
        if (pending) {
            WindowsApi.kernel.getFunction("CancelIoEx").invokeInt(arrayOf<Any>(handle, overlapped))
            WindowsApi.kernel.getFunction("GetOverlappedResult").invokeInt(
                arrayOf<Any>(handle, overlapped, transferred, 1),
            )
        }
        WindowsApi.close(handle)
        event?.let(WindowsApi::close)
        transferred.close()
        overlapped.close()
        buffer.close()
    }
}
