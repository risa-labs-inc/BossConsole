package ai.rever.boss.logging

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue

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
        val teeOut = TeeOutputStream(originalOut, buffer, LogSource.STDOUT, ::notifyListeners)
        val teeErr = TeeOutputStream(originalErr, buffer, LogSource.STDERR, ::notifyListeners)

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
    fun getLogs(): List<LogEntry> {
        return buffer.toList()
    }

    /**
     * Clear all captured logs.
     */
    fun clear() {
        buffer.clear()
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
        val listenersCopy = synchronized(listeners) {
            listeners.toList()
        }
        listenersCopy.forEach { it(entry) }
    }

    /**
     * OutputStream that writes to both the original stream and captures logs.
     */
    private class TeeOutputStream(
        private val originalStream: PrintStream,
        private val buffer: ConcurrentLinkedQueue<LogEntry>,
        private val source: LogSource,
        private val onNewEntry: (LogEntry) -> Unit
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

                    val entry = LogEntry(
                        timestamp = System.currentTimeMillis(),
                        message = line,
                        source = source
                    )

                    buffer.add(entry)

                    // Prune old entries if over limit
                    while (buffer.size > 10000) {
                        buffer.poll()
                    }

                    // Notify listeners
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
}
