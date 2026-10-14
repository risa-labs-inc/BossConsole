package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppCaptureDiagnosticsTest {
    @Test
    fun `latest observations distinguish startup skips repeated reads and later overwritten frames`() {
        val probe = AppCaptureDiagnostics()
        val stream = probe.stream(mac = true)
        stream.consume(null, 10)
        stream.stamp(10)
        val first = stream.stamp(20)
        stream.consume(first, 30)
        stream.consume(first, 40)
        stream.stamp(50)
        stream.stamp(60)
        stream.consume(stream.stamp(70), 80)
        val result = probe.snapshot()
        assertEquals(4L, result.counters.getValue("latest_reads"))
        assertEquals(1L, result.counters.getValue("latest_empty"))
        assertEquals(1L, result.counters.getValue("latest_repeated"))
        assertEquals(2L, result.counters.getValue("latest_new"))
        assertEquals(1L, result.counters.getValue("first_read_skipped"))
        assertEquals(2L, result.counters.getValue("latest_skipped"))
        assertEquals(2L, result.timings.getValue("latest_age").samples)
        assertEquals(20L, result.timings.getValue("latest_age").totalNanos)
    }

    @Test
    fun `stream sequences remain independent when captures own multiple windows`() {
        val probe = AppCaptureDiagnostics()
        val first = probe.stream(mac = true)
        val second = probe.stream(mac = false)
        repeat(4) { first.stamp(10) }
        first.consume(first.stamp(20), 30)
        second.consume(second.stamp(20), 30)
        val result = probe.snapshot()
        assertEquals(1L, result.counters.getValue("mac_streams"))
        assertEquals(1L, result.counters.getValue("helper_streams"))
        assertEquals(4L, result.counters.getValue("first_read_skipped"))
        assertEquals(0L, result.counters.getValue("latest_skipped"))
        assertEquals(2L, result.counters.getValue("latest_new"))
    }

    @Test
    fun `concurrent diagnostics retain bounded histogram storage and exact settled totals`() {
        val probe = AppCaptureDiagnostics()
        val executor = Executors.newFixedThreadPool(4)
        val completed = CountDownLatch(4)
        try {
            repeat(4) {
                executor.execute {
                    try {
                        repeat(1_000) {
                            probe.add(AppCaptureDiagnostics.Counter.NATIVE_COMPLETE)
                            probe.time(AppCaptureDiagnostics.Timing.NATIVE_COPY, 100_000)
                        }
                    } finally {
                        completed.countDown()
                    }
                }
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            probe.time(AppCaptureDiagnostics.Timing.NATIVE_COPY, -1)
            probe.time(AppCaptureDiagnostics.Timing.NATIVE_COPY, 6_000_000_000)
            val result = probe.snapshot()
            val timing = result.timings.getValue("native_copy")
            assertEquals(4_000L, result.counters.getValue("native_complete"))
            assertEquals(4_001L, timing.samples)
            assertEquals(6_400_000_000L, timing.totalNanos)
            assertEquals(AppCaptureDiagnostics.BOUNDS_NANOS.size + 1, timing.buckets.size)
            assertEquals(4_000L, timing.buckets.first())
            assertEquals(1L, timing.buckets.last())
            assertEquals(timing.samples, timing.buckets.sum())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `optional measurement preserves values and failures without repeating work`() {
        val expected = Any()
        var calls = 0
        assertSame(
            expected,
            measuredCapture(null, AppCaptureDiagnostics.Timing.COMPOSE) {
                calls++
                expected
            },
        )
        assertEquals(1, calls)
        val failure = IllegalStateException("synthetic")
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                measuredCapture(null, AppCaptureDiagnostics.Timing.COMPOSE) { throw failure }
            },
        )
        val probe = AppCaptureDiagnostics()
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                measuredCapture(probe, AppCaptureDiagnostics.Timing.COMPOSE) { throw failure }
            },
        )
        assertEquals(
            1L,
            probe
                .snapshot()
                .timings
                .getValue("compose")
                .samples,
        )
    }
}
