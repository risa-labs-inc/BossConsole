package ai.rever.boss.logging

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop-specific log capture system.
 *
 * Intercepts System.out and System.err to capture all console output.
 * Uses a "tee" approach: logs are sent to BOTH the original stream AND our capture buffer.
 */
class DesktopLogCapture {
    private val logger = BossLogger.forComponent("DesktopLogCapture")
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    // Thread-safe buffer for captured logs (circular buffer implemented via pruning)
    private val buffer = ConcurrentLinkedQueue<LogEntry>()

    /**
     * Tracks [buffer]'s length so trimming never has to ask the queue for it.
     *
     * `ConcurrentLinkedQueue.size()` is O(n) by contract - it walks the list - and the trim that
     * used it ran once per captured line, re-reading `size` on each iteration of its own loop. At
     * the 10k cap that is a 10,000-node walk per line of output, paid on whichever thread did the
     * logging while it held the `System.out` monitor (the tee runs inside `PrintStream.write`).
     * Every other thread that wanted to log then queued behind it, so a burst of output from one
     * component stalled threads with nothing to do with logging.
     *
     * Kept beside [buffer] rather than inside the tee because [clear] empties the queue, and a
     * count owned by the tee would have been left overstated - which would have made the next
     * writes trim a nearly empty buffer.
     */
    private val bufferedLines = AtomicInteger(0)

    /**
     * Appends one captured line, trimming to [MAX_BUFFERED_LINES], then notifies listeners.
     *
     * Both tee streams funnel through here so the count and the queue cannot drift apart.
     */
    private fun record(entry: LogEntry) {
        buffer.add(entry)
        var count = bufferedLines.incrementAndGet()
        while (count > MAX_BUFFERED_LINES) {
            // Queue already drained by a concurrent clear(): that resets the count, so stop.
            if (buffer.poll() == null) break
            count = bufferedLines.decrementAndGet()
        }
        notifyListeners(entry)
    }

    // Listeners for new log entries
    private val listeners = mutableListOf<(LogEntry) -> Unit>()

    @Volatile
    private var isCapturing = false

    /**
     * Start capturing logs.
     * Sets up PrintStream wrappers that tee output to both original streams and our buffer.
     */
    fun start() {
        if (isCapturing) return

        isCapturing = true

        // Create tee streams that write to both original stream and our buffer
        val teeOut = TeeOutputStream(originalOut, LogSource.STDOUT, ::record)
        val teeErr = TeeOutputStream(originalErr, LogSource.STDERR, ::record)

        // Replace System streams
        System.setOut(PrintStream(teeOut, true, Charsets.UTF_8))
        System.setErr(PrintStream(teeErr, true, Charsets.UTF_8))

        logger.info(LogCategory.SYSTEM, "Log capture started")
    }

    /**
     * Stop capturing logs and restore original streams.
     */
    fun stop() {
        if (!isCapturing) return

        // Restore original streams
        System.setOut(originalOut)
        System.setErr(originalErr)

        isCapturing = false
        logger.info(LogCategory.SYSTEM, "Log capture stopped")
    }

    /**
     * Get all captured logs.
     */
    fun getLogs(): List<LogEntry> = buffer.toList()

    /**
     * Clear all captured logs.
     */
    fun clear() {
        buffer.clear()
        bufferedLines.set(0)
        logger.debug(LogCategory.SYSTEM, "Log buffer cleared")
    }

    /**
     * Add a listener for new log entries.
     */
    fun addListener(listener: (LogEntry) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove a listener.
     */
    fun removeListener(listener: (LogEntry) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Notify all listeners of a new log entry.
     * Uses copy-on-read pattern to avoid holding lock during callback invocation.
     */
    private fun notifyListeners(entry: LogEntry) {
        val listenersCopy =
            synchronized(listeners) {
                listeners.toList()
            }
        listenersCopy.forEach { it(entry) }
    }

    /**
     * OutputStream that writes to both the original stream and captures logs.
     */
    private class TeeOutputStream(
        private val originalStream: PrintStream,
        private val source: LogSource,
        private val onNewEntry: (LogEntry) -> Unit,
    ) : OutputStream() {
        private val lineBuffer = ByteArrayOutputStream()

        override fun write(b: Int) {
            // Write to original stream
            originalStream.write(b)

            // Capture bytes for UTF-8 decoding
            if (b == '\n'.code) {
                // Complete line - convert bytes to UTF-8 String
                val bytes = lineBuffer.toByteArray()
                if (bytes.isNotEmpty()) {
                    val line = String(bytes, Charsets.UTF_8)

                    val entry =
                        LogEntry(
                            timestamp = System.currentTimeMillis(),
                            message = line,
                            source = source,
                        )

                    // Buffering, trimming and listener notification all live in
                    // DesktopLogCapture.record so the queue and its counter stay in step.
                    onNewEntry(entry)
                }
                lineBuffer.reset()
            } else if (b != '\r'.code) {
                // Accumulate bytes (skip \r)
                lineBuffer.write(b)
            }
        }

        override fun flush() {
            originalStream.flush()
        }

        override fun close() {
            originalStream.close()
        }
    }

    private companion object {
        /** Captured lines retained; oldest are dropped past this. */
        const val MAX_BUFFERED_LINES = 10000
    }
}
