package ai.rever.boss.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessMonitorTest {

    // ── calculateBackoff ────────────────────────────────────────────────

    @Test
    fun `attempt 0 returns base delay`() {
        val result = ProcessMonitor.calculateBackoff(attempt = 0, baseMs = 1_000, maxMs = 30_000)
        assertEquals(1_000L, result)
    }

    @Test
    fun `attempt 1 returns 2x base`() {
        val result = ProcessMonitor.calculateBackoff(attempt = 1, baseMs = 1_000, maxMs = 30_000)
        assertEquals(2_000L, result)
    }

    @Test
    fun `attempt 5 returns 32x base`() {
        val result = ProcessMonitor.calculateBackoff(attempt = 5, baseMs = 1_000, maxMs = 30_000)
        // 1000 * 2^5 = 32000, but max is 30000
        assertEquals(30_000L, result)
    }

    @Test
    fun `backoff is capped at maxMs`() {
        // 1000 * 2^10 = 1_024_000, far exceeding max
        val result = ProcessMonitor.calculateBackoff(attempt = 10, baseMs = 1_000, maxMs = 30_000)
        assertEquals(30_000L, result)
    }

    @Test
    fun `attempt 4 returns 16x base when under cap`() {
        // 1000 * 2^4 = 16_000, under 30_000 cap
        val result = ProcessMonitor.calculateBackoff(attempt = 4, baseMs = 1_000, maxMs = 30_000)
        assertEquals(16_000L, result)
    }

    @Test
    fun `negative attempt is coerced to 0 and returns base`() {
        val result = ProcessMonitor.calculateBackoff(attempt = -5, baseMs = 1_000, maxMs = 30_000)
        assertEquals(1_000L, result)
    }

    @Test
    fun `very large negative attempt is safe`() {
        val result = ProcessMonitor.calculateBackoff(attempt = Int.MIN_VALUE, baseMs = 1_000, maxMs = 30_000)
        assertEquals(1_000L, result)
    }

    @Test
    fun `custom base and max are respected`() {
        val result = ProcessMonitor.calculateBackoff(attempt = 3, baseMs = 500, maxMs = 2_000)
        // 500 * 2^3 = 4000, capped at 2000
        assertEquals(2_000L, result)
    }

    @Test
    fun `backoff with default parameters`() {
        // Using default baseMs=1000, maxMs=30000
        val r0 = ProcessMonitor.calculateBackoff(attempt = 0)
        val r1 = ProcessMonitor.calculateBackoff(attempt = 1)
        assertEquals(1_000L, r0)
        assertEquals(2_000L, r1)
    }

    @Test
    fun `very large attempt is clamped to 30 and capped`() {
        // attempt is coerced to 30, 1000 * 2^30 = huge number, capped at 30_000
        val result = ProcessMonitor.calculateBackoff(attempt = Int.MAX_VALUE, baseMs = 1_000, maxMs = 30_000)
        assertTrue(result <= 30_000L)
    }
}
