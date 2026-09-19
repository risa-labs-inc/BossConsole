package ai.rever.boss.components.dashboard.cards

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The old-timestamp branch of [formatRelativeTime] formats with an explicit `Locale.getDefault()`,
 * matching the rest of the codebase rather than relying on the bare `SimpleDateFormat` constructor.
 */
class ProjectCardLocaleTest {
    @Test
    fun `an old timestamp is formatted with the default locale`() {
        val now = 1_700_000_000_000L
        // Older than the 48h "Yesterday" window, so it hits the absolute-date branch.
        val old = now - 10L * 86_400_000L

        val expected = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(old))
        assertEquals(expected, formatRelativeTime(old, now))
    }
}
