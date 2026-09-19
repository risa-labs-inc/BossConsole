package ai.rever.boss.components.dashboard.cards

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1078: formatRelativeTime's "Yesterday" bucket must answer to the calendar day in the
 * local zone, not to a 24-48h elapsed window. Two boundary failures pinned:
 * a 47h-old late-evening mtime is the day before yesterday (not "Yesterday"), and a
 * 23:59-last-night mtime is yesterday (not "Just now") one minute past midnight.
 */
class ProjectCardYesterdayTest {
    // Fixed zone so the calendar boundaries are deterministic regardless of the host.
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(
        day: LocalDate,
        hour: Int,
        minute: Int,
    ): Long =
        day
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `a 47h-old late-evening timestamp is the day before yesterday not Yesterday`() {
        val today = LocalDate.of(2026, 9, 19)
        val twoDaysBackAt23 = at(today.minusDays(2), 23, 0)
        val now = at(today, 22, 0) // 47h later

        val label = formatRelativeTime(twoDaysBackAt23, now, zone)

        assertEquals("Sep 17", label)
    }

    @Test
    fun `elapsed precision wins below a day - a 23-59 last-night timestamp reads 2m ago at 00-01`() {
        val today = LocalDate.of(2026, 9, 19)
        val lastNight2359 = at(today.minusDays(1), 23, 59)
        val now = at(today, 0, 1) // 2 minutes later, new calendar day

        val label = formatRelativeTime(lastNight2359, now, zone)

        // Correcting the issue's Case 2: 2 minutes elapsed is precise and honest -
        // "Yesterday" here would discard information, so elapsed buckets win
        // below a day. The defect is only the 24-48h window (the 47h case above).
        assertEquals("2m ago", label)
    }

    @Test
    fun `a noon-yesterday timestamp still reads Yesterday at noon today`() {
        val today = LocalDate.of(2026, 9, 19)
        val yesterdayNoon = at(today.minusDays(1), 12, 0)
        val now = at(today, 12, 0) // exactly 24h later

        val label = formatRelativeTime(yesterdayNoon, now, zone)

        assertEquals("Yesterday", label)
    }

    @Test
    fun `elapsed buckets below a day keep their elapsed semantics`() {
        val today = LocalDate.of(2026, 9, 19)
        val now = at(today, 12, 0)

        assertEquals("Just now", formatRelativeTime(now - 30_000, now, zone))
        assertEquals("5m ago", formatRelativeTime(now - 5 * 60_000, now, zone))
        assertEquals("2h ago", formatRelativeTime(now - 2 * 3600_000, now, zone))
    }
}
