package ai.rever.boss.process

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.UUID

/** Rotation owns the writable descriptor; children write pipes and never retain an obsolete log descriptor. */
internal class RotatingProcessLog(
    private val directory: ProcessLogDirectory,
    private val name: String,
    private val maximumBytes: Int = 10 * 1024 * 1024,
    private val fileCount: Int = 5,
) : AutoCloseable {
    private var channel: SeekableByteChannel? = null
    private var written = 0

    init {
        require(maximumBytes > 0 && fileCount > 0)
    }

    @Synchronized
    fun append(
        bytes: ByteArray,
        count: Int,
    ) {
        var offset = 0
        while (offset < count) {
            if (channel == null || written == maximumBytes) rotate()
            val size = minOf(maximumBytes - written, count - offset)
            val buffer = ByteBuffer.wrap(bytes, offset, size)
            while (buffer.hasRemaining()) {
                if (checkNotNull(channel).write(buffer) == 0) throw IOException("Process log write made no progress")
            }
            offset += size
            written += size
        }
    }

    private fun rotate() {
        channel?.close()
        channel = null
        for (index in 0 until fileCount) {
            val file = if (index == 0) "$name.log" else "$name.$index.log"
            val attributes = directory.attributes(file)
            if (attributes != null && (!attributes.isRegularFile || attributes.size > maximumBytes)) {
                directory.delete(file)
            }
        }
        directory.delete(if (fileCount == 1) "$name.log" else "$name.${fileCount - 1}.log")
        for (index in (fileCount - 1) downTo 1) {
            directory.move(if (index == 1) "$name.log" else "$name.${index - 1}.log", "$name.$index.log")
        }
        val temporary = ".$name-${UUID.randomUUID()}.part"
        val next = directory.create(temporary)
        var promoted = false
        try {
            if (!directory.move(temporary, "$name.log")) {
                throw IOException("New process log disappeared before promotion")
            }
            channel = next
            written = 0
            promoted = true
        } finally {
            if (!promoted) {
                next.close()
                directory.delete(temporary)
            }
        }
    }

    @Synchronized
    override fun close() {
        channel?.close()
        channel = null
    }
}
