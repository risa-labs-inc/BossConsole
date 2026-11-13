package ai.rever.boss.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Open this URL in the browser" must not hand it to a command interpreter.
 *
 * On Windows the old command was `cmd /c start "" <url>`. cmd parses the whole line, and `&`, `|`, `%` and `^`
 * mean something to it. A URL without a space is passed through unquoted, so the cross-device sign-in link, which
 * carries several `&` separated parameters, was cut at the first one. Measured with the exact argument shape:
 * `cmd /c start "" /min C:\Windows\System32\whoami.exe&echo,SECOND_COMMAND_RAN` prints `SECOND_COMMAND_RAN`.
 */
class SystemOpenCommandTest {
    private val shells = setOf("cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe", "sh", "bash")

    private val urlsWithShellPunctuation =
        listOf(
            "https://example.com/a?b=1&c=2",
            "https://example.com/a?x=1&calc",
            "https://example.com/a|b",
            "https://example.com/%COMSPEC%",
            "https://example.com/a^&b",
            "https://example.com/a?redirect=boss%3A%2F%2Fcb&state=1",
        )

    @Test
    fun `Windows does not go through a command interpreter`() {
        for (url in urlsWithShellPunctuation) {
            val command = assertNotNull(SystemOpenCommand.forUrl("Windows 11", url))
            assertTrue(command.first().lowercase() !in shells, "must not start a shell for $url: $command")
            assertFalse(command.any { it.equals("/c", ignoreCase = true) }, "no /c: $command")
            assertFalse(command.any { it.equals("start", ignoreCase = true) }, "no cmd built-in: $command")
        }
    }

    @Test
    fun `a URL reaches the launcher as exactly one argument, unchanged`() {
        for (url in urlsWithShellPunctuation) {
            val command = assertNotNull(SystemOpenCommand.forUrl("Windows 11", url))
            assertEquals(url, command.last(), "the URL must be its own argument")
            assertEquals(1, command.count { it == url }, "and appear once: $command")
            assertEquals(3, command.size, "$command")
        }
    }

    @Test
    fun `Windows launches through the shell's file protocol handler`() {
        val command = assertNotNull(SystemOpenCommand.forUrl("Windows 11", "https://example.com/"))
        assertEquals(listOf("rundll32.exe", "url.dll,FileProtocolHandler", "https://example.com/"), command)
    }

    @Test
    fun `macOS and Linux keep their launchers`() {
        assertEquals(listOf("open", "https://e.test/a&b"), SystemOpenCommand.forUrl("Mac OS X", "https://e.test/a&b"))
        assertEquals(listOf("xdg-open", "http://e.test/a&b"), SystemOpenCommand.forUrl("Linux", "http://e.test/a&b"))
        assertNull(SystemOpenCommand.forUrl("Plan 9", "https://example.com/"))
    }

    @Test
    fun `only a plain http or https URL is handed to the browser`() {
        val refused =
            listOf(
                "file:///C:/Windows/System32/calc.exe",
                "javascript:alert(1)",
                "ms-msdt:/id",
                "\\\\attacker.example\\share\\x.exe",
                "https://example.com/ evil",
                "https://example.com/\nx",
                "calc.exe",
                "",
                "  https://example.com/",
                "https:///nohost",
            )
        for (bad in refused) {
            assertNull(SystemOpenCommand.forUrl("Windows 11", bad), "must refuse: $bad")
        }
    }

    @Test
    fun `a URL with an ampersand in its query survives whole`() {
        // Under cmd this URL was cut at the first `&`, so a working sign-in link opened a broken one.
        val url = "https://api.example.test/auth?session=abc&redirect=boss%3A%2F%2Fcb&state=1"
        assertEquals(url, SystemOpenCommand.forUrl("Windows 11", url)!!.last())
    }
}
