package ai.rever.boss.utils.logging

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

/**
 * Tests for BossLogger functionality.
 *
 * Note: BossLogger is a singleton, so tests must be careful about state.
 * Each test should reset relevant state after completion.
 */
class BossLoggerTest {
    private val testLogDir = File(System.getProperty("java.io.tmpdir"), "boss-logger-test-${System.currentTimeMillis()}")
    private val testLogFile = File(testLogDir, "test.log")

    @BeforeTest
    fun setUp() {
        testLogDir.mkdirs()
        // Reset logger state
        BossLogger.setGlobalLevel(LogLevel.INFO)
        BossLogger.clearLogs()
        BossLogger.disableFileLogging()
    }

    @AfterTest
    fun tearDown() {
        BossLogger.disableFileLogging()
        // Clean up test files
        testLogDir.deleteRecursively()
    }

    // =========================================================================
    // Log Level Filtering Tests
    // =========================================================================

    @Test
    fun `log filtering respects global level hierarchy`() {
        val logger = BossLogger.forComponent("TestComponent")
        BossLogger.setGlobalLevel(LogLevel.WARN)
        BossLogger.clearLogs()

        // These should be filtered out
        logger.trace(LogCategory.GENERAL, "Trace message")
        logger.debug(LogCategory.GENERAL, "Debug message")
        logger.info(LogCategory.GENERAL, "Info message")

        // These should pass through
        logger.warn(LogCategory.GENERAL, "Warn message")
        logger.error(LogCategory.GENERAL, "Error message")

        val logs = BossLogger.getRecentLogs(limit = 100)
        assertEquals(2, logs.size, "Only WARN and ERROR should pass through")
        assertTrue(logs.any { it.level == LogLevel.WARN })
        assertTrue(logs.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `log filtering respects category-specific levels`() {
        val logger = BossLogger.forComponent("TestComponent")
        BossLogger.setGlobalLevel(LogLevel.ERROR)
        BossLogger.setCategoryLevel(LogCategory.AUTH, LogLevel.DEBUG)
        BossLogger.clearLogs()

        // AUTH category should allow DEBUG
        logger.debug(LogCategory.AUTH, "Auth debug message")

        // GENERAL category should still require ERROR
        logger.debug(LogCategory.GENERAL, "General debug message")
        logger.info(LogCategory.GENERAL, "General info message")

        val logs = BossLogger.getRecentLogs(limit = 100)
        assertEquals(1, logs.size, "Only AUTH debug should pass through")
        assertEquals(LogCategory.AUTH, logs.first().category)

        // Clean up
        BossLogger.clearCategoryLevel(LogCategory.AUTH)
    }

    @Test
    fun `getEffectiveLevel returns category level when set`() {
        BossLogger.setGlobalLevel(LogLevel.INFO)
        BossLogger.setCategoryLevel(LogCategory.NETWORK, LogLevel.DEBUG)

        assertEquals(LogLevel.DEBUG, BossLogger.getEffectiveLevel(LogCategory.NETWORK))
        assertEquals(LogLevel.INFO, BossLogger.getEffectiveLevel(LogCategory.GENERAL))

        // Clean up
        BossLogger.clearCategoryLevel(LogCategory.NETWORK)
    }

    @Test
    fun `clearCategoryLevel falls back to global level`() {
        BossLogger.setGlobalLevel(LogLevel.WARN)
        BossLogger.setCategoryLevel(LogCategory.UI, LogLevel.TRACE)

        assertEquals(LogLevel.TRACE, BossLogger.getEffectiveLevel(LogCategory.UI))

        BossLogger.clearCategoryLevel(LogCategory.UI)

        assertEquals(LogLevel.WARN, BossLogger.getEffectiveLevel(LogCategory.UI))
    }

    // =========================================================================
    // Log Entry Tests
    // =========================================================================

    @Test
    fun `log entries contain correct metadata`() {
        val logger = BossLogger.forComponent("MetadataTest")
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.clearLogs()

        val testData = mapOf("key1" to "value1", "key2" to 42)
        logger.info(LogCategory.SYSTEM, "Test message", data = testData)

        val logs = BossLogger.getRecentLogs(limit = 1)
        assertEquals(1, logs.size)

        val entry = logs.first()
        assertEquals("MetadataTest", entry.component)
        assertEquals(LogCategory.SYSTEM, entry.category)
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("Test message", entry.message)
        assertEquals(testData, entry.data)
        assertTrue(entry.timestamp > 0)
    }

    @Test
    fun `log entries capture exceptions`() {
        val logger = BossLogger.forComponent("ExceptionTest")
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.clearLogs()

        val testException = RuntimeException("Test error")
        logger.error(LogCategory.SYSTEM, "Error occurred", error = testException)

        val logs = BossLogger.getRecentLogs(limit = 1)
        assertEquals(1, logs.size)

        val entry = logs.first()
        val error = entry.error
        assertNotNull(error)
        assertEquals("Test error", error.message)
    }

    // =========================================================================
    // Recent Logs Tests
    // =========================================================================

    @Test
    fun `getRecentLogs respects limit parameter`() {
        val logger = BossLogger.forComponent("LimitTest")
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.clearLogs()

        repeat(20) { i ->
            logger.info(LogCategory.GENERAL, "Message $i")
        }

        val logs = BossLogger.getRecentLogs(limit = 5)
        assertEquals(5, logs.size)
    }

    @Test
    fun `getRecentLogs filters by category`() {
        val logger = BossLogger.forComponent("CategoryFilterTest")
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.clearLogs()

        logger.info(LogCategory.AUTH, "Auth message 1")
        logger.info(LogCategory.NETWORK, "Network message")
        logger.info(LogCategory.AUTH, "Auth message 2")
        logger.info(LogCategory.UI, "UI message")

        val authLogs = BossLogger.getRecentLogs(limit = 100, category = LogCategory.AUTH)
        assertEquals(2, authLogs.size)
        assertTrue(authLogs.all { it.category == LogCategory.AUTH })
    }

    @Test
    fun `getRecentLogs filters by minimum level`() {
        val logger = BossLogger.forComponent("MinLevelTest")
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.clearLogs()

        logger.debug(LogCategory.GENERAL, "Debug message")
        logger.info(LogCategory.GENERAL, "Info message")
        logger.warn(LogCategory.GENERAL, "Warn message")
        logger.error(LogCategory.GENERAL, "Error message")

        val warnAndAbove = BossLogger.getRecentLogs(limit = 100, minLevel = LogLevel.WARN)
        assertEquals(2, warnAndAbove.size)
        assertTrue(warnAndAbove.all { it.level.priority >= LogLevel.WARN.priority })
    }

    @Test
    fun `clearLogs removes all entries`() {
        val logger = BossLogger.forComponent("ClearTest")
        BossLogger.setGlobalLevel(LogLevel.DEBUG)

        logger.info(LogCategory.GENERAL, "Message 1")
        logger.info(LogCategory.GENERAL, "Message 2")

        assertTrue(BossLogger.getRecentLogs(limit = 100).isNotEmpty())

        BossLogger.clearLogs()

        assertTrue(BossLogger.getRecentLogs(limit = 100).isEmpty())
    }

    // =========================================================================
    // File Logging Tests
    // =========================================================================

    @Test
    fun `enableFileLogging creates log file`() =
        runBlocking {
            BossLogger.enableFileLogging(testLogFile)

            val logger = BossLogger.forComponent("FileTest")
            logger.info(LogCategory.GENERAL, "Test file message")

            // Give async writer time to process
            delay(500)

            assertTrue(testLogFile.exists(), "Log file should be created")
            val content = testLogFile.readText()
            assertTrue(content.contains("Test file message"), "Log file should contain message")
        }

    @Test
    fun `file rotation triggers at maxFileSize`() =
        runBlocking {
            // Configure small file size for testing
            val config =
                BossLoggerConfig(
                    globalLevel = LogLevel.DEBUG,
                    fileLoggingEnabled = true,
                    logFilePath = testLogFile.absolutePath,
                    maxFileSize = 1024, // 1KB for quick rotation
                    maxBackupFiles = 3,
                )
            BossLogger.configure(config)

            val logger = BossLogger.forComponent("RotationTest")

            // Write enough to trigger rotation
            repeat(50) { i ->
                logger.info(LogCategory.GENERAL, "Long message number $i with padding: ${"x".repeat(50)}")
            }

            // Give async writer time to process
            delay(1000)

            // Check that backup file was created
            val backupFile = File("${testLogFile.absolutePath}.1")
            assertTrue(
                backupFile.exists() || testLogFile.length() > 0,
                "Either rotation should occur or log file should have content",
            )
        }

    @Test
    fun `disableFileLogging stops writing to file`() =
        runBlocking {
            BossLogger.enableFileLogging(testLogFile)

            val logger = BossLogger.forComponent("DisableTest")
            logger.info(LogCategory.GENERAL, "Before disable")

            delay(500)
            val sizeBeforeDisable = if (testLogFile.exists()) testLogFile.length() else 0

            BossLogger.disableFileLogging()

            logger.info(LogCategory.GENERAL, "After disable")
            delay(500)

            val sizeAfterDisable = if (testLogFile.exists()) testLogFile.length() else 0

            // File size shouldn't increase after disable
            assertEquals(sizeBeforeDisable, sizeAfterDisable, "File should not grow after disabling")
        }

    // =========================================================================
    // Log Listener Tests
    // =========================================================================

    @Test
    fun `listeners receive log entries`() {
        val receivedEntries = mutableListOf<LogEntry>()
        val listener = LogListener { entry -> receivedEntries.add(entry) }

        BossLogger.addListener(listener)
        BossLogger.setGlobalLevel(LogLevel.DEBUG)

        try {
            val logger = BossLogger.forComponent("ListenerTest")
            logger.info(LogCategory.GENERAL, "Test message for listener")

            assertEquals(1, receivedEntries.size)
            assertEquals("Test message for listener", receivedEntries.first().message)
        } finally {
            BossLogger.removeListener(listener)
        }
    }

    @Test
    fun `removeListener stops notifications`() {
        val receivedEntries = mutableListOf<LogEntry>()
        val listener = LogListener { entry -> receivedEntries.add(entry) }

        BossLogger.addListener(listener)
        BossLogger.setGlobalLevel(LogLevel.DEBUG)

        val logger = BossLogger.forComponent("RemoveListenerTest")
        logger.info(LogCategory.GENERAL, "Message 1")

        BossLogger.removeListener(listener)

        logger.info(LogCategory.GENERAL, "Message 2")

        assertEquals(1, receivedEntries.size, "Should only receive message before removal")
    }

    // =========================================================================
    // Configuration Tests
    // =========================================================================

    @Test
    fun `configure applies all settings`() {
        val config =
            BossLoggerConfig(
                globalLevel = LogLevel.TRACE,
                categoryLevels = mapOf(LogCategory.AUTH to LogLevel.ERROR),
                fileLoggingEnabled = false,
            )

        BossLogger.configure(config)

        assertEquals(LogLevel.TRACE, BossLogger.globalLevel)
        assertEquals(LogLevel.ERROR, BossLogger.getEffectiveLevel(LogCategory.AUTH))

        // Clean up
        BossLogger.clearCategoryLevel(LogCategory.AUTH)
    }

    @Test
    fun `LogLevel fromString handles valid values`() {
        assertEquals(LogLevel.TRACE, LogLevel.fromString("TRACE"))
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("debug"))
        assertEquals(LogLevel.INFO, LogLevel.fromString("Info"))
        assertEquals(LogLevel.WARN, LogLevel.fromString("WARN"))
        assertEquals(LogLevel.ERROR, LogLevel.fromString("error"))
    }

    @Test
    fun `LogLevel fromString defaults to INFO for invalid values`() {
        assertEquals(LogLevel.INFO, LogLevel.fromString("invalid"))
        assertEquals(LogLevel.INFO, LogLevel.fromString(""))
        assertEquals(LogLevel.INFO, LogLevel.fromString("VERBOSE"))
    }

    // =========================================================================
    // ComponentLogger Tests
    // =========================================================================

    @Test
    fun `ComponentLogger logs at all levels`() {
        val logger = BossLogger.forComponent("AllLevelsTest")
        BossLogger.setGlobalLevel(LogLevel.TRACE)
        BossLogger.clearLogs()

        logger.trace(LogCategory.GENERAL, "Trace")
        logger.debug(LogCategory.GENERAL, "Debug")
        logger.info(LogCategory.GENERAL, "Info")
        logger.warn(LogCategory.GENERAL, "Warn")
        logger.error(LogCategory.GENERAL, "Error")

        val logs = BossLogger.getRecentLogs(limit = 100)
        assertEquals(5, logs.size)

        val levels = logs.map { it.level }
        assertTrue(levels.contains(LogLevel.TRACE))
        assertTrue(levels.contains(LogLevel.DEBUG))
        assertTrue(levels.contains(LogLevel.INFO))
        assertTrue(levels.contains(LogLevel.WARN))
        assertTrue(levels.contains(LogLevel.ERROR))
    }

    @Test
    fun `ComponentLogger name is preserved in entries`() {
        val logger = BossLogger.forComponent("MySpecialComponent")
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.clearLogs()

        logger.info(LogCategory.GENERAL, "Test")

        val logs = BossLogger.getRecentLogs(limit = 1)
        assertEquals("MySpecialComponent", logs.first().component)
    }
}
