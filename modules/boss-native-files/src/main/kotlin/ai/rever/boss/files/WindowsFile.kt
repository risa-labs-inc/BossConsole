package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

internal class WindowsFile(
    private val pointer: Pointer,
    private val writable: Boolean,
    verifyPrivate: Boolean = writable,
    private val readable: Boolean = true,
) : SeekableByteChannel {
    private var closed = false
    private var offset = 0L

    init {
        var valid = false
        try {
            val type = WindowsApi.kernel.getFunction("GetFileType").invokeInt(arrayOf<Any?>(pointer))
            require(type == 1 && WindowsApi.info(pointer).isRegularFile) { "Only regular disk files can be opened" }
            if (verifyPrivate) WindowsSecurity.verify(pointer)
            valid = true
        } finally {
            if (!valid) close()
        }
    }

    private fun handle(): Pointer {
        if (closed) throw ClosedChannelException()
        return pointer
    }

    @Synchronized
    override fun read(dst: ByteBuffer): Int {
        if (!readable) throw NonReadableChannelException()
        if (!dst.hasRemaining()) return 0
        val bytes = ByteArray(minOf(dst.remaining(), 65_536))
        val count = transfer("ReadFile", bytes)
        dst.put(bytes, 0, count)
        return if (count == 0) -1 else count
    }

    @Synchronized
    override fun write(src: ByteBuffer): Int {
        if (!writable) throw NonWritableChannelException()
        val bytes = ByteArray(minOf(src.remaining(), 65_536))
        src.duplicate().get(bytes)
        val count = transfer("WriteFile", bytes)
        src.position(src.position() + count)
        return count
    }

    private fun transfer(
        operation: String,
        bytes: ByteArray,
    ): Int =
        Memory(4).use { transferred ->
            val seekArguments = arrayOf<Any?>(handle(), offset, null, 0)
            val seek = WindowsApi.kernel.getFunction("SetFilePointerEx").invokeInt(seekArguments)
            WindowsApi.check(seek, "Seek")
            val arguments = arrayOf<Any?>(handle(), bytes, bytes.size, transferred, null)
            WindowsApi.check(WindowsApi.kernel.getFunction(operation).invokeInt(arguments), operation)
            val count = transferred.getInt(0)
            require(count in 0..bytes.size) { "Invalid file transfer length" }
            offset += count
            count
        }

    @Synchronized
    override fun position(): Long {
        handle()
        return offset
    }

    @Synchronized
    override fun position(newPosition: Long): SeekableByteChannel {
        handle()
        require(newPosition >= 0)
        offset = newPosition
        return this
    }

    @Synchronized
    override fun size(): Long = WindowsApi.info(handle()).size

    @Synchronized
    override fun truncate(size: Long): SeekableByteChannel {
        require(size >= 0)
        if (!writable) throw NonWritableChannelException()
        if (size < size()) {
            Memory(8).use { end ->
                end.setLong(0, size)
                val arguments = arrayOf<Any>(handle(), 6, end, 8) // FileEndOfFileInfo
                val result = WindowsApi.kernel.getFunction("SetFileInformationByHandle").invokeInt(arguments)
                WindowsApi.check(result, "Truncate")
            }
        }
        offset = minOf(offset, size)
        return this
    }

    @Synchronized
    override fun isOpen(): Boolean = !closed

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            WindowsApi.close(pointer)
        }
    }
}
