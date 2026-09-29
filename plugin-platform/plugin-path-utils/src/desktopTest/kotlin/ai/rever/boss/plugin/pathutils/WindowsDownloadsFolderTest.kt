package ai.rever.boss.plugin.pathutils

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nothing here reads the real registry or environment: `reg.exe` output and the environment
 * are passed in as data, so these run the same on Windows, macOS and Linux.
 */
class WindowsDownloadsFolderTest {
    private val key = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders"
    private val id = "{374DE290-123F-4565-9164-39C4925E467B}"
    private val env = mapOf("USERPROFILE" to "C:\\Users\\someone", "OneDrive" to "C:\\Users\\someone\\OneDrive")

    /** What `reg.exe query <key> /v <id>` prints: a blank line, the key, then the value row. */
    private fun regOutput(
        type: String,
        value: String,
        lineEnd: String = "\r\n",
    ) = listOf("", key, "    $id    $type    $value", "", "").joinToString(lineEnd)

    private fun parse(output: String?) = WindowsDownloadsFolder.fromRegQuery(output, env::get)

    @Nested
    inner class Parsing {
        @Test
        fun `the default REG_EXPAND_SZ value is expanded against the environment`() {
            assertEquals("C:\\Users\\someone\\Downloads", parse(regOutput("REG_EXPAND_SZ", "%USERPROFILE%\\Downloads")))
        }

        @Test
        fun `a folder moved to another drive is read as written`() {
            assertEquals("D:\\Downloads", parse(regOutput("REG_SZ", "D:\\Downloads")))
        }

        @Test
        fun `spaces inside the path are kept`() {
            assertEquals("D:\\My Downloads", parse(regOutput("REG_EXPAND_SZ", "D:\\My Downloads")))
        }

        @Test
        fun `every variable in the value is expanded`() {
            assertEquals(
                "C:\\Users\\someone\\OneDrive\\Downloads",
                parse(regOutput("REG_EXPAND_SZ", "%OneDrive%\\Downloads")),
            )
        }

        @Test
        fun `LF line endings parse the same as CRLF`() {
            assertEquals("D:\\Downloads", parse(regOutput("REG_SZ", "D:\\Downloads", lineEnd = "\n")))
        }

        @Test
        fun `a UNC path is accepted`() {
            assertEquals("\\\\nas\\share\\Downloads", parse(regOutput("REG_SZ", "\\\\nas\\share\\Downloads")))
        }
    }

    @Nested
    inner class Rejection {
        @Test
        fun `no output, as when the value is missing or reg exe failed, gives null`() {
            assertNull(parse(null))
            assertNull(parse(""))
        }

        @Test
        fun `a value of another type gives null`() {
            assertNull(parse(regOutput("REG_DWORD", "0x1")))
        }

        @Test
        fun `an empty value gives null`() {
            assertNull(parse(regOutput("REG_SZ", "")))
        }

        @Test
        fun `a variable that is not set leaves a path that is not absolute, which gives null`() {
            // Expansion leaves unknown names verbatim, as Windows does; the result is not a path.
            assertNull(parse(regOutput("REG_EXPAND_SZ", "%NOT_SET%\\Downloads")))
        }

        @Test
        fun `a relative value gives null rather than a folder under the working directory`() {
            assertNull(parse(regOutput("REG_SZ", "Downloads")))
        }

        @Test
        fun `another value's row is not mistaken for the Downloads one`() {
            val desktop = "    Desktop    REG_EXPAND_SZ    %USERPROFILE%\\Desktop"

            assertNull(parse(listOf("", key, desktop, "").joinToString("\r\n")))
        }
    }

    @Nested
    inner class ProfileGate {
        @Test
        fun `the registry describes the home the JVM runs with when user home is the profile`() {
            assertTrue(WindowsDownloadsFolder.isProfileHome("C:\\Users\\someone", "C:\\Users\\someone"))
            assertTrue(WindowsDownloadsFolder.isProfileHome("C:\\Users\\someone", "c:\\users\\SOMEONE"))
        }

        @Test
        fun `a redirected user home, as in the test tasks, is not described by the registry`() {
            assertFalse(WindowsDownloadsFolder.isProfileHome("C:\\build\\test-home\\desktopTest", "C:\\Users\\someone"))
            assertFalse(WindowsDownloadsFolder.isProfileHome("C:\\Users\\someone", null))
        }
    }

    @Nested
    inner class Process {
        // The running JVM's own launcher stands in for reg.exe: it exists on every OS.
        private val java = File(File(System.getProperty("java.home"), "bin"), "java").path

        @Test
        fun `a successful run returns its standard output`() {
            // --version prints to stdout; -version prints to stderr, which is discarded.
            val output = WindowsDownloadsFolder.runRegQuery(listOf(java, "--version"), timeoutMillis = 60_000)

            assertTrue(
                output.orEmpty().contains(System.getProperty("java.specification.version")),
                "expected the launcher's version on stdout, got: $output",
            )
        }

        @Test
        fun `a missing executable gives null`() {
            val missing = File(File(System.getProperty("java.io.tmpdir"), "boss-no-such-dir"), "reg.exe").path

            assertNull(WindowsDownloadsFolder.runRegQuery(listOf(missing, "query"), timeoutMillis = 60_000))
        }

        @Test
        fun `a failing exit code, as for a missing value, gives null`() {
            assertNull(WindowsDownloadsFolder.runRegQuery(listOf(java, "--no-such-option"), timeoutMillis = 60_000))
        }

        @Test
        fun `a run that outlives the timeout gives null`() {
            // A single-file source program that sleeps far longer than the timeout, so the
            // outcome does not depend on how fast the JVM starts.
            val dir = Files.createTempDirectory("boss-reg-timeout").toFile()
            val sleeper = File(dir, "Sleeper.java")
            sleeper.writeText(
                "class Sleeper { public static void main(String[] a) throws Exception " +
                    "{ Thread.sleep(60_000); } }",
            )

            try {
                val start = System.nanoTime()
                val output = WindowsDownloadsFolder.runRegQuery(listOf(java, sleeper.path), timeoutMillis = 1_000)
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

                assertNull(output)
                // A child that failed at once also gives null; only the timeout takes this long.
                assertTrue(elapsedMillis >= 1_000, "returned after $elapsedMillis ms, before the 1000 ms timeout")
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
