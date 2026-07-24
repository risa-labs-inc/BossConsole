package ai.rever.boss.components.workspaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks down the path-quoting that fixes "launch a terminal in a folder whose
 * name has a space/apostrophe" (the `AI Workflow Tools' Exports` bug, where the
 * unquoted `cd …` left the shell stuck at a `quote>` prompt).
 *
 * [ShellPathQuoting]'s two strategies are pure, so both the POSIX and PowerShell
 * branches are verified here regardless of the host OS. The end-to-end POSIX
 * round-trip is asserted too: re-parsing the quoted string yields the original.
 */
class ShellPathQuotingTest {
    // ---- POSIX (macOS/Linux) ----

    @Test
    fun posixQuotesPlainPath() {
        assertEquals("'/Users/foo/bar'", ShellPathQuoting.posix("/Users/foo/bar"))
    }

    @Test
    fun posixQuotesPathWithSpaces() {
        assertEquals("'/Users/foo/AI Workflow Tools'", ShellPathQuoting.posix("/Users/foo/AI Workflow Tools"))
    }

    @Test
    fun posixEscapesEmbeddedApostrophe() {
        // The motivating case: .../AI Workflow Tools' Exports/claude-exports
        val path = "/Users/ananya_work/AI Workflow Tools' Exports/claude-exports"
        val quoted = ShellPathQuoting.posix(path)
        assertEquals("'/Users/ananya_work/AI Workflow Tools'\\'' Exports/claude-exports'", quoted)
        // Round-trip: a POSIX shell parses the quoted string back to the exact path.
        assertEquals(path, posixUnquote(quoted))
    }

    // ---- Windows PowerShell ----

    @Test
    fun powershellQuotesBackslashPathWithSpace() {
        // Backslashes are literal inside PowerShell single quotes (no escaping).
        assertEquals("'C:\\dir with space'", ShellPathQuoting.powershell("C:\\dir with space"))
    }

    @Test
    fun powershellDoublesEmbeddedApostrophe() {
        assertEquals("'a''b'", ShellPathQuoting.powershell("a'b"))
    }

    // ---- CommandProcessor delegation (POSIX host) ----

    @Test
    fun quotePathDelegatesToPosixOnThisHost() {
        // CI runs on macOS/Linux, so the actual resolves to the POSIX strategy.
        assertEquals(ShellPathQuoting.posix("/a b"), CommandProcessor.quotePath("/a b"))
    }

    /**
     * Parse a POSIX single-quoted token back to its literal value: strip the
     * outer quotes and collapse each `'\''` escape sequence back to `'`.
     */
    private fun posixUnquote(token: String): String {
        assertTrue(token.startsWith("'") && token.endsWith("'"))
        return token.substring(1, token.length - 1).replace("'\\''", "'")
    }
}
