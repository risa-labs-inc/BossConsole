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
    fun `the cap applies before expanding short paths into placeholders`() {
        val error = CrashReportService.SubmitResult.Error("/a ".repeat(2_000))

        // 1,333 complete three-character tokens fit the independent 4,000-character input bound.
        assertEquals("[PATH] ".repeat(1_333), error.message)
    }

    @Test
    fun `bounding does not expose a partially cut private hostname`() {
        val diagnostic = "timeout ".repeat(499)
        val error = CrashReportService.SubmitResult.Error(diagnostic + "employer.corp.internal")

        assertEquals(diagnostic, error.message)
    }

    @Test
    fun `oversized error retains sanitized diagnostics and omits the tail`() {
        val error =
            CrashReportService.SubmitResult.Error(
                "https://private.example.com/secret " + "boom ".repeat(100_000) + "TAIL_MARKER",
            )
        assertTrue(error.message.length <= 4_000)
        assertFalse(error.message.contains("private.example.com"))
        assertFalse(error.message.contains("TAIL_MARKER"))
    }

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
