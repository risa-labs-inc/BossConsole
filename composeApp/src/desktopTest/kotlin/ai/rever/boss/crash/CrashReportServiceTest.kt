package ai.rever.boss.crash

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#110: `SubmitResult.Error` used to hold a raw exception string, sanitized only at
 * one render call site in [CrashReportDialog]. `Error`'s constructor is now private -
 * [CrashReportService.SubmitResult.Error] is the only way to build one, and it sanitizes
 * before the raw string can ever reach `.message` - so the guarantee is a property of the type,
 * not something every future consumer (a copy button, a toast, a log line) has to remember
 * independently.
 */
class CrashReportServiceTest {
    @Test
    fun `the constructor and generated copy cannot bypass the factory`() {
        val errorClass = CrashReportService.SubmitResult.Error::class.java

        assertTrue(Modifier.isPrivate(errorClass.getDeclaredConstructor(String::class.java).modifiers))
        assertTrue(Modifier.isPrivate(errorClass.getDeclaredMethod("copy", String::class.java).modifiers))
    }

    @Test
    fun submitResultErrorSanitizesAtConstruction() {
        val raw =
            "Failed to submit crash report: Request timeout has expired " +
                "[url=https://api.risaboss.com/functions/v1/crash-report, request_timeout=15000 ms]"
        val error = CrashReportService.SubmitResult.Error(raw)

        assertTrue(
            error.message.contains("Request timeout has expired"),
            "Error.message should retain non-sensitive diagnostic text",
        )
        assertFalse(
            error.message.contains("api.risaboss.com"),
            "Error.message must sanitize URL hosts at construction time",
        )
        assertFalse(
            error.message.contains("crash-report"),
            "Error.message must sanitize URL paths at construction time",
        )
    }

    @Test
    fun `the factory preserves the diagnostic half of the message`() {
        val error = CrashReportService.SubmitResult.Error("Request timeout has expired")

        assertEquals("Request timeout has expired", error.message)
    }

    @Test
    fun `the factory turns a blank message into the placeholder`() {
        val error = CrashReportService.SubmitResult.Error("")

        assertEquals("[no message]", error.message)
    }
}
