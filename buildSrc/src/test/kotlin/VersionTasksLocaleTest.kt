import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BossConsole#1100: `app.build.date` must be a locale-independent ISO date. The previous
 * `SimpleDateFormat("yyyy-MM-dd").format(Date())` took both the calendar and the digit
 * symbols from the JVM default locale: `th-TH-u-ca-buddhist` writes `2569-09-19`, `ar-EG`
 * writes `٢٠٢٦-٠٩-١٩`. `DateTimeFormatter.ISO_LOCAL_DATE` pins the ISO calendar and ASCII
 * digits, so every locale writes the same string for the same date.
 *
 * The original report included a fixed reproduction table; this test walks a representative
 * subset, plus a wall-clock default call, plus the digits-only character-class assertion
 * that catches the most common regression (a non-ASCII digit slipping through).
 */
class VersionTasksLocaleTest {
    @Test
    fun `buildDate returns the ISO string regardless of default locale`() {
        val date = LocalDate.of(2026, 9, 19)
        val cases =
            listOf(
                Locale.US to "2026-09-19",
                Locale.forLanguageTag("th-TH-u-ca-buddhist") to "2026-09-19",
                Locale.forLanguageTag("ja-JP-u-ca-japanese") to "2026-09-19",
                Locale.forLanguageTag("ar-EG") to "2026-09-19",
                Locale.forLanguageTag("fa-IR") to "2026-09-19",
            )
        for ((locale, expected) in cases) {
            val previous = Locale.getDefault()
            Locale.setDefault(locale)
            try {
                assertEquals(expected, buildDate(date), "default locale $locale")
            } finally {
                Locale.setDefault(previous)
            }
        }
    }

    @Test
    fun `buildDate returns ASCII digits only, no locale numerals`() {
        val date = LocalDate.of(2026, 9, 19)
        // Walking through a locale that would emit Arabic-Indic digits if a SimpleDateFormat
        // slipped back in. The ISO formatter stays ASCII regardless of the default locale.
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            val rendered = buildDate(date)
            assertEquals(10, rendered.length, "ISO yyyy-MM-dd is exactly 10 chars")
            assertTrue(
                rendered.all { it in '0'..'9' || it == '-' },
                "every char must be an ASCII digit or '-': $rendered",
            )
        } finally {
            Locale.setDefault(Locale.US)
        }
    }

    @Test
    fun `buildDate default call is ISO and locale-independent`() {
        // No fixed date argument: walks every locale, asserts the format pattern matches ISO,
        // and the digits stay ASCII. Catches a regression where a future call site passes a
        // fresh Date() through SimpleDateFormat.
        val locales =
            listOf(
                Locale.US,
                Locale.forLanguageTag("th-TH-u-ca-buddhist"),
                Locale.forLanguageTag("ja-JP-u-ca-japanese"),
                Locale.forLanguageTag("ar-EG"),
                Locale.forLanguageTag("fa-IR"),
            )
        for (locale in locales) {
            val previous = Locale.getDefault()
            Locale.setDefault(locale)
            try {
                val rendered = buildDate()
                assertTrue(
                    rendered.matches(Regex("\\d{4}-\\d{2}-\\d{2}")),
                    "ISO pattern yyyy-MM-dd expected, got $rendered (locale $locale)",
                )
            } finally {
                Locale.setDefault(previous)
            }
        }
    }
}
