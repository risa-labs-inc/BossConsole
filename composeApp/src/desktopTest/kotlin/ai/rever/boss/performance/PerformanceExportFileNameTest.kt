package ai.rever.boss.performance

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceExportFileNameTest {
    @Test
    fun `export file name is ASCII and ISO across default locales`() {
        val original = Locale.getDefault()
        val timestamp = Instant.parse("2026-09-19T13:53:20Z")
        try {
            listOf(
                Locale.US,
                Locale.forLanguageTag("th-TH-u-ca-buddhist"),
                Locale.forLanguageTag("ar-EG"),
            ).forEach { locale ->
                Locale.setDefault(locale)
                assertEquals(
                    "performance-export-20260919-135320.json",
                    performanceExportFileName(timestamp, ZoneOffset.UTC),
                )
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
