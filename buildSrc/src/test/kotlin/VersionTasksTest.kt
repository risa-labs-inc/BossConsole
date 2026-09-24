import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTasksTest {
    @Test
    fun `build date is ISO and ASCII under every default locale`() {
        val date = LocalDate.of(2026, 9, 19)
        val original = Locale.getDefault()
        try {
            for (tag in listOf("en-US", "th-TH-u-ca-buddhist", "ja-JP-u-ca-japanese", "ar-EG", "fa-IR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("2026-09-19", isoBuildDate(date), "locale $tag changed app.build.date")
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `build date is the UTC day whatever the default time zone`() {
        val original = TimeZone.getDefault()
        try {
            // UTC+14 and UTC-12 are a full day apart for most of every day, so a local-zone read
            // disagrees with UTC under at least one of them.
            for (zone in listOf("Pacific/Kiritimati", "Etc/GMT+12")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val before = isoBuildDate(LocalDate.now(ZoneOffset.UTC))
                val written = isoBuildDate()
                val after = isoBuildDate(LocalDate.now(ZoneOffset.UTC))
                // Bracketed so a run that crosses UTC midnight cannot fail spuriously.
                assertTrue(written == before || written == after, "zone $zone wrote $written, UTC day is $before")
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // The tests above cover the helper, not its call sites. A call site that went back to
    // SimpleDateFormat would bring the locale bug back without failing them, so check the source.
    @Test
    fun `no call site formats the build date with SimpleDateFormat`() {
        // Gradle runs buildSrc's tests from the buildSrc directory.
        val source = File("src/main/kotlin/VersionTasks.kt")
        assertTrue(source.isFile, "expected to run from buildSrc: ${source.absolutePath}")
        // A use, not a mention: isoBuildDate's KDoc names SimpleDateFormat to explain the old bug.
        val use = Regex("""SimpleDateFormat\s*\(|import\s+java\.text\.(SimpleDateFormat|\*)""")
        assertFalse(use.containsMatchIn(source.readText()), "VersionTasks.kt uses SimpleDateFormat again; use isoBuildDate()")
    }
}
