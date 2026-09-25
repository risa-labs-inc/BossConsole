package ai.rever.boss.logging

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
     * Both tee streams funnel through here so there is one place the count is maintained.
     *
     * The count can still drift from the queue by a little, and deliberately is not locked
     * against it: an `add` landing between [clear]'s `buffer.clear()` and its `set(0)`
     * understates by one, and a `record` that polls before a concurrent `clear` and decrements
     * after can go momentarily negative. Both are bounded and self-correcting - the visible
     * effect is the buffer sitting a few lines either side of the cap, which is what a cap on a
     * debug log buffer is for. Locking the two together would put a mutex on the path every
     * `println` in the process takes, which is the cost this whole change exists to remove.
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

    /**
     * Entries waiting for listener delivery. Bounded on purpose: a listener that falls behind
     * must lose the oldest pending entries, not grow memory or stall the logging thread.
     * Delivery happens on [dispatcherThread], never on the thread that called `println`.
     */
    private val notificationQueue = ArrayBlockingQueue<LogEntry>(MAX_PENDING_NOTIFICATIONS)

    @Volatile
    private var dispatcherThread: Thread? = null

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

        dispatcherThread?.interrupt()
        dispatcherThread = null
        notificationQueue.clear()

        logger.info(LogCategory.SYSTEM, "Log capture stopped")
    }

    /**
     * Captured logs, newest retained, bounded by total characters.
     *
     * A count cap alone does not bound the copy: lines can run to [MAX_LINE_BYTES], so 10k of
     * them would hand back hundreds of megabytes. Walking newest-first until the budget is
     * spent keeps the newest line even when it alone is large, and short lines - the common
     * case - still return everything in the buffer.
     */
    fun getLogs(): List<LogEntry> {
        val kept = ArrayDeque<LogEntry>()
        var chars = 0L
        for (entry in buffer.toList().asReversed()) {
            kept.addFirst(entry)
            chars += entry.message.length
            if (chars >= MAX_GET_LOGS_CHARS) break
        }
        return kept
    }

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
        ensureDispatcher()
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
     * How many listeners are registered.
     *
     * A test seam. A provider that fails to unregister on dispose leaks silently: the leak is
     * invisible through the provider's own state, because a cancelled consumer publishes
     * nothing whether or not the listener is still attached. This is the only place the
     * difference shows.
     */
    internal val listenerCount: Int
        get() = synchronized(listeners) { listeners.size }

    /**
     * Enqueue an entry for listener delivery.
     *
     * Delivery used to run the listeners on the logging thread, inside `PrintStream.write` and
     * the `System.out` monitor - one slow Console listener stalled every thread that logged.
     * Now the logging thread only offers to a bounded queue: a full queue drops the oldest
     * pending entry, matching the capture buffer's own keep-the-newest policy.
     */
    private fun notifyListeners(entry: LogEntry) {
        if (synchronized(listeners) { listeners.isEmpty() }) return
        ensureDispatcher()
        if (!notificationQueue.offer(entry)) {
            notificationQueue.poll()
            notificationQueue.offer(entry)
        }
    }

    /** Start the delivery thread if it is not already running. Daemon: parked on the queue. */
    private fun ensureDispatcher() {
        if (dispatcherThread?.isAlive == true) return
        synchronized(this) {
            if (dispatcherThread?.isAlive == true) return
            dispatcherThread =
                thread(isDaemon = true, name = "log-capture-dispatcher") {
                    dispatchLoop()
                }
        }
    }

    /**
     * Drain [notificationQueue] to the current listeners, one entry at a time. A listener that
     * throws is skipped so a bad consumer cannot take down delivery for the rest.
     */
    // A listener callback can throw anything; the fault is dropped, not reported through
    // logger/println - those feed back through this same capture and a throwing listener
    // would churn the queue forever.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun dispatchLoop() {
        while (true) {
            val entry =
                try {
                    notificationQueue.take()
                } catch (interrupted: InterruptedException) {
                    return
                }
            val listenersCopy =
                synchronized(listeners) {
                    listeners.toList()
                }
            listenersCopy.forEach { listener ->
                try {
                    listener(entry)
                } catch (t: Throwable) {
                    // Intentionally swallowed: one listener's fault must not stop the others.
                }
            }
        }
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

        /**
         * Bytes dropped from the line currently accumulating because it passed
         * [MAX_LINE_BYTES]. A newline-less flood (a dump, \r-only progress bars, a runaway
         * printer) otherwise grew [lineBuffer] until the process ran out of heap.
         */
        private var droppedBytes = 0

        override fun write(b: Int) {
            // Write to original stream
            originalStream.write(b)

            // Capture bytes for UTF-8 decoding
            if (b == '\n'.code) {
                // Complete line - convert bytes to UTF-8 String
                val bytes = lineBuffer.toByteArray()
                if (bytes.isNotEmpty() || droppedBytes > 0) {
                    var line = String(bytes, Charsets.UTF_8)
                    if (droppedBytes > 0) {
                        line += " [truncated $droppedBytes bytes]"
                    }

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
                droppedBytes = 0
            } else if (b != '\r'.code) {
                // Accumulate bytes (skip \r), up to the per-line cap.
                if (lineBuffer.size() < MAX_LINE_BYTES) {
                    lineBuffer.write(b)
                } else {
                    droppedBytes++
                }
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

        /** One captured line keeps at most this many bytes; the rest is counted and marked. */
        const val MAX_LINE_BYTES = 64 * 1024

        /** Entries awaiting listener delivery; the oldest pending entry is dropped past this. */
        const val MAX_PENDING_NOTIFICATIONS = 4096

        /** Total characters [getLogs] will copy out, newest-first. */
        const val MAX_GET_LOGS_CHARS = 2_000_000L
    }
}
