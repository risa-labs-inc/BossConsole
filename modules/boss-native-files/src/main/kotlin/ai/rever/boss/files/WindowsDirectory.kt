package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path

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
    ): NativeDirectory {
        val access = 0x81 // FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES
        val opened =
            if (create) {
                WindowsSecurity.privateDescriptor(handle()) { descriptor ->
                    WindowsOpen(handle(), name, descriptor).use { it.open(access, 3, 1) }
                }
            } else {
                WindowsOpen(handle(), name, null).use { it.open(access, 1, 1) }
            }
        return WindowsDirectory(opened)
    }

    @Synchronized
    override fun file(
        name: String,
        create: Boolean,
    ): SeekableByteChannel {
        val opened =
            if (create) {
                WindowsSecurity.privateDescriptor(handle()) { descriptor ->
                    WindowsOpen(handle(), name, descriptor).use { it.open(0xc0000000.toInt(), 2, 0x40) }
                }
            } else {
                WindowsOpen(handle(), name, null).use { it.open(0x80000000.toInt(), 1, 0x40) }
            }
        return WindowsFile(opened, create)
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
                WindowsRename.move(entry, destination.handle(), component(name), overwrite)
            } finally {
                WindowsApi.close(entry)
            }
        }
    }

    @Synchronized
    override fun entries(visit: (String) -> Boolean) = WindowsEntries.visit(handle(), visit)

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
            val arguments = arrayOf<Any?>(WString(root.toString()), 0x100081, 7, null, 3, 0x02200000, null)
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
