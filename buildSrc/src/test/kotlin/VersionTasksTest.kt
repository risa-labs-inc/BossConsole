import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTasksTest {
    @Test
    fun `build date is ISO and ASCII under every default locale`() {
        val date = LocalDate.of(2026, 9, 19)
        val original = Locale.getDefault()
        try {
            for (tag in listOf("en-US", "th-TH-u-ca-buddhist", "ja-JP-u-ca-japanese", "ar-EG", "fa-IR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("2026-09-19", buildDate(date), "locale $tag changed app.build.date")
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
