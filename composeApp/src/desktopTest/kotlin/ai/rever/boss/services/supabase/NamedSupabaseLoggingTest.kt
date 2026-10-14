package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.logging.LogEntry
import ai.rever.boss.plugin.logging.LogListener
import io.github.jan.supabase.logging.LogLevel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ai.rever.boss.plugin.logging.LogLevel as BossLogLevel

class NamedSupabaseLoggingTest {
    private var previousGlobalLevel = BossLogLevel.INFO
    private val capturedEntries = mutableListOf<LogEntry>()
    private val listener =
        LogListener { entry ->
            synchronized(capturedEntries) {
                capturedEntries.add(entry)
            }
        }

    @BeforeTest
    fun setUp() {
        capturedEntries.clear()
        previousGlobalLevel = BossLogger.globalLevel
        BossLogger.setGlobalLevel(BossLogLevel.DEBUG)
        BossLogger.clearCategoryLevel(LogCategory.NETWORK)
        BossLogger.addListener(listener)
    }

    @AfterTest
    fun tearDown() {
        BossLogger.removeListener(listener)
        BossLogger.clearCategoryLevel(LogCategory.NETWORK)
        BossLogger.setGlobalLevel(previousGlobalLevel)
        capturedEntries.clear()
    }

    @Test
    fun `processLog redacts JWT access tokens in realtime error messages`() {
        val processor = NamedSupabaseLogging(name = "main", minimum = LogLevel.DEBUG)
        val sensitiveJwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0." +
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val rawMessage =
            """Error while sending message {"event":"phx_join","topic":"realtime:public",""" +
                """"payload":{"access_token":"$sensitiveJwt"}}"""

        processor.processLog(
            level = LogLevel.WARNING,
            tag = "RealtimeImpl",
            throwable = null,
            message = rawMessage,
        )

        val entry = synchronized(capturedEntries) { capturedEntries.lastOrNull() }
        requireNotNull(entry) { "Expected log entry to be captured" }

        assertTrue(entry.message.startsWith("[main] "))
        assertFalse(entry.message.contains(sensitiveJwt), "JWT access token must be redacted from logged message")
        assertTrue(entry.message.contains("Error while sending message"))
    }

    @Test
    fun `processLog redacts credentials in subscribe joinConfigObject`() {
        val processor = NamedSupabaseLogging(name = "store-client", minimum = LogLevel.DEBUG)
        val sensitiveJwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJleHAiOjE3MDAwMDAwMDB9." +
                "abcdefghijklmnopqrstuvwxyz0123456789"
        val rawMessage =
            "Subscribing to channel with body RealtimeJoinConfig(access_token=$sensitiveJwt, postgres_changes=[])"

        processor.processLog(
            level = LogLevel.DEBUG,
            tag = "RealtimeChannelImpl",
            throwable = null,
            message = rawMessage,
        )

        val entry = synchronized(capturedEntries) { capturedEntries.lastOrNull() }
        requireNotNull(entry) { "Expected log entry to be captured" }

        assertTrue(entry.message.startsWith("[store-client] "))
        assertFalse(entry.message.contains(sensitiveJwt), "JWT must be redacted from subscribe log line")
        assertTrue(entry.message.contains("Subscribing to channel with body"))
    }

    @Test
    fun `processLog preserves harmless messages while attributing client name`() {
        val processor = NamedSupabaseLogging(name = "update-watcher", minimum = LogLevel.INFO)
        val rawMessage = "Heartbeat timeout. Trying to reconnect in 7s"

        processor.processLog(
            level = LogLevel.WARNING,
            tag = "RealtimeImpl",
            throwable = null,
            message = rawMessage,
        )

        val entry = synchronized(capturedEntries) { capturedEntries.lastOrNull() }
        requireNotNull(entry) { "Expected log entry to be captured" }

        assertEquals("[update-watcher] Heartbeat timeout. Trying to reconnect in 7s", entry.message)
    }

    @Test
    fun `processLog respects minimum log level`() {
        val processor = NamedSupabaseLogging(name = "main", minimum = LogLevel.WARNING)

        processor.processLog(
            level = LogLevel.DEBUG,
            tag = "RealtimeImpl",
            throwable = null,
            message = "Transient trace event",
        )

        assertTrue(synchronized(capturedEntries) { capturedEntries.isEmpty() })
    }
}
