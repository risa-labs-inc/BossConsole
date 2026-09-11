package ai.rever.boss.service.filesystem

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Checks the opened object, never a pathname checked before a potentially blocking open. */
internal interface RegularFileReader : AutoCloseable {
    val size: Long

    fun read(
        offset: Long,
        destination: ByteArray,
        count: Int,
    ): Int
}

internal fun openRegularFile(path: Path): RegularFileReader {
    check(Native.POINTER_SIZE == 8) { "Regular-file reads require a supported 64-bit platform" }
    return when {
        Platform.isWindows() -> {
            WindowsRegularFile(path)
        }

        Platform.isMac() || (Platform.isLinux() && Platform.ARCH in setOf("x86-64", "aarch64")) -> {
            PosixRegularFile(path)
        }

        else -> {
            throw IOException("Regular-file reads are unavailable on this platform")
        }
    }
}

internal suspend fun RegularFileReader.readPage(
    offset: Long,
    maximum: Int,
): ByteArray {
    if (offset >= size) return byteArrayOf()
    val buffer = ByteArray(minOf(65_536, maximum))
    val output = ByteArrayOutputStream()
    val limit = minOf(maximum.toLong(), Long.MAX_VALUE - offset).toInt()
    while (output.size() < limit) {
        currentCoroutineContext().ensureActive()
        val position = offset + output.size()
        val count = read(position, buffer, minOf(buffer.size, limit - output.size()))
        if (count == 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private class PosixRegularFile(
    path: Path,
) : RegularFileReader {
    private val descriptor: Int
    override val size: Long

    init {
        // O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC. Values from Darwin fcntl.h
        // and Linux asm-generic/fcntl.h (only the supported 64-bit ABIs above).
        val flags = if (Platform.isMac()) 0x0004 or 0x0100 or 0x01000000 else 0x0800 or 0x20000 or 0x80000
        descriptor = libc.getFunction("open").invokeInt(arrayOf<Any>(path.toString(), flags))
        if (descriptor < 0) throw nativeReadError("Open")
        var initialized = false
        try {
            // /dev/fd resolves this process's still-open descriptor on Darwin and Linux.
            // Failure to inspect it is fatal; never fall back to checking the original path.
            val attributes = Files.readAttributes(Path.of("/dev/fd/$descriptor"), BasicFileAttributes::class.java)
            require(attributes.isRegularFile) { "Only regular files can be read" }
            size = attributes.size()
            initialized = true
        } finally {
            if (!initialized) close()
        }
    }

    override fun read(
        offset: Long,
        destination: ByteArray,
        count: Int,
    ): Int {
        val result = libc.getFunction("pread").invokeLong(arrayOf(descriptor, destination, count.toLong(), offset))
        if (result < 0) throw nativeReadError("Read")
        return result.toInt()
    }

    override fun close() {
        libc.getFunction("close").invokeInt(arrayOf(descriptor))
    }

    companion object {
        private val libc =
            NativeLibrary.getInstance(Platform.C_LIBRARY_NAME, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }
}

private class WindowsRegularFile(
    path: Path,
) : RegularFileReader {
    private val handle: Pointer
    override val size: Long

    init {
        // OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, non-inheritable handle, shared read/write/delete.
        val opened =
            kernel.getFunction("CreateFileW", Function.ALT_CONVENTION).invokePointer(
                arrayOf(WString(path.toAbsolutePath().toString()), 0x80000000.toInt(), 7, null, 3, 0x00200000, null),
            )
        if (opened == null || Pointer.nativeValue(opened) == -1L) throw nativeReadError("Open")
        handle = opened
        var initialized = false
        try {
            require(kernel.getFunction("GetFileType").invokeInt(arrayOf(handle)) == 1) { "Only disk files can be read" }
            // BY_HANDLE_FILE_INFORMATION has thirteen DWORDs, including three FILETIMEs.
            Memory(52).use { info ->
                if (kernel.getFunction("GetFileInformationByHandle").invokeInt(arrayOf(handle, info)) == 0) {
                    throw nativeReadError("Inspect")
                }
                require(info.getInt(0) and (0x10 or 0x400) == 0) { "Directories and reparse points cannot be read" }
                size = (info.getInt(32).toLong() shl 32) or (info.getInt(36).toLong() and 0xffffffffL)
                require(size >= 0) { "File exceeds supported size" }
                initialized = true
            }
        } finally {
            if (!initialized) close()
        }
    }

    override fun read(
        offset: Long,
        destination: ByteArray,
        count: Int,
    ): Int {
        if (kernel.getFunction("SetFilePointerEx").invokeInt(arrayOf(handle, offset, null, 0)) == 0) {
            throw nativeReadError("Seek")
        }
        Memory(4).use { bytesRead ->
            if (kernel.getFunction("ReadFile").invokeInt(arrayOf(handle, destination, count, bytesRead, null)) == 0) {
                throw nativeReadError("Read")
            }
            return bytesRead.getInt(0)
        }
    }

    override fun close() {
        kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle))
    }

    companion object {
        private val kernel = NativeLibrary.getInstance("kernel32")
    }
}

private fun nativeReadError(operation: String) = IOException("$operation failed (OS error ${Native.getLastError()})")
