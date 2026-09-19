import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildDateTest {
    @Test
    fun `build date is ISO regardless of default locale`() {
        val original = Locale.getDefault()
        try {
            listOf(
                Locale.US,
                Locale.forLanguageTag("th-TH-u-ca-buddhist"),
                Locale.forLanguageTag("ja-JP-u-ca-japanese"),
                Locale.forLanguageTag("ar-EG"),
            ).forEach { locale ->
                Locale.setDefault(locale)
                assertEquals("2026-09-19", buildDate(LocalDate.of(2026, 9, 19)))
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
