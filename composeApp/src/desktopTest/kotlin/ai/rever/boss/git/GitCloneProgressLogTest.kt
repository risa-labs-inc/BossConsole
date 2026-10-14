package ai.rever.boss.git

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `cloneRepository` merges git's stderr into stdout and logs every line at DEBUG. That line used
 * to be logged raw, so a credential git printed was written to the log even though the starting
 * `url` field is masked. git 2.22 and later strip it from their own `fatal:` messages; git older
 * than that, `GIT_TRACE=1` inherited from the environment, and text a server echoes back do not.
 *
 * The first test runs the exact message the log sink receives. The second is a source test for
 * the one join a unit test cannot reach: that the logging call inside the process loop uses it.
 */
class GitCloneProgressLogTest {
    @Test
    fun `a synthetic fatal git line reaches the log without its credentials`() {
        val line =
            "fatal: unable to access 'https://x-access-token:ghs_CANARY0101@github.com/o/r.git/': " +
                "mirror http://u:CANARY0102@mirror.example:8080/r.git also failed"

        val message = GitService.cloneProgressLogMessage(line)

        assertFalse("CANARY" in message, "a credential reached the log: $message")
        assertEquals(
            "Clone progress: fatal: unable to access 'https://[REDACTED]@github.com/o/r.git/': " +
                "mirror http://[REDACTED]@mirror.example:8080/r.git also failed",
            message,
        )
    }

    @Test
    fun `an ordinary progress line is logged as before`() {
        assertEquals(
            "Clone progress: Receiving objects:  45% (450/1000)",
            GitService.cloneProgressLogMessage("Receiving objects:  45% (450/1000)"),
        )
    }

    @Test
    fun `the clone loop logs each line through the redacting message`() {
        val service = source("composeApp/src/desktopMain/kotlin/ai/rever/boss/git/DesktopGitService.kt")
        assertTrue(
            service.contains("logger.debug(LogCategory.GENERAL, cloneProgressLogMessage(progressLine))"),
            "the clone progress line is no longer logged through cloneProgressLogMessage",
        )
        assertFalse(
            Regex("""logger\.\w+\([^\n]*\$\{?progressLine""").containsMatchIn(service),
            "a logging call interpolates the raw git output line again",
        )
    }

    private fun source(relative: String): String {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
        val file = File(assertNotNull(root, "could not locate the repository root"), relative)
        assertTrue(file.isFile, "missing source file: $relative")
        return file.readText()
    }
}
