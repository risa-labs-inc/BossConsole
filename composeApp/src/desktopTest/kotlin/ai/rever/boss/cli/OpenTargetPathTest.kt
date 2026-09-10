package ai.rever.boss.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [CLISecurityValidator.isValidOpenTargetPath], the check applied to a
 * file BOSS is about to read into the editor.
 *
 * It exists because `isValidPath` - the right rules for a path that ends up in a
 * shell command - refused ordinary filenames. A file called `Q&A notes.md` failed
 * the shell-metacharacter test, so double-clicking it logged "Invalid file path
 * (security check failed)" and nothing opened. No shell is involved on this path,
 * so those characters are just characters.
 */
class OpenTargetPathTest {
    @Test
    fun `accepts filenames that isValidPath refused`() {
        // Every one of these is a legal filename and every one was rejected.
        listOf(
            "/Users/me/Documents/Q&A notes.md",
            "/Users/me/scripts/pay\$.sh",
            "/Users/me/notes;draft.md",
            "/Users/me/a|b.txt",
            "/Users/me/back`tick`.md",
            "/Users/me/notes..draft.md",
        ).forEach { path ->
            assertTrue(CLISecurityValidator.isValidOpenTargetPath(path), "should accept $path")
            // The contrast is the point of having two functions.
            assertFalse(CLISecurityValidator.isValidPath(path), "isValidPath was expected to refuse $path")
        }
    }

    @Test
    fun `accepts an ordinary absolute path`() {
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/Users/me/project/src/Main.kt"))
        // Parentheses are fine for both validators; listed here so the contrast
        // above stays a list of characters isValidPath actually rejects.
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/Users/me/Projects (2026)/README.md"))
    }

    @Test
    fun `accepts a path containing dot-dot, which canonicalises away`() {
        // `..` was refused as a traversal defence, which it never was on this
        // path: the caller may pass any absolute path anyway, so the segment adds
        // no reach. Canonicalising is both stricter and correct.
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/Users/me/project/../project/README.md"))
    }

    @Test
    fun `refuses a NUL byte`() {
        // Truncates the path in any native call underneath, so the file that gets
        // opened is not the file that was checked.
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("/Users/me/notes.md\u0000.png"))
    }

    @Test
    fun `refuses blank input`() {
        assertFalse(CLISecurityValidator.isValidOpenTargetPath(""))
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("   "))
    }

    @Test
    fun `the shell-facing validator is unchanged`() {
        // Loosening isValidPath was never the intent - only splitting the two apart. Its
        // rules are still the right rules for a path that reaches a command line; what
        // changed is which paths are held to them.
        assertFalse(CLISecurityValidator.isValidPath("/tmp/a;rm -rf /"))
        assertFalse(CLISecurityValidator.isValidPath("/tmp/../etc/passwd"))
        assertTrue(CLISecurityValidator.isValidPath("/Users/me/project/src/Main.kt"))
    }

    @Test
    fun `a folder or workspace under an ampersand directory is openable`() {
        // The cases that were refused outright. `boss folder ~/work/R&D`, the same folder
        // opened through boss://folder, and `boss workspace ~/work/R&D/ws.json` each hit
        // isValidPath and were dropped with "security check failed" and no window - the
        // same failure this file already records for `Q&A notes.md`, one door along.
        listOf(
            "/Users/me/work/R&D",
            "/Users/me/work/R&D/team.boss-workspace.json",
            "/Users/me/src/c\$/app",
            "/Users/me/a;b/notes",
        ).forEach { path ->
            assertTrue(CLISecurityValidator.isValidOpenTargetPath(path), "should accept $path")
            assertFalse(CLISecurityValidator.isValidPath(path), "isValidPath was expected to refuse $path")
        }
    }

    @Test
    fun `no path that is only read is held to the shell-facing rules`() {
        // The guard, rather than the symptom. isValidOpenTargetPath was added for `boss file`
        // and the other read-only doors kept the shell rules, so the same bug shipped three
        // more times: two in CLICommandHandler (workspace, folder) and one in DeepLinkHandler
        // (boss://folder). Parsed from source because the mistake is invisible at the call
        // site - both names read as "validate this path".
        //
        // Neither file hands a path to a shell. If one ever does, that is the moment to use
        // isValidPath again, and to change this test deliberately rather than by accident.
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
                "could not locate the repository root",
            )
        listOf(
            "composeApp/src/desktopMain/kotlin/ai/rever/boss/cli/CLICommandHandler.kt",
            "composeApp/src/desktopMain/kotlin/ai/rever/boss/utils/DeepLinkHandler.kt",
        ).forEach { relative ->
            val source = File(root, relative)
            assertTrue(source.isFile, "$relative not found at ${source.absolutePath}")
            val offenders =
                source
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> line.contains("CLISecurityValidator.isValidPath(") }
                    .map { (i, line) -> "${relative.substringAfterLast('/')}:${i + 1}: ${line.trim()}" }
            assertEquals(
                emptyList(),
                offenders,
                "these read a path and never shell out, so they must use isValidOpenTargetPath",
            )
        }
    }
}
