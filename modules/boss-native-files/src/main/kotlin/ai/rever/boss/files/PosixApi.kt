package ai.rever.boss.files

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

internal object PosixApi {
    val mac = Platform.isMac()
    val inodeSuffix = if (mac && Platform.ARCH == "x86-64") "\$INODE64" else ""
    val library = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    val noFollow = if (mac) 0x100 else 0x20000
    val closeOnExec = if (mac) 0x01000000 else 0x80000
    val directory = if (mac) 0x00100000 else 0x10000
    val nonBlocking = if (mac) 4 else 0x800

    fun error(
        operation: String,
        code: Int = Native.getLastError(),
    ): IOException =
        when (code) {
            2 -> NoSuchFileException(operation)
            13, 1 -> AccessDeniedException(operation)
            17 -> FileAlreadyExistsException(operation)
            else -> IOException("$operation failed (OS error $code)")
        }

    fun check(
        result: Int,
        operation: String,
    ): Int {
        if (result < 0) throw error(operation)
        return result
    }

    fun info(
        descriptor: Int,
        name: String? = null,
    ): FileInfo =
        Memory(256).use { memory ->
            // Darwin's stat64 ABI and Linux's 64-bit x86 / asm-generic stat ABIs.
            // Oversized zeroed storage accommodates the entire structure, not only the fields used here.
            memory.clear()
            val suffix = inodeSuffix
            val result =
                if (name == null) {
                    library.getFunction("fstat$suffix").invokeInt(arrayOf<Any>(descriptor, memory))
                } else {
                    val flags = if (mac) 0x20 else 0x100 // AT_SYMLINK_NOFOLLOW
                    val arguments = arrayOf<Any>(descriptor, component(name), memory, flags)
                    library.getFunction("fstatat$suffix").invokeInt(arguments)
                }
            check(result, "Inspect file")
            val modeOffset =
                if (mac) {
                    4L
                } else if (Platform.ARCH == "x86-64") {
                    24L
                } else {
                    16L
                }
            val mode = if (mac) memory.getShort(modeOffset).toInt() and 0xffff else memory.getInt(modeOffset)
            val device = if (mac) memory.getInt(0).toLong() and 0xffffffffL else memory.getLong(0)
            val size = memory.getLong(if (mac) 96 else 48)
            val modified = memory.getLong(if (mac) 48 else 88)
            FileInfo(
                size,
                mode and 0xf000 == 0x8000,
                mode and 0xf000 == 0x4000,
                mode and 0xf000 == 0xa000,
                "$device:${memory.getLong(8)}",
                modified * 1000,
            )
        }
}
