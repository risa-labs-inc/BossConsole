package ai.rever.boss.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `boss.bat` must find BOSS.exe to forward `status`, `doctor`, `mcp` and `plugin`. The MSI installs
 * per user into a directory the user may choose, so the installer writes the running app's own
 * launcher path into the script. These pin that rewrite and where the shipped script puts it.
 */
class CLIInstallerScriptTest {
    private val perUser = "%LOCALAPPDATA%\\BOSS\\BOSS.exe"

    private val script =
        listOf(
            "setlocal DisableDelayedExpansion",
            "if defined BOSS_EXE if not exist \"%BOSS_EXE%\" goto :cmd_missing_exe",
            "REM {{INSTALLED_EXE}}",
            "if not defined BOSS_EXE if exist \"$perUser\" set \"BOSS_EXE=$perUser\"",
        ).joinToString("\r\n")

    @Test
    fun `the installed launcher is looked up after an explicit BOSS_EXE and before any guess`() {
        val chosen = "D:\\Apps\\BOSS\\BOSS.exe"
        val installed = bossBatForInstall(script, chosen)

        val lines = installed.split("\r\n")
        assertEquals("if not defined BOSS_EXE if exist \"$chosen\" set \"BOSS_EXE=$chosen\"", lines[2])
        assertEquals(4, lines.size, "the rewrite replaces one line and keeps the script's CRLF line endings")
        assertFalse(installed.contains("{{INSTALLED_EXE}}"))
    }

    @Test
    fun `a percent sign in the install path is escaped so the batch file does not expand it`() {
        val installed = bossBatForInstall(script, "C:\\Users\\a%b\\AppData\\Local\\BOSS\\BOSS.exe")

        assertTrue(installed.contains("if exist \"C:\\Users\\a%%b\\AppData\\Local\\BOSS\\BOSS.exe\""), installed)
    }

    @Test
    fun `without a packaged launcher path the script is left exactly as shipped`() {
        assertEquals(script, bossBatForInstall(script, null))
        assertEquals(script, bossBatForInstall(script, ""))
        assertEquals(script, bossBatForInstall(script, "/Applications/BOSS.app/Contents/MacOS/BOSS"))
    }

    @Test
    fun `the shipped boss_bat carries the marker once, between the BOSS_EXE check and the guesses`() {
        val lines = File(repoRoot(), "scripts/boss.bat").readLines()
        val markers = lines.indices.filter { lines[it].trim() == "REM {{INSTALLED_EXE}}" }
        assertEquals(1, markers.size, "exactly one line for the installer to replace")

        val marker = markers.single()
        val explicitCheck = lines.indexOfFirst { it.startsWith("if defined BOSS_EXE if not exist") }
        val firstGuess = lines.indexOfFirst { it.startsWith("if not defined BOSS_EXE if exist") }
        assertTrue(explicitCheck in 0 until marker, "an explicit BOSS_EXE must still win")
        assertTrue(marker < firstGuess, "the installed launcher must be tried before any guessed location")
        assertTrue(
            lines[firstGuess].contains("%LOCALAPPDATA%\\BOSS\\BOSS.exe"),
            "the first guess must be the MSI's per-user default",
        )
    }

    /** Walks up from the test's working directory to the checkout root. */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "composeApp").isDirectory && File(dir, "version.properties").isFile) return dir
            dir = dir.parentFile
        }
        fail("could not locate the repository root from ${File(".").absolutePath}")
    }
}
