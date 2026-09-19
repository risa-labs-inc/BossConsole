package ai.rever.boss.components.dashboard.cards

import java.text.SimpleDateFormat
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [formatRelativeTime]'s buckets, with an injected `now` so the boundaries are deterministic.
 * The regression: a timestamp in the future (clock skew, or a file mtime ahead of the clock)
 * made `now - timestamp` negative, which fell into the `< 60_000` bucket and read "Just now".
 */
class ProjectCardRelativeTimeTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `a future timestamp shows the date, not just now`() {
        val future = now + 60_000L
        assertEquals(SimpleDateFormat("MMM d").format(Date(future)), formatRelativeTime(future, now))
    }

    @Test
    fun `a recent timestamp still shows just now`() {
        assertEquals("Just now", formatRelativeTime(now - 1_000L, now))
    }

    @Test
    fun `minutes and hours buckets are unchanged`() {
        assertEquals("5m ago", formatRelativeTime(now - 5 * 60_000L, now))
        assertEquals("3h ago", formatRelativeTime(now - 3 * 3600_000L, now))
    }

    @Test
    fun `a zero timestamp is never`() {
        assertEquals("Never", formatRelativeTime(0L, now))
    }
}
