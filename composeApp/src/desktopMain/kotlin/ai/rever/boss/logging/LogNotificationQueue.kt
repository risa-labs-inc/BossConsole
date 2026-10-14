package ai.rever.boss.logging

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bounded FIFO with an atomic drop-oldest append. No listener runs under its lock. */
internal class LogNotificationQueue(
    private val capacity: Int,
) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val entries = ArrayDeque<LogEntry>()

    init {
        require(capacity > 0)
    }

    fun append(entry: LogEntry) =
        lock.withLock {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(entry)
            available.signal()
        }

    fun take(): LogEntry =
        lock.withLock {
            while (entries.isEmpty()) available.await()
            entries.removeFirst()
        }

    fun clear() = lock.withLock { entries.clear() }
}
