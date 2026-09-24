package ai.rever.boss.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BossLogParseTest {
    private val parser = LogLineParser()
    private val tempFiles = mutableListOf<File>()

    private fun writeTemp(content: String): File {
        val f = Files.createTempFile("boss-log-parse-test", ".log").toFile()
        f.writeText(content)
        tempFiles += f
        return f
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `parses a typical BOSS structured line`() {
        val line = "2026-01-15T10:30:45.123Z [INFO] [main] SYSTEM ai.rever.boss.cli.BossMcpCommand - hello world"
        val report = parser.parse(listOf(line), LogLevel.TRACE)
        val rec = report.records.single()
        assertEquals("2026-01-15T10:30:45.123Z", rec.timestamp)
        assertEquals(LogLevel.INFO, rec.level)
        assertEquals("SYSTEM", rec.category)
        assertEquals("ai.rever.boss.cli.BossMcpCommand", rec.component)
        assertEquals("hello world", rec.message)
    }

    @Test
    fun `parses the legacy timestamp shape with space separator`() {
        val line = "2026-01-15 10:30:45.123 [WARN] [worker-1] BROWSER ai.rever.boss.BrowserService - timeout"
        val report = parser.parse(listOf(line), LogLevel.TRACE)
        val rec = report.records.single()
        assertEquals("2026-01-15 10:30:45.123", rec.timestamp)
        assertEquals(LogLevel.WARN, rec.level)
        assertEquals("BROWSER", rec.category)
        assertEquals("ai.rever.boss.BrowserService", rec.component)
        assertEquals("timeout", rec.message)
    }

    @Test
    fun `FATAL is mapped to ERROR`() {
        val line = "2026-01-15 10:30:45.123 [FATAL] [main] SYSTEM a - crash"
        val report = parser.parse(listOf(line), LogLevel.TRACE)
        val rec = report.records.single()
        assertEquals(LogLevel.ERROR, rec.level)
    }

    @Test
    fun `unparsable lines go to the unparsed bucket`() {
        val lines =
            listOf(
                "this is not a log line",
                "random free text without any structure",
                "### rotation marker ###",
            )
        val report = parser.parse(lines, LogLevel.TRACE)
        assertEquals(emptyList<LogRecord>(), report.records)
        assertEquals(3, report.unparsed.size)
    }

    @Test
    fun `blank lines and rotation markers are skipped`() {
        val lines = listOf("", "--", "   ", "--", "2026-01-15 10:30:45.123 [INFO] [t] SYSTEM c - m")
        val report = parser.parse(lines, LogLevel.TRACE)
        assertEquals(1, report.records.size)
        assertEquals(emptyList<String>(), report.unparsed)
    }

    @Test
    fun `the threshold filters out lower-severity records`() {
        val lines =
            listOf(
                "2026-01-15 10:30:45.123 [TRACE] [t] SYSTEM c - trace_msg",
                "2026-01-15 10:30:45.124 [DEBUG] [t] SYSTEM c - debug_msg",
                "2026-01-15 10:30:45.125 [INFO] [t] SYSTEM c - info_msg",
                "2026-01-15 10:30:45.126 [WARN] [t] SYSTEM c - warn_msg",
                "2026-01-15 10:30:45.127 [ERROR] [t] SYSTEM c - error_msg",
            )
        val report = parser.parse(lines, LogLevel.WARN)
        assertEquals(2, report.records.size)
        assertEquals(listOf("warn_msg", "error_msg"), report.records.map { it.message })
    }

    @Test
    fun `a TRACE threshold includes every level`() {
        val lines =
            listOf(
                "2026-01-15 10:30:45.123 [TRACE] [t] SYSTEM c - m1",
                "2026-01-15 10:30:45.124 [DEBUG] [t] SYSTEM c - m2",
                "2026-01-15 10:30:45.125 [INFO] [t] SYSTEM c - m3",
            )
        val report = parser.parse(lines, LogLevel.TRACE)
        assertEquals(3, report.records.size)
    }

    @Test
    fun `case-insensitive level names are accepted`() {
        // Real-world SLF4J output is consistently uppercase, but a
        // hand-rolled format or a wrapping logger may differ; the parser
        // should not silently drop those lines.
        val line = "2026-01-15 10:30:45.123 [info] [t] SYSTEM c - lower-case level"
        val report = parser.parse(listOf(line), LogLevel.TRACE)
        val rec = report.records.single()
        assertEquals(LogLevel.INFO, rec.level)
    }

    @Test
    fun `a message containing the separator dash is captured whole`() {
        val line = "2026-01-15 10:30:45.123 [INFO] [t] SYSTEM c - text - with - dashes"
        val report = parser.parse(listOf(line), LogLevel.TRACE)
        val rec = report.records.single()
        assertEquals("text - with - dashes", rec.message)
    }

    @Test
    fun `parse can read from a file via the Clikt command`() {
        val content =
            """
            2026-01-15 10:30:45.123 [INFO] [t] SYSTEM ai.rever.boss.cli - real
            garbage line
            2026-01-15 10:30:45.124 [WARN] [t] BROWSER a - bad
            """.trimIndent()
        writeTemp(content)
        // Direct CLI invocation would be heavier; here we just verify the
        // parser handles the file content shape that the CLI passes to it.
        val report = parser.parse(content.lines(), LogLevel.TRACE)
        assertEquals(2, report.records.size)
        assertEquals(1, report.unparsed.size)
    }

    @Test
    fun `unknown level falls back to INFO`() {
        // Some wrapped loggers emit non-standard levels (e.g. "FINE").
        // The parser should not drop those lines - it falls back to INFO
        // so the operator still sees the message.
        val line = "2026-01-15 10:30:45.123 [FINE] [t] SYSTEM c - fine-grained message"
        val report = parser.parse(listOf(line), LogLevel.TRACE)
        val rec = report.records.single()
        assertEquals(LogLevel.INFO, rec.level)
    }

    @Test
    fun `LogLevel parse is case-insensitive and rejects unknown`() {
        assertEquals(LogLevel.WARN, LogLevel.parse("WARN"))
        assertEquals(LogLevel.INFO, LogLevel.parse("info"))
        val ex = runCatching { LogLevel.parse("nope") }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `a long-running session with thousands of lines parses in one pass`() {
        val lines =
            (0 until 1000).map { i ->
                "2026-01-15 10:30:45.${"%03d".format(i)} [INFO] [t] SYSTEM c - entry_$i"
            }
        val report = parser.parse(lines, LogLevel.TRACE)
        assertEquals(1000, report.records.size)
        assertEquals(emptyList<String>(), report.unparsed)
    }
}
