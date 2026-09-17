package ai.rever.boss.files

import com.sun.jna.Native
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal class PosixDirectory private constructor(
    private val descriptor: Int,
) : NativeDirectory {
    private var closed = false
    override val identity: String
        @Synchronized get() = PosixApi.info(handle()).identity

    private fun handle(): Int {
        if (closed) throw ClosedChannelException()
        return descriptor
    }

    @Synchronized
    override fun child(
        name: String,
        create: Boolean,
    ): NativeDirectory {
        component(name)
        if (create) {
            val result = PosixApi.library.getFunction("mkdirat").invokeInt(arrayOf<Any>(handle(), name, 448)) // 0700
            if (result < 0 && Native.getLastError() != 17) throw PosixApi.error("Create directory")
        }
        val flags = PosixApi.directory or PosixApi.noFollow or PosixApi.closeOnExec
        val child = PosixApi.library.getFunction("openat").invokeInt(arrayOf<Any>(handle(), name, flags))
        return PosixDirectory(PosixApi.check(child, "Open directory"))
    }

    @Synchronized
    override fun file(
        name: String,
        create: Boolean,
    ): SeekableByteChannel {
        val creation = if (create) 2 or (if (PosixApi.mac) 0x200 or 0x800 else 0x40 or 0x80) else 0
        val flags = creation or PosixApi.noFollow or PosixApi.closeOnExec or PosixApi.nonBlocking
        val arguments = arrayOf<Any>(handle(), component(name), flags, 384)
        // openat has three fixed arguments; Darwin ARM64 passes the variadic mode on the stack.
        val opened = PosixApi.library.getFunction("openat", 3 shl 7).invokeInt(arguments)
        return PosixFile(PosixApi.check(opened, "Open file"), writable = create)
    }

    @Synchronized
    override fun info(name: String): FileInfo? =
        try {
            PosixApi.info(handle(), component(name))
        } catch (_: NoSuchFileException) {
            null
        }

    @Synchronized
    override fun delete(
        name: String,
        directory: Boolean,
    ) {
        val flag = if (directory) (if (PosixApi.mac) 0x80 else 0x200) else 0
        val result = PosixApi.library.getFunction("unlinkat").invokeInt(arrayOf<Any>(handle(), component(name), flag))
        PosixApi.check(result, "Delete")
    }

    override fun move(
        source: String,
        destination: NativeDirectory,
        name: String,
        overwrite: Boolean,
    ) {
        require(destination is PosixDirectory) { "Incompatible filesystem provider" }
        // The caller retains both handles for the operation and serializes close against use.
        val function =
            if (overwrite) {
                "renameat"
            } else if (PosixApi.mac) {
                "renameatx_np"
            } else {
                "renameat2"
            }
        val arguments = arrayOf<Any>(handle(), component(source), destination.handle(), component(name))
        val flags = if (PosixApi.mac) 4 else 1 // RENAME_EXCL / RENAME_NOREPLACE
        val result = PosixApi.library.getFunction(function).invokeInt(if (overwrite) arguments else arguments + flags)
        PosixApi.check(result, "Move")
    }

    @Synchronized
    override fun entries(visit: (String) -> Boolean) {
        // A separate open file description gives each enumeration its own directory offset.
        val flags = PosixApi.directory or PosixApi.closeOnExec
        val opened = PosixApi.library.getFunction("openat").invokeInt(arrayOf<Any>(handle(), ".", flags))
        val copy = PosixApi.check(opened, "List")
        val stream = PosixApi.library.getFunction("fdopendir").invokePointer(arrayOf<Any>(copy))
        if (stream == null) {
            val error = PosixApi.error("List")
            PosixApi.library.getFunction("close").invokeInt(arrayOf<Any>(copy))
            throw error
        }
        try {
            val readdir = PosixApi.library.getFunction("readdir${PosixApi.inodeSuffix}")
            var proceed = true
            while (proceed) {
                Native.setLastError(0)
                val entry = readdir.invokePointer(arrayOf<Any>(stream))
                if (entry == null && Native.getLastError() != 0) throw PosixApi.error("List")
                if (entry == null) break
                val name = entry.getString(if (PosixApi.mac) 21 else 19, "UTF-8")
                if (name != "." && name != "..") proceed = visit(name)
            }
        } finally {
            PosixApi.library.getFunction("closedir").invokeInt(arrayOf<Any>(stream))
        }
    }

    @Synchronized
    override fun restrictToOwner() {
        PosixPermissions.restrictDirectory(handle())
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            PosixApi.library.getFunction("close").invokeInt(arrayOf<Any>(descriptor))
        }
    }

    companion object {
        fun openRoot(root: Path): NativeDirectory {
            val flags = PosixApi.directory or PosixApi.noFollow or PosixApi.closeOnExec
            val descriptor = PosixApi.library.getFunction("open").invokeInt(arrayOf<Any>(root.toString(), flags))
            return PosixDirectory(PosixApi.check(descriptor, "Open filesystem root"))
        }
    }
}
