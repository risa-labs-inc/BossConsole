package ai.rever.boss.process

import java.io.InputStream
import java.io.InterruptedIOException

/** Never wait for EOF from descendants that inherited the owned parent's pipe. */
internal class ProcessOwnedLogInput(
    private val input: InputStream,
    private val process: Process,
) : InputStream() {
    private var remaining: Int? = null
    private var idleDelayMillis = MIN_IDLE_DELAY_MILLIS

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int = if (length == 0) 0 else readAvailable(bytes, offset, length)

    private fun readAvailable(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        while (true) {
            // A fixed post-exit snapshot cannot be extended by a descendant's later writes.
            // Observe exit before sampling bytes, or a final write between the two can be lost.
            val alive = process.isAlive
            val available = input.available()
            if (remaining == null && !alive) remaining = available.coerceAtMost(FINAL_BYTES)
            val budget = remaining
            if (budget != null && (budget == 0 || available == 0)) return -1
            if (available > 0) {
                val count = input.read(bytes, offset, minOf(length, available, budget ?: Int.MAX_VALUE))
                if (count > 0) idleDelayMillis = MIN_IDLE_DELAY_MILLIS
                if (budget != null && count > 0) remaining = budget - count
                return count
            }
            try {
                // A fixed 10 ms delay throttles a busy small OS pipe. Back off only while idle.
                Thread.sleep(idleDelayMillis)
                idleDelayMillis = (idleDelayMillis * 2).coerceAtMost(MAX_IDLE_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Process log drain interrupted")
            }
        }
    }

    override fun close() = input.close()

    companion object {
        private const val FINAL_BYTES = 1024 * 1024
        private const val MIN_IDLE_DELAY_MILLIS = 1L
        private const val MAX_IDLE_DELAY_MILLIS = 100L
    }
}
