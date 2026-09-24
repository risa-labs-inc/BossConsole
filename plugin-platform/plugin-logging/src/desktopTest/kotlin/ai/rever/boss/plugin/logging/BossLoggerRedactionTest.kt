package ai.rever.boss.plugin.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Regression coverage for the central redaction in [BossLogger.log].
 *
 * The append path produces ONE sanitized entry and every downstream channel -
 * recent logs, listeners, the SLF4J line and the file writer - renders from it.
 * These tests pin that a credential planted in the message text, in a stack
 * frame or in an exception's cause chain cannot reach any of those channels
 * raw, whichever call site forgot to mask it.
 */
class BossLoggerRedactionTest {
    private val testLogDir =
        File(System.getProperty("java.io.tmpdir"), "boss-logger-redaction-${System.nanoTime()}")
    private val testLogFile = File(testLogDir, "redaction.log")

    private lateinit var listAppender: ListAppender<ILoggingEvent>

    @BeforeTest
    fun setUp() {
        testLogDir.mkdirs()
        BossLogger.setGlobalLevel(LogLevel.TRACE)
        BossLogger.clearLogs()
        BossLogger.disableFileLogging()

        // slf4j-api is an `implementation` dep with no binding on the production
        // classpath, so the SLF4J line has no sink a test could read. desktopTest
        // carries logback-classic purely to give that line a capturable sink:
        // BossLogger logs through the "ai.rever.boss" logger, so a ListAppender on
        // the same name sees the exact string handed to SLF4J.
        val slf4jTarget = LoggerFactory.getLogger("ai.rever.boss") as LogbackLogger
        listAppender = ListAppender<ILoggingEvent>().apply { start() }
        slf4jTarget.addAppender(listAppender)
    }

    @AfterTest
    fun tearDown() {
        (LoggerFactory.getLogger("ai.rever.boss") as LogbackLogger).detachAppender(listAppender)
        listAppender.stop()
        BossLogger.disableFileLogging()
        BossLogger.setGlobalLevel(LogLevel.INFO)
        testLogDir.deleteRecursively()
    }

    @Test
    fun `a credential in the message text reaches neither the SLF4J line nor the file line`() =
        runBlocking {
            BossLogger.enableFileLogging(testLogFile)

            val secret = "ghp_${"x".repeat(36)}"
            BossLogger.forComponent("RedactionProbe").info(LogCategory.AUTH, "login presented $secret")

            // The file writer consumes the channel asynchronously.
            delay(500)

            val slf4jLines = listAppender.list.joinToString("\n") { it.formattedMessage }
            assertFalse(slf4jLines.contains(secret), "raw credential reached the SLF4J line")
            assertTrue(slf4jLines.contains("login presented"), "sanitized SLF4J line lost the prose")

            val fileText = testLogFile.readText()
            assertFalse(fileText.contains(secret), "raw credential reached the file line")
            assertTrue(fileText.contains("login presented"), "sanitized entry itself should still be written")
        }

    @Test
    fun `the file writer renders stack frames through the sanitizer`() =
        runBlocking {
            BossLogger.enableFileLogging(testLogFile)

            // A frame can carry a path - hand-built or native traces embed a file name -
            // and a `Caused by:` line carries an exception message. Both used to be
            // appended raw while only `error.message` was sanitized.
            val secret = "ghp_${"y".repeat(36)}"
            val error =
                RuntimeException(
                    "load failed for $secret",
                    RuntimeException("read /Users/ci/.aws/credentials denied"),
                )
            error.stackTrace =
                arrayOf(
                    StackTraceElement("com.example.Secrets", "load", "/Users/ci/keys.pem", 12),
                    StackTraceElement("com.example.App", "main", "App.kt", 5),
                )

            BossLogger.forComponent("RedactionProbe").error(LogCategory.SYSTEM, "credential load failed", error = error)
            delay(500)

            val fileText = testLogFile.readText()
            assertFalse(fileText.contains(secret), "raw credential in the error message reached the file")
            assertFalse(fileText.contains("/Users/ci/keys.pem"), "raw path inside a frame reached the file")
            assertFalse(
                fileText.contains("/Users/ci/.aws/credentials"),
                "raw path in a cause message reached the file",
            )
            assertTrue(fileText.contains("Caused by"), "the sanitized trace should keep its cause chain")
            assertTrue(fileText.contains("com.example.App.main"), "sanitization should not eat ordinary frames")
        }

    @Test
    fun `a listener receives a throwable whose rendered trace holds only sanitized messages`() {
        val received = mutableListOf<LogEntry>()
        val listener = LogListener { received.add(it) }
        BossLogger.addListener(listener)
        try {
            val secret = "ghp_${"z".repeat(36)}"
            val original =
                RuntimeException(
                    "exchange failed with $secret",
                    IllegalStateException("token=$secret in /Users/ci/.ssh/id_rsa"),
                )
            BossLogger.forComponent("RedactionProbe").error(LogCategory.AUTH, "token exchange failed", error = original)

            // The listener's entry must not be the original throwable: anything that
            // prints it - a Console panel, a crash reporter - would otherwise render
            // the unsanitized message and every `Caused by:` line.
            val error = assertNotNull(received.single().error)
            val rendered = error.stackTraceToString()
            assertFalse(rendered.contains(secret), "listener-visible trace leaked the raw exception message")
            assertFalse(
                rendered.contains("/Users/ci/.ssh/id_rsa"),
                "listener-visible trace leaked the raw cause message",
            )
            assertTrue(rendered.contains("Caused by"), "the sanitized throwable should preserve the cause chain")
        } finally {
            BossLogger.removeListener(listener)
        }
    }
}
