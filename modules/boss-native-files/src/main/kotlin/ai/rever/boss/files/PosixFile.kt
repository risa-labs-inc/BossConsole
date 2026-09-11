package ai.rever.boss.files

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

internal class PosixFile(
    private val descriptor: Int,
    private val writable: Boolean,
    private val readable: Boolean = true,
) : SeekableByteChannel {
    private var closed = false
    private var offset = 0L

    init {
        var valid = false
        try {
            require(PosixApi.info(descriptor).isRegularFile) { "Only regular files can be opened" }
            valid = true
        } finally {
            if (!valid) close()
        }
    }

    private fun handle(): Int {
        if (closed) throw ClosedChannelException()
        return descriptor
    }

    @Synchronized
    override fun read(dst: ByteBuffer): Int {
        if (!readable) throw NonReadableChannelException()
        if (!dst.hasRemaining()) return 0
        val buffer = ByteArray(minOf(dst.remaining(), 65_536))
        val arguments = arrayOf<Any>(handle(), buffer, buffer.size.toLong(), offset)
        val count = PosixApi.library.getFunction("pread").invokeLong(arguments)
        if (count < 0) throw PosixApi.error("Read")
        dst.put(buffer, 0, count.toInt())
        offset += count
        return if (count == 0L) -1 else count.toInt()
    }

    @Synchronized
    override fun write(src: ByteBuffer): Int {
        if (!writable) throw NonWritableChannelException()
        val buffer = ByteArray(minOf(src.remaining(), 65_536))
        src.duplicate().get(buffer)
        val arguments = arrayOf<Any>(handle(), buffer, buffer.size.toLong(), offset)
        val count = PosixApi.library.getFunction("pwrite").invokeLong(arguments)
        if (count < 0) throw PosixApi.error("Write")
        src.position(src.position() + count.toInt())
        offset += count
        return count.toInt()
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
    override fun size(): Long = PosixApi.info(handle()).size

    @Synchronized
    override fun truncate(size: Long): SeekableByteChannel {
        require(size >= 0)
        if (!writable) throw NonWritableChannelException()
        if (size < size()) {
            val result = PosixApi.library.getFunction("ftruncate").invokeInt(arrayOf<Any>(handle(), size))
            PosixApi.check(result, "Truncate")
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
            PosixApi.library.getFunction("close").invokeInt(arrayOf<Any>(descriptor))
        }
    }
}
