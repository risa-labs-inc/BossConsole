package ai.rever.boss.sharing

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** Explicit per-capture benchmark probe. Never contains pixels, native handles or account identifiers. */
internal class AppCaptureDiagnostics {
    enum class Counter {
        MAC_STREAMS,
        HELPER_STREAMS,
        NATIVE_CALLBACKS,
        NATIVE_COMPLETE,
        NATIVE_COPIED,
        NATIVE_PUBLISHED,
        HELPER_FRAMES,
        LATEST_READS,
        LATEST_EMPTY,
        LATEST_REPEATED,
        LATEST_NEW,
        LATEST_SKIPPED,
        FIRST_READ_SKIPPED,
        WORKER_PASSES,
        WORKER_PAUSED,
        WORKER_NO_PIXELS,
        WORKER_UNCHANGED,
        WORKER_GEOMETRY_REJECTED,
        WORKER_PUBLISHED,
        WORKER_FAILURES,
    }

    enum class Timing {
        NATIVE_COPY,
        HELPER_READ_WAIT,
        LATEST_AGE,
        WORKER_PERIOD,
        WORKER_GAP,
        WORKER_WORK,
        MINIMIZE_QUEUE,
        MINIMIZE_WORK,
        GEOMETRY_QUEUE,
        GEOMETRY_WORK,
        FINAL_GEOMETRY_QUEUE,
        FINAL_GEOMETRY_WORK,
        COMPOSE,
        DELIVERY_OLDEST_AGE,
    }

    data class Histogram(
        val samples: Long,
        val totalNanos: Long,
        val buckets: List<Long>,
    )

    data class Snapshot(
        val counters: Map<String, Long>,
        val timings: Map<String, Histogram>,
    )

    private val counters = AtomicLongArray(Counter.entries.size)
    private val histograms = Array(Timing.entries.size) { AtomicLongArray(BOUNDS_NANOS.size + 3) }

    fun add(
        counter: Counter,
        amount: Long = 1,
    ) {
        if (amount > 0) counters.addAndGet(counter.ordinal, amount)
    }

    fun time(
        timing: Timing,
        nanos: Long,
    ) {
        if (nanos < 0) return
        val histogram = histograms[timing.ordinal]
        val bucket = BOUNDS_NANOS.indexOfFirst { nanos <= it }.let { if (it < 0) BOUNDS_NANOS.size else it }
        histogram.incrementAndGet(bucket + 2)
        histogram.addAndGet(1, nanos)
        histogram.incrementAndGet(0)
    }

    fun stream(mac: Boolean): Stream {
        add(if (mac) Counter.MAC_STREAMS else Counter.HELPER_STREAMS)
        return Stream(this)
    }

    /** Snapshots are aggregate but not atomic across independently updated counters. */
    fun snapshot(): Snapshot =
        Snapshot(
            Counter.entries.associate { it.name.lowercase() to counters.get(it.ordinal) },
            Timing.entries.associate { timing ->
                val histogram = histograms[timing.ordinal]
                timing.name.lowercase() to
                    Histogram(
                        histogram.get(0),
                        histogram.get(1),
                        List(BOUNDS_NANOS.size + 1) { histogram.get(it + 2) },
                    )
            },
        )

    class Stream internal constructor(
        private val owner: AppCaptureDiagnostics,
    ) {
        private val published = AtomicLong()
        private val consumed = AtomicLong()

        fun stamp(readyAtNanos: Long): AppCaptureFrameStamp =
            AppCaptureFrameStamp(
                published.incrementAndGet(),
                readyAtNanos,
            )

        fun consume(
            stamp: AppCaptureFrameStamp?,
            readAtNanos: Long,
        ) {
            owner.add(Counter.LATEST_READS)
            if (stamp == null) {
                owner.add(Counter.LATEST_EMPTY)
                return
            }
            val previous = consumed.getAndUpdate { maxOf(it, stamp.sequence) }
            if (stamp.sequence <= previous) {
                owner.add(Counter.LATEST_REPEATED)
                return
            }
            owner.add(Counter.LATEST_NEW)
            owner.add(
                if (previous == 0L) Counter.FIRST_READ_SKIPPED else Counter.LATEST_SKIPPED,
                stamp.sequence - previous - 1,
            )
            owner.time(Timing.LATEST_AGE, readAtNanos - stamp.readyAtNanos)
        }
    }

    companion object {
        // Final bucket is overflow, so memory is independent of frame count or run length.
        val BOUNDS_NANOS =
            listOf(
                100_000L,
                250_000L,
                500_000L,
                1_000_000L,
                2_000_000L,
                4_000_000L,
                8_000_000L,
                12_000_000L,
                16_666_667L,
                25_000_000L,
                50_000_000L,
                100_000_000L,
                250_000_000L,
                1_000_000_000L,
                5_000_000_000L,
            )
    }
}

/** Internal immutable timing only. Never serialized with media or retained beyond its captured frame. */
internal data class AppCaptureFrameStamp(
    val sequence: Long,
    val readyAtNanos: Long,
)

internal inline fun <T> measuredCapture(
    diagnostics: AppCaptureDiagnostics?,
    timing: AppCaptureDiagnostics.Timing,
    action: () -> T,
): T {
    if (diagnostics == null) return action()
    val began = System.nanoTime()
    return try {
        action()
    } finally {
        diagnostics.time(timing, System.nanoTime() - began)
    }
}

/** The disabled branch performs exactly the original EDT dispatch, without a diagnostic clock read. */
internal fun <T> measuredCaptureEdt(
    diagnostics: AppCaptureDiagnostics?,
    queueTiming: AppCaptureDiagnostics.Timing,
    workTiming: AppCaptureDiagnostics.Timing,
    action: () -> T,
): T {
    if (diagnostics == null) return onEdt(action)
    val submitted = System.nanoTime()
    return onEdt {
        diagnostics.time(queueTiming, System.nanoTime() - submitted)
        measuredCapture(diagnostics, workTiming, action)
    }
}
