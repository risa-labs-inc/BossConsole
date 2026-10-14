package ai.rever.boss.components.dashboard.cards

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Guards that the old-timestamp branch of [formatRelativeTime] formats with the FORMAT-category
 * default locale ([Locale.Category.FORMAT]) - the one users set for dates on macOS/Windows - and
 * not the general default, which can differ (a user can read the UI in English while formatting
 * dates in French).
 *
 * This is the behaviour the explicit `Locale.getDefault(Locale.Category.FORMAT)` documents; the
 * bare `SimpleDateFormat("MMM d")` on dev already resolved to the same locale, so this test does
 * not fail against dev - it pins the property against a future regression to the general default
 * (`Locale.getDefault()`) or a hardcoded locale. It sets the general/DISPLAY default to English and
 * only the FORMAT category to French and asserts the French rendering.
 */
class ProjectCardLocaleTest {
    private val savedDefault = Locale.getDefault()
    private val savedFormat = Locale.getDefault(Locale.Category.FORMAT)

    @AfterTest
    fun tearDown() {
        Locale.setDefault(savedDefault)
        Locale.setDefault(Locale.Category.FORMAT, savedFormat)
    }

    @Test
    fun `an old timestamp is formatted with the FORMAT-category default locale`() {
        // General/DISPLAY default English, FORMAT default French - the split this guards.
        Locale.setDefault(Locale.ENGLISH)
        Locale.setDefault(Locale.Category.FORMAT, Locale.FRENCH)

        val now = 1_700_000_000_000L
        // Well before yesterday, so it hits the absolute-date (calendar) branch.
        val old = now - 10L * 86_400_000L

        val french = SimpleDateFormat("MMM d", Locale.FRENCH).format(Date(old))
        val english = SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(old))
        // Guard: the two locales must actually render this month differently, or the test proves
        // nothing. (November: "nov." vs "Nov".)
        assertNotEquals(english, french, "test locales must differ for this date")

        assertEquals(french, formatRelativeTime(old, now), "must format with the FORMAT-category locale")
    }
}
