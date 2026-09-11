package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path

@Suppress("TooManyFunctions") // Implements the complete native directory operation contract.
internal class WindowsDirectory(
    private val pointer: Pointer,
) : NativeDirectory {
    private var closed = false
    override val identity: String
        @Synchronized get() = WindowsApi.info(handle()).identity

    private fun handle(): Pointer {
        if (closed) throw ClosedChannelException()
        return pointer
    }

    @Synchronized
    override fun child(
        name: String,
        create: Boolean,
        permissions: CreationPermissions,
    ): NativeDirectory {
        val access = 0xa0 // FILE_TRAVERSE | FILE_READ_ATTRIBUTES; listing is acquired only for enumeration.
        val opened =
            if (create && permissions == CreationPermissions.OWNER_ONLY) {
                WindowsSecurity.privateDescriptor(handle()) { descriptor ->
                    WindowsOpen(handle(), name, descriptor).use { it.open(access, 3, 1) }
                }
            } else {
                WindowsOpen(handle(), name, null).use { it.open(access, if (create) 3 else 1, 1) }
            }
        return WindowsDirectory(opened)
    }

    @Synchronized
    override fun file(
        name: String,
        create: Boolean,
        writable: Boolean,
        readable: Boolean,
        permissions: CreationPermissions,
    ): SeekableByteChannel {
        require(readable || writable) { "A file must be opened for reading or writing" }
        val access = (if (readable) 0x80000000.toInt() else 0) or (if (writable) 0x40000000 else 0)
        val disposition = if (create) 2 else 1
        val opened =
            if (create && permissions == CreationPermissions.OWNER_ONLY) {
                WindowsSecurity.privateDescriptor(handle()) { descriptor ->
                    WindowsOpen(handle(), name, descriptor).use { it.open(access, 2, 0x40) }
                }
            } else {
                WindowsOpen(handle(), name, null).use { it.open(access, disposition, 0x40) }
            }
        return WindowsFile(
            opened,
            writable,
            verifyPrivate = writable && permissions == CreationPermissions.OWNER_ONLY,
            readable = readable,
        )
    }

    @Synchronized
    override fun info(name: String): FileInfo? =
        try {
            WindowsOpen(handle(), name, null).use { request ->
                val entry = request.open(0x80, 1, 0x200000)
                try {
                    WindowsApi.info(entry)
                } finally {
                    WindowsApi.close(entry)
                }
            }
        } catch (_: NoSuchFileException) {
            null
        }

    @Synchronized
    override fun delete(
        name: String,
        directory: Boolean,
    ) {
        WindowsOpen(handle(), name, null).use { request ->
            val entry = request.open(0x10080, 1, 0x200000)
            try {
                val info = WindowsApi.info(entry)
                require(info.isLink || info.isDirectory == directory) { "File type changed before deletion" }
                Memory(4).use { disposition ->
                    disposition.setInt(0, 1)
                    val arguments = arrayOf<Any>(entry, 4, disposition, 4)
                    val result = WindowsApi.kernel.getFunction("SetFileInformationByHandle").invokeInt(arguments)
                    WindowsApi.check(result, "Delete")
                }
            } finally {
                WindowsApi.close(entry)
            }
        }
    }

    override fun move(
        source: String,
        destination: NativeDirectory,
        name: String,
        overwrite: Boolean,
    ) {
        require(destination is WindowsDirectory) { "Incompatible filesystem provider" }
        WindowsOpen(handle(), source, null).use { request ->
            val entry = request.open(0x10080, 1, 0x200000)
            try {
                if (overwrite) destination.prepareReplacement(entry, name)
                WindowsRename.move(entry, destination.handle(), component(name), overwrite)
            } finally {
                WindowsApi.close(entry)
            }
        }
    }

    private fun prepareReplacement(
        source: Pointer,
        name: String,
    ) {
        val target = info(name) ?: return
        if (!target.isLink || !target.isDirectory || target.identity == WindowsApi.info(source).identity) return
        // Windows cannot replace a directory reparse entry through FILE_RENAME_INFORMATION.
        // Delete only the entry; the type check rejects a concurrent replacement by a real directory.
        delete(name)
    }

    override fun copyEntry(
        source: String,
        destination: NativeDirectory,
        name: String,
    ) {
        require(destination is WindowsDirectory) { "Incompatible filesystem provider" }
        WindowsCopy.copy(handle(), component(source), destination.handle(), component(name))
    }

    @Synchronized
    override fun entries(visit: (String) -> Boolean) {
        WindowsOpen.reopen(handle()).use { request ->
            val listable = request.open(0x81, 1, 1)
            try {
                WindowsEntries.visit(listable, visit)
            } finally {
                WindowsApi.close(listable)
            }
        }
    }

    @Synchronized
    override fun watch(session: DirectoryWatchSession?): NativeDirectoryWatch = WindowsDirectoryWatch(handle())

    @Synchronized
    override fun restrictToOwner() {
        WindowsOpen.reopen(handle()).use { request ->
            val writable = request.open(0x60080, 1, 0x200001)
            try {
                WindowsSecurity.restrict(writable)
            } finally {
                WindowsApi.close(writable)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            WindowsApi.close(pointer)
        }
    }

    companion object {
        fun openRoot(root: Path): NativeDirectory {
            // Only a drive root or UNC share is opened by name. Descendants always use NtCreateFile relative handles.
            val arguments = arrayOf<Any?>(WString(root.toString()), 0x1000a0, 7, null, 3, 0x02200000, null)
            val opened = WindowsApi.kernel.getFunction("CreateFileW").invokePointer(arguments)
            if (opened == null || Pointer.nativeValue(opened) == -1L) throw WindowsApi.error("Open filesystem root")
            var valid = false
            try {
                val info = WindowsApi.info(opened)
                require(info.isDirectory && !info.isLink) { "Filesystem root must be a directory" }
                valid = true
                return WindowsDirectory(opened)
            } finally {
                if (!valid) WindowsApi.close(opened)
            }
        }
    }
}

internal object WindowsRename {
    fun move(
        source: Pointer,
        destination: Pointer,
        name: String,
        overwrite: Boolean,
    ) {
        val bytes = name.toByteArray(Charsets.UTF_16LE)
        Memory(maxOf(24L, 20L + bytes.size)).use { info ->
            info.clear()
            info.setByte(0, if (overwrite) 1 else 0)
            info.setPointer(8, destination)
            info.setInt(16, bytes.size)
            info.write(20, bytes, 0, bytes.size)
            Memory(16).use { status ->
                val arguments = arrayOf<Any>(source, status, info, info.size().toInt(), 10)
                WindowsApi.checkStatus(
                    WindowsApi.nt.getFunction("NtSetInformationFile").invokeInt(arguments),
                    "Move",
                )
            }
        }
    }
}
