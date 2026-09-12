package ai.rever.boss.plugin.logging

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the file-side threshold, the one thing the console level could not express.
 *
 * Before this, the file received every entry that passed the global level, so "ERROR to disk,
 * INFO to console" was impossible. [BossLogger.writesToFile] is the gate `log()` now consults,
 * split out so it can be asserted without writing a file or reading one back.
 *
 * `BossLogger` is a process-wide object, so each test leaves it with file logging off.
 */
class FileLogThresholdTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "boss-log-threshold-${hashCode()}")

    @AfterTest
    fun tearDown() {
        BossLogger.disableFileLogging()
        dir.deleteRecursively()
    }

    @Test
    fun `nothing goes to the file while file logging is off`() {
        BossLogger.disableFileLogging()

        assertFalse(BossLogger.writesToFile(LogLevel.ERROR))
    }

    @Test
    fun `the file receives its threshold and above`() {
        BossLogger.enableFileLogging(File(dir, "boss.log"), LogLevel.WARN)

        assertFalse(BossLogger.writesToFile(LogLevel.INFO))
        assertTrue(BossLogger.writesToFile(LogLevel.WARN))
        assertTrue(BossLogger.writesToFile(LogLevel.ERROR))
        // OFF is above any threshold, so only the explicit OFF clause in writesToFile can
        // keep it out; this pins the agreement with log()'s console OFF no-op.
        assertFalse(BossLogger.writesToFile(LogLevel.OFF))
    }

    @Test
    fun `enabling without a threshold keeps the old behaviour of everything`() {
        // configure() callers never had a threshold; TRACE is the default so they see no change.
        BossLogger.enableFileLogging(File(dir, "boss.log"))

        assertTrue(BossLogger.writesToFile(LogLevel.TRACE))
    }

    @Test
    fun `disabling resets the threshold for the next enable`() {
        BossLogger.enableFileLogging(File(dir, "boss.log"), LogLevel.ERROR)
        BossLogger.disableFileLogging()
        BossLogger.enableFileLogging(File(dir, "boss.log"))

        assertTrue(BossLogger.writesToFile(LogLevel.DEBUG), "a stale threshold survived disable")
    }

    @Test
    fun `a file threshold can only narrow, never widen past the global level`() {
        // The file gate in log() sits BELOW the early return on the global level, so an entry
        // the console never sees must never reach the file, whatever the file's own threshold
        // says. If the gate were moved above the early return, this DEBUG entry would be queued
        // for a file that accepts DEBUG and above - the exact inversion the fileMinLevel KDoc
        // and AGENTS.md promise cannot happen.
        val debugMessage = "threshold-probe: below the global level"
        val errorMessage = "threshold-probe: at the global level"
        val previousLevel = BossLogger.globalLevel
        try {
            BossLogger.setGlobalLevel(LogLevel.INFO)
            BossLogger.enableFileLogging(File(dir, "boss.log"), LogLevel.DEBUG)
            val logger = ComponentLogger("threshold-probe")
            logger.debug(LogCategory.SYSTEM, debugMessage)
            // Positive control: proves the writer is alive and would have written the DEBUG
            // entry too had the gate been misplaced. Without it, a writer that never works at
            // all would make the absence assertion below pass for the wrong reason.
            logger.error(LogCategory.SYSTEM, errorMessage)

            val file = File(dir, "boss.log")
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (file.length() > 0 && file.readText().contains(errorMessage)) break
                Thread.sleep(100)
            }
            assertTrue(
                file.length() > 0 && file.readText().contains(errorMessage),
                "the ERROR control entry never reached the file; the writer is not working, " +
                    "so the narrow-only assertion below would pass vacuously",
            )
            // The channel is FIFO, so by the time the ERROR has landed any DEBUG entry that
            // was sent at all has already been written too.
            assertFalse(
                file.readText().contains(debugMessage),
                "an entry below the global level reached the file",
            )
        } finally {
            BossLogger.setGlobalLevel(previousLevel)
        }
    }
}
