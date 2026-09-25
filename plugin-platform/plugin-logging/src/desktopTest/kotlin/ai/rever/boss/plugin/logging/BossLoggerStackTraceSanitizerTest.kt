package ai.rever.boss.plugin.logging

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BossLoggerStackTraceSanitizerTest {
    private var previousLevel: LogLevel = LogLevel.INFO

    @BeforeTest
    fun setUp() {
        previousLevel = BossLogger.globalLevel
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.clearLogs()
        BossLogger.disableFileLogging()
    }

    @AfterTest
    fun tearDown() {
        BossLogger.setGlobalLevel(previousLevel)
        BossLogger.clearLogs()
        BossLogger.disableFileLogging()
    }

    @Test
    fun `listener received throwable does not leak raw stack frame filenames`() {
        val received = mutableListOf<LogEntry>()
        val listener = LogListener { received.add(it) }
        BossLogger.addListener(listener)
        try {
            val ex = RuntimeException("failed to load credentials")
            ex.stackTrace =
                arrayOf(
                    StackTraceElement("com.example.Secrets", "load", "/Users/ci/keys.pem", 12),
                    StackTraceElement("com.example.App", "main", "App.kt", 42),
                )
            BossLogger.forComponent("SecretsLoader").error(LogCategory.GENERAL, "Failed to load", error = ex)

            val entry = received.single()
            val trace = entry.error?.stackTraceToString().orEmpty()

            assertFalse(
                trace.contains("/Users/ci/keys.pem"),
                "trace leaked raw stack frame filename: $trace",
            )
            assertTrue(
                trace.contains("[PATH]:12") || trace.contains("([PATH]:12)"),
                "trace did not sanitize frame filename to [PATH]: $trace",
            )
            assertTrue(
                trace.contains("App.kt:42"),
                "trace did not preserve standard source file name: $trace",
            )
            assertTrue(
                trace.contains("java.lang.RuntimeException"),
                "exception class name was erased from stack trace: $trace",
            )
            assertIs<SanitizedThrowable>(entry.error)
        } finally {
            BossLogger.removeListener(listener)
        }
    }

    @Test
    fun `listener received throwable masks Windows paths in stack frames`() {
        val received = mutableListOf<LogEntry>()
        val listener = LogListener { received.add(it) }
        BossLogger.addListener(listener)
        try {
            val ex = IllegalArgumentException("invalid path")
            ex.stackTrace =
                arrayOf(
                    StackTraceElement("com.example.Config", "parse", "C:\\Users\\secret\\keys.pem", 99),
                )
            BossLogger.forComponent("ConfigParser").error(LogCategory.GENERAL, "Config error", error = ex)

            val entry = received.single()
            val trace = entry.error?.stackTraceToString().orEmpty()

            assertFalse(trace.contains("C:\\Users\\secret\\keys.pem"), "leaked Windows path: $trace")
            assertTrue(trace.contains("[PATH]:99") || trace.contains("([PATH]:99)"), "did not sanitize: $trace")
            assertTrue(trace.contains("java.lang.IllegalArgumentException"), "erased exception type: $trace")
        } finally {
            BossLogger.removeListener(listener)
        }
    }

    @Test
    fun `sanitizeFileName handles paths and source file names correctly`() {
        assertNull(LogSanitizer.sanitizeFileName(null))
        assertEquals("", LogSanitizer.sanitizeFileName(""))
        assertEquals("   ", LogSanitizer.sanitizeFileName("   "))
        assertEquals("BossLogger.kt", LogSanitizer.sanitizeFileName("BossLogger.kt"))
        assertEquals("Main.java", LogSanitizer.sanitizeFileName("Main.java"))
        assertEquals("[PATH]", LogSanitizer.sanitizeFileName("/Users/ci/keys.pem"))
        assertEquals("[PATH]", LogSanitizer.sanitizeFileName("C:\\Users\\ci\\keys.pem"))
        assertEquals("[PATH]", LogSanitizer.sanitizeFileName("dir/Nested.kt"))
        assertEquals("[PATH]", LogSanitizer.sanitizeFileName("dir\\Nested.kt"))
        assertEquals("[EMAIL]", LogSanitizer.sanitizeFileName("user@example.com"))
        assertEquals("ghp...890.kt", LogSanitizer.sanitizeFileName("ghp_12345678901234567890.kt"))
    }

    @Test
    fun `sanitizeStackTraceElement preserves clean frames and rewires dirty ones`() {
        val cleanFrame = StackTraceElement("com.example.App", "main", "App.kt", 10)
        assertSame(cleanFrame, LogSanitizer.sanitizeStackTraceElement(cleanFrame))

        val nullFileFrame = StackTraceElement("com.example.App", "nativeMethod", null, -2)
        assertSame(nullFileFrame, LogSanitizer.sanitizeStackTraceElement(nullFileFrame))

        val dirtyFrame = StackTraceElement("com.example.Secrets", "read", "/Users/ci/keys.pem", 15)
        val sanitized = LogSanitizer.sanitizeStackTraceElement(dirtyFrame)
        assertEquals("com.example.Secrets", sanitized.className)
        assertEquals("read", sanitized.methodName)
        assertEquals("[PATH]", sanitized.fileName)
        assertEquals(15, sanitized.lineNumber)
    }

    @Test
    fun `sanitizeThrowable preserves original class name in toString and trace`() {
        val raw = java.io.FileNotFoundException("missing: /Users/ci/secret.txt")
        raw.stackTrace =
            arrayOf(
                StackTraceElement("com.example.IO", "open", "/Users/ci/keys.pem", 20),
            )

        val sanitized = LogSanitizer.sanitizeThrowable(raw)
        assertNotNull(sanitized)
        assertEquals("missing: [PATH]", sanitized.message)
        assertEquals("java.io.FileNotFoundException", (sanitized as SanitizedThrowable).originalClassName)
        assertEquals("java.io.FileNotFoundException: missing: [PATH]", sanitized.toString())

        val trace = sanitized.stackTraceToString()
        assertTrue(trace.startsWith("java.io.FileNotFoundException: missing: [PATH]"))
        assertFalse(trace.contains("/Users/ci/keys.pem"))
        assertTrue(trace.contains("[PATH]:20") || trace.contains("([PATH]:20)"))
    }

    @Test
    fun `sanitizeThrowable handles null error and null message`() {
        assertNull(LogSanitizer.sanitizeThrowable(null))

        val npe = NullPointerException(null as String?)
        val sanitized = LogSanitizer.sanitizeThrowable(npe)
        assertNotNull(sanitized)
        assertNull(sanitized.message)
        assertEquals("java.lang.NullPointerException", sanitized.toString())
    }

    @Test
    fun `sanitizeThrowable breaks cyclic cause chains without StackOverflowError`() {
        val e1 = RuntimeException("cycle 1: /Users/ci/keys.pem")
        val e2 = IllegalStateException("cycle 2: /Users/ci/secret.pem")
        e1.initCause(e2)
        e2.initCause(e1)

        val sanitized = LogSanitizer.sanitizeThrowable(e1)
        assertNotNull(sanitized)
        assertEquals("cycle 1: [PATH]", sanitized.message)
        val sanitizedCause = sanitized.cause
        assertNotNull(sanitizedCause)
        assertEquals("cycle 2: [PATH]", sanitizedCause.message)
        assertNull(sanitizedCause.cause, "cycle was not safely terminated")
    }

    @Test
    fun `sanitizeThrowable preserves and sanitizes suppressed exceptions`() {
        val primary = RuntimeException("primary")
        val suppressed = java.io.IOException("/Users/ci/keys.pem")
        suppressed.stackTrace =
            arrayOf(
                StackTraceElement("com.example.Suppressed", "close", "/Users/ci/keys.pem", 5),
            )
        primary.addSuppressed(suppressed)

        val sanitized = LogSanitizer.sanitizeThrowable(primary)
        assertNotNull(sanitized)
        assertEquals(1, sanitized.suppressed.size)
        val sanitizedSuppressed = sanitized.suppressed.first()
        assertEquals("[PATH]", sanitizedSuppressed.message)
        assertEquals("[PATH]", sanitizedSuppressed.stackTrace.first().fileName)
        assertEquals("java.io.IOException", (sanitizedSuppressed as SanitizedThrowable).originalClassName)
    }

    @Test
    fun `recentLogs stores sanitized throwable`() {
        val ex = RuntimeException("sensitive path: /Users/ci/keys.pem")
        ex.stackTrace =
            arrayOf(
                StackTraceElement("com.example.Secrets", "fetch", "/Users/ci/keys.pem", 8),
            )
        BossLogger.forComponent("RecentLogTest").error(LogCategory.GENERAL, "Error occurred", error = ex)

        val recent = BossLogger.getRecentLogs(limit = 1).single()
        val error = recent.error
        assertNotNull(error)
        val trace = error.stackTraceToString()
        assertFalse(trace.contains("/Users/ci/keys.pem"), "recentLogs leaked raw path in trace: $trace")
        assertTrue(trace.contains("[PATH]"), "recentLogs did not sanitize path: $trace")
    }

    @Test
    fun `already sanitized throwable is returned directly`() {
        val raw = RuntimeException("test")
        val sanitized1 = LogSanitizer.sanitizeThrowable(raw)
        val sanitized2 = LogSanitizer.sanitizeThrowable(sanitized1)
        assertSame(sanitized1, sanitized2)
    }
}
