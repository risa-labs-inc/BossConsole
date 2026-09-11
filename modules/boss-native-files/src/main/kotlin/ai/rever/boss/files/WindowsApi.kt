package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

internal object WindowsApi {
    val kernel = NativeLibrary.getInstance("kernel32")
    val nt = NativeLibrary.getInstance("ntdll")
    val security = NativeLibrary.getInstance("advapi32")

    fun error(
        operation: String,
        code: Int = Native.getLastError(),
    ): IOException =
        when (code) {
            2, 3 -> NoSuchFileException(operation)
            5 -> AccessDeniedException(operation)
            80, 183 -> FileAlreadyExistsException(operation)
            else -> IOException("$operation failed (OS error $code)")
        }

    fun check(
        result: Int,
        operation: String,
    ) {
        if (result == 0) throw error(operation)
    }

    fun checkStatus(
        status: Int,
        operation: String,
    ) {
        if (status < 0) {
            val code = nt.getFunction("RtlNtStatusToDosError").invokeInt(arrayOf<Any?>(status))
            throw error(operation, code)
        }
    }

    fun info(handle: Pointer): FileInfo =
        Memory(52).use { memory ->
            check(kernel.getFunction("GetFileInformationByHandle").invokeInt(arrayOf<Any?>(handle, memory)), "Inspect")
            val attributes = memory.getInt(0)
            val size = (memory.getInt(32).toLong() shl 32) or (memory.getInt(36).toLong() and 0xffffffffL)
            val modified = memory.getLong(20) / 10_000 - 11_644_473_600_000L
            val index = (memory.getInt(44).toLong() shl 32) or (memory.getInt(48).toLong() and 0xffffffffL)
            val directory = attributes and 0x10 != 0
            val link = attributes and 0x400 != 0
            FileInfo(size, !directory && !link, directory, link, "${memory.getInt(28)}:$index", modified)
        }

    fun close(handle: Pointer) {
        kernel.getFunction("CloseHandle").invokeInt(arrayOf<Any?>(handle))
    }
}

/** Windows x64 layouts from winternl.h: OBJECT_ATTRIBUTES=48, UNICODE_STRING=16, IO_STATUS_BLOCK=16. */
internal class WindowsOpen private constructor(
    parent: Pointer,
    private val bytes: ByteArray,
    security: Pointer?,
) : AutoCloseable {
    constructor(parent: Pointer, name: String, security: Pointer?) :
        this(parent, component(name).toByteArray(Charsets.UTF_16LE), security)

    private val memory = Memory(90L + bytes.size)

    init {
        require(bytes.size <= 65_532) { "File name exceeds native string limit" }
        memory.clear()
        memory.setInt(0, 48)
        memory.setPointer(8, parent)
        memory.setPointer(16, memory.share(48))
        memory.setInt(24, 0x1000 or 0x40) // OBJ_DONT_REPARSE | OBJ_CASE_INSENSITIVE
        memory.setPointer(32, security)
        memory.setShort(48, bytes.size.toShort())
        memory.setShort(50, (bytes.size + 2).toShort())
        memory.setPointer(56, memory.share(88))
        memory.write(88, bytes, 0, bytes.size)
    }

    fun open(
        access: Int,
        disposition: Int,
        options: Int,
    ): Pointer {
        val arguments =
            arrayOf<Any?>(
                memory.share(80),
                access or 0x100000,
                memory,
                memory.share(64),
                null,
                0x80,
                7,
                disposition,
                options or 0x20,
                null,
                0,
            )
        val status = WindowsApi.nt.getFunction("NtCreateFile").invokeInt(arguments)
        WindowsApi.checkStatus(status, "Open relative file")
        return checkNotNull(memory.getPointer(80))
    }

    override fun close() = memory.close()

    companion object {
        // An empty NT name reopens the same held object; normal child operations still require a component.
        fun reopen(handle: Pointer): WindowsOpen = WindowsOpen(handle, byteArrayOf(), null)
    }
}
