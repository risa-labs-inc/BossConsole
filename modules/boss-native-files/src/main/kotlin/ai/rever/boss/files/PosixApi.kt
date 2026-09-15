package ai.rever.boss.files

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.concurrent.ConcurrentHashMap

internal object PosixApi {
    val mac = Platform.isMac()
    val inodeSuffix = if (mac && Platform.ARCH == "x86-64") "\$INODE64" else ""
    val library = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))

    // Linux arm64 uses different open flags from x86-64 (arch/arm64/include/uapi/asm/fcntl.h).
    private val linuxArm64 = !mac && Platform.ARCH == "aarch64"
    val noFollow =
        if (mac) {
            0x100
        } else if (linuxArm64) {
            0x8000
        } else {
            0x20000
        }
    val closeOnExec = if (mac) 0x01000000 else 0x80000
    val directory =
        if (mac) {
            0x00100000
        } else if (linuxArm64) {
            0x4000
        } else {
            0x10000
        }
    val search = if (mac) 0x40000000 else 0x200000 // O_SEARCH / O_PATH
    val nonBlocking = if (mac) 4 else 0x800

    fun error(
        operation: String,
        code: Int = Native.getLastError(),
    ): IOException =
        when (code) {
            2 -> NoSuchFileException(operation)
            13, 1 -> AccessDeniedException(operation)
            17 -> FileAlreadyExistsException(operation)
            18 -> CrossDeviceMoveException()
            20 -> java.nio.file.NotDirectoryException(operation)
            (if (mac) 66 else 39) -> DirectoryNotEmptyException(operation)
            else -> IOException("$operation failed (OS error $code)")
        }

    fun check(
        result: Int,
        operation: String,
    ): Int {
        if (result < 0) throw error(operation)
        return result
    }

    private val statFunctions = ConcurrentHashMap<String, Function>()

    fun stat(
        name: String,
        legacy: String,
        arguments: Array<Any>,
    ): Int {
        val function =
            statFunctions.getOrPut(name) {
                try {
                    library.getFunction(name)
                } catch (missing: UnsatisfiedLinkError) {
                    if (mac || Platform.ARCH !in setOf("x86-64", "aarch64")) throw missing
                    library.getFunction(legacy)
                }
            }
        val nativeArguments =
            if (function.name == legacy) {
                // glibc's versioned ABI: x86-64 uses version 1; Linux aarch64 uses version 0.
                val version = if (Platform.ARCH == "x86-64") 1 else 0
                Array<Any>(arguments.size + 1) { if (it == 0) version else arguments[it - 1] }
            } else {
                arguments
            }
        return function.invokeInt(nativeArguments)
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
                    stat("fstat$suffix", "__fxstat", arrayOf<Any>(descriptor, memory))
                } else {
                    val flags = if (mac) 0x20 else 0x100 // AT_SYMLINK_NOFOLLOW
                    val arguments = arrayOf<Any>(descriptor, component(name), memory, flags)
                    stat("fstatat$suffix", "__fxstatat", arguments)
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
                modified * 1000 + memory.getLong(if (mac) 56 else 96) / 1_000_000,
                modified * 1_000_000_000 + memory.getLong(if (mac) 56 else 96),
                if (mac && name != null) entryName(descriptor, name) else null,
            )
        }

    private fun entryName(
        directory: Int,
        name: String,
    ): String =
        Memory(24).use { attributes ->
            attributes.clear()
            attributes.setShort(0, 5)
            attributes.setInt(4, 1) // ATTR_CMN_NAME returns the stored spelling, including for symlinks.
            Memory(4096).use { result ->
                check(
                    library.getFunction("getattrlistat").invokeInt(
                        arrayOf<Any>(directory, component(name), attributes, result, result.size(), 1),
                    ),
                    "Read entry name",
                ) // FSOPT_NOFOLLOW
                val offset = result.getInt(4)
                val length = result.getInt(8)
                require(offset >= 8 && length > 0 && 4L + offset + length <= result.size()) { "Invalid entry name" }
                component(result.getString(4L + offset, "UTF-8"))
            }
        }
}
