package ai.rever.boss.components.dashboard.cards

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProjectCardRelativeTimeTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun `timestamp just before midnight is yesterday after midnight`() {
        val now = time(2026, 9, 19, 0, 1)
        val timestamp = time(2026, 9, 18, 23, 59)

        assertEquals("Yesterday", formatRelativeTime(timestamp, now, zone, Locale.US))
    }

    @Test
    fun `late timestamp from two calendar days ago is not yesterday`() {
        val now = time(2026, 9, 19, 22, 0)
        val timestamp = time(2026, 9, 17, 23, 0)

        assertEquals("Sep 17", formatRelativeTime(timestamp, now, zone, Locale.US))
    }

    @Test
    fun `future timestamp does not read just now`() {
        val now = time(2026, 9, 19, 22, 0)
        val timestamp = time(2026, 9, 20, 1, 0)

        assertNotEquals("Just now", formatRelativeTime(timestamp, now, zone, Locale.US))
        assertEquals("Sep 20", formatRelativeTime(timestamp, now, zone, Locale.US))
    }

    @Test
    fun `same-day timestamps keep elapsed-time labels`() {
        val now = time(2026, 9, 19, 22, 0)
        val timestamp = time(2026, 9, 19, 20, 0)

        assertEquals("2h ago", formatRelativeTime(timestamp, now, zone, Locale.US))
    }

    private fun time(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
