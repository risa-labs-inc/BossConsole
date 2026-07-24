package ai.rever.boss.run

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks down [ShellUtils]: the quoting/escaping/separator logic every runner
 * command and "open terminal here" flow is built from.
 *
 * CI runs this suite on Linux, macOS AND Windows, and [ShellUtils.isWindows] is
 * fixed at class-load from the real OS, so each expectation is written per
 * platform: the POSIX branch is verified on Unix runners and the PowerShell
 * branch on Windows runners.
 */
class ShellUtilsTest {

    private val win = ShellUtils.isWindows
    private val sep = ShellUtils.commandSeparator

    private fun expect(unix: String, windows: String): String = if (win) windows else unix

    // ==================== commandSeparator ====================

    @Test
    fun `separator is and-and on unix, semicolon on windows powershell`() {
        assertEquals(expect(unix = " && ", windows = "; "), sep)
    }

    // ==================== escapeForDoubleQuotes: pass-through ====================

    @Test
    fun `plain text is unchanged`() {
        assertEquals("hello-world_123", ShellUtils.escapeForDoubleQuotes("hello-world_123"))
    }

    @Test
    fun `spaces are unchanged - the caller adds the surrounding quotes`() {
        assertEquals("My Project Dir", ShellUtils.escapeForDoubleQuotes("My Project Dir"))
    }

    @Test
    fun `unicode is unchanged`() {
        assertEquals("docs/日本語 déjà ✨", ShellUtils.escapeForDoubleQuotes("docs/日本語 déjà ✨"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", ShellUtils.escapeForDoubleQuotes(""))
    }

    @Test
    fun `semicolon ampersand and pipe pass through - literal inside double quotes`() {
        // The function only claims safety INSIDE double quotes, where these are literal.
        assertEquals("a; b && c | d", ShellUtils.escapeForDoubleQuotes("a; b && c | d"))
    }

    // ==================== escapeForDoubleQuotes: escaped metacharacters ====================

    @Test
    fun `double quote is escaped`() {
        assertEquals(
            expect(unix = "say \\\"hi\\\"", windows = "say `\"hi`\""),
            ShellUtils.escapeForDoubleQuotes("say \"hi\"")
        )
    }

    @Test
    fun `dollar is escaped to block variable expansion`() {
        assertEquals(
            expect(unix = "\\\$HOME", windows = "`\$HOME"),
            ShellUtils.escapeForDoubleQuotes("\$HOME")
        )
    }

    @Test
    fun `command substitution via dollar-paren is neutralized`() {
        assertEquals(
            expect(unix = "\\\$(rm -rf ~)", windows = "`\$(rm -rf ~)"),
            ShellUtils.escapeForDoubleQuotes("\$(rm -rf ~)")
        )
    }

    @Test
    fun `backtick is escaped to block command substitution`() {
        assertEquals(
            expect(unix = "a\\`whoami\\`b", windows = "a``whoami``b"),
            ShellUtils.escapeForDoubleQuotes("a`whoami`b")
        )
    }

    @Test
    fun `backslash is doubled on unix and literal on powershell`() {
        // PowerShell's escape character is the backtick; backslash is a literal there.
        assertEquals(
            expect(unix = "C:\\\\Temp", windows = "C:\\Temp"),
            ShellUtils.escapeForDoubleQuotes("C:\\Temp")
        )
    }

    @Test
    fun `exclamation is escaped on unix only - history expansion`() {
        assertEquals(
            expect(unix = "deploy\\!", windows = "deploy!"),
            ShellUtils.escapeForDoubleQuotes("deploy!")
        )
    }

    @Test
    fun `escape ordering - a backslash-quote pair does not double-escape`() {
        // Unix: backslash is escaped first (\ -> \\), then the quote (" -> \"),
        // so the two-char input backslash+quote becomes \\ + \" (four chars).
        assertEquals(
            expect(unix = "\\\\\\\"", windows = "\\`\""),
            ShellUtils.escapeForDoubleQuotes("\\\"")
        )
    }

    // ==================== buildCommandWithWorkingDirectory ====================

    @Test
    fun `null working directory returns the command untouched`() {
        assertEquals("ls -la", ShellUtils.buildCommandWithWorkingDirectory("ls -la", null))
    }

    @Test
    fun `blank working directory returns the command untouched`() {
        assertEquals("ls -la", ShellUtils.buildCommandWithWorkingDirectory("ls -la", ""))
        assertEquals("ls -la", ShellUtils.buildCommandWithWorkingDirectory("ls -la", "   "))
    }

    @Test
    fun `working directory is quoted and chained with the platform separator`() {
        assertEquals(
            "cd \"/Users/dev/My Project\"${sep}ls -la",
            ShellUtils.buildCommandWithWorkingDirectory("ls -la", "/Users/dev/My Project")
        )
    }

    @Test
    fun `working directory with a dollar sign cannot expand`() {
        assertEquals(
            expect(
                unix = "cd \"/data/\\\$USER files\" && ls",
                windows = "cd \"/data/`\$USER files\"; ls"
            ),
            ShellUtils.buildCommandWithWorkingDirectory("ls", "/data/\$USER files")
        )
    }

    // ==================== chainCommands ====================

    @Test
    fun `chainCommands joins with the platform separator`() {
        assertEquals("a${sep}b${sep}c", ShellUtils.chainCommands("a", "b", "c"))
    }

    @Test
    fun `chainCommands with a single command returns it unchanged`() {
        assertEquals("./gradlew build", ShellUtils.chainCommands("./gradlew build"))
    }

    @Test
    fun `chainCommands with no commands yields an empty string`() {
        assertEquals("", ShellUtils.chainCommands())
    }
}
