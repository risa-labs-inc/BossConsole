package ai.rever.boss.sharing

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A single immutable latest frame, shared with the capability-protected loopback reader. */
internal class AppRawFrameMailbox : AutoCloseable {
    data class Snapshot(
        val sequence: Long,
        val frame: AppRawCapturedFrame?,
        val cursor: String = "default",
        val frameSequence: Long = sequence,
    )

    private val current = AtomicReference(Snapshot(0, null))
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var retired = false

    @Volatile var frameRate: Int = 30
        private set

    @Volatile var pixelFormat: String = "BGRA"
        private set

    fun requestPixelFormat(format: String) {
        require(format == "BGRA" || format == "NV12")
        pixelFormat = format
    }

    fun requestFrameRate(rate: Int) {
        require(rate == 30 || rate == 60)
        frameRate = rate
    }

    fun offer(frame: AppRawCapturedFrame?) {
        lock.withLock {
            if (retired) return
            current.updateAndGet { Snapshot(it.sequence + 1, frame, if (frame == null) "default" else it.cursor) }
            changed.signalAll()
        }
    }

    /** Wake the publisher without copying or painting the unchanged native pixels. */
    fun cursor(shape: String) {
        lock.withLock {
            if (retired || current.get().cursor == shape) return
            current.updateAndGet { it.copy(sequence = it.sequence + 1, cursor = shape) }
            changed.signalAll()
        }
    }

    fun latest(): Snapshot = current.get()

    /** Sequence inspection and waiter registration share the producer's lock: no lost wakeups. */
    fun awaitNext(
        after: Long,
        timeoutMillis: Long = 250,
    ): Snapshot {
        require(after >= -1 && timeoutMillis in 0..250)
        return lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!retired && current.get().sequence == after && remaining > 0) {
                remaining = changed.awaitNanos(remaining)
            }
            current.get()
        }
    }

    /** Page retirement is terminal; a late native callback must never restore captured pixels. */
    override fun close() {
        lock.withLock {
            if (retired) return
            retired = true
            current.updateAndGet { Snapshot(it.sequence + 1, null) }
            changed.signalAll()
        }
    }
}
