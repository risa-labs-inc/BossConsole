package ai.rever.boss.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the bound on externally requested tab opens: a `boss://url` flood
 * must stop being tabs (or prompts) after [UrlOpenRateLimiter.MAX_OPENS] inside
 * the window, and a real burst must still get through once the window slides.
 */
class UrlOpenRateLimiterTest {
    @Test
    fun `a burst past the limit is refused inside the window`() {
        var now = 1_000L
        val limiter = UrlOpenRateLimiter(nowMs = { now })

        repeat(UrlOpenRateLimiter.MAX_OPENS) { assertTrue(limiter.tryAcquire()) }
        repeat(1000) { assertFalse(limiter.tryAcquire()) }

        // Still inside the window, still refused.
        now += UrlOpenRateLimiter.WINDOW_MS - 1
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `opens outside the window stop counting`() {
        var now = 0L
        val limiter = UrlOpenRateLimiter(nowMs = { now })

        // Two opens now, the rest one beat later — staggered so expiry is partial.
        repeat(2) { assertTrue(limiter.tryAcquire()) }
        now += 1_000
        repeat(UrlOpenRateLimiter.MAX_OPENS - 2) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())

        // At WINDOW_MS the first two have aged out; exactly two slots free.
        now = UrlOpenRateLimiter.WINDOW_MS
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `a refused open does not spend budget`() {
        var now = 0L
        val limiter = UrlOpenRateLimiter(nowMs = { now })

        repeat(UrlOpenRateLimiter.MAX_OPENS) { assertTrue(limiter.tryAcquire()) }
        repeat(1000) { assertFalse(limiter.tryAcquire()) }

        // Every refused attempt left the window alone, so the first real slot
        // opens the moment the oldest admitted open ages out.
        now += UrlOpenRateLimiter.WINDOW_MS
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `a backwards clock step does not wedge the limiter`() {
        var now = 10_000L
        val limiter = UrlOpenRateLimiter(nowMs = { now })
        repeat(UrlOpenRateLimiter.MAX_OPENS) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())

        now = 1_000L
        assertTrue(limiter.tryAcquire())
        now += UrlOpenRateLimiter.WINDOW_MS
        assertTrue(limiter.tryAcquire())
    }
}
