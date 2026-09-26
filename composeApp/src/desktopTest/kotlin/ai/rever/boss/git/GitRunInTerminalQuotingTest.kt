package ai.rever.boss.git

import ai.rever.boss.components.workspaces.ShellPathQuoting
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies the exact command built for [GitService.runInTerminal] and, on POSIX hosts,
 * checks that a shell parses it back into the original arguments without evaluating them.
 */
class GitRunInTerminalQuotingTest {
    @Test
    fun `builder emits the expected host quoted command`() {
        val arguments = listOf("status", "O'Reilly", "\$(touch sentinel)", "")
        val expected =
            if (isWindows()) {
                "git 'status' 'O''Reilly' '\$(touch sentinel)' ''"
            } else {
                "git 'status' 'O'\\''Reilly' '\$(touch sentinel)' ''"
            }

        assertEquals(expected, buildGitTerminalCommand(arguments))
    }

    @Test
    fun `built POSIX command yields every argument literally`() {
        val shell = File("/bin/sh")
        assumeTrue(shell.isFile, "requires /bin/sh")

        val directory = Files.createTempDirectory("git-run-in-terminal").toFile()
        val sentinel = directory.resolve("shell-command-ran")
        val arguments =
            listOf(
                "status",
                "'",
                "\"",
                "\\",
                "",
                "\$(touch \"${sentinel.absolutePath}\")",
                "`touch \"${sentinel.absolutePath}\"`",
                "line one\nline two",
                "; touch \"${sentinel.absolutePath}\"",
                "| touch \"${sentinel.absolutePath}\"",
                "&& touch \"${sentinel.absolutePath}\"",
                "> \"${sentinel.absolutePath}\"",
                "\$HOME",
                "\${IFS}",
                "~",
                "*",
                "?",
                "[a-z]",
                "#",
                "'; touch \"${sentinel.absolutePath}\"; '",
            )

        try {
            assertUnquotedGlobsExpand(shell, directory)

            val unsafeArgument = "; touch ${ShellPathQuoting.posix(sentinel.absolutePath)}; #"
            val unsafeCommand = "set -- safe $unsafeArgument; printf '%s\\000' \"\$@\""
            val positiveControl = runShell(shell, unsafeCommand, directory, "unsafe-control")
            assertEquals(0, positiveControl.exitCode, positiveControl.stderr)
            assertTrue(
                sentinel.isFile,
                "the control command should prove the sentinel is writable and observable",
            )
            Files.delete(sentinel.toPath())

            val builtCommand = buildGitTerminalCommand(arguments)
            val quotedArguments = builtCommand.removePrefix("git ")
            assertTrue(builtCommand.startsWith("git "), "the builder should include its git command prefix")
            val script = "set -- $quotedArguments; printf '%s\\000' \"\$@\""
            val result = runShell(shell, script, directory, "quoted-command")

            assertEquals(0, result.exitCode, result.stderr)
            val expectedOutput =
                arguments
                    .joinToString(separator = "\u0000", postfix = "\u0000")
                    .toByteArray(Charsets.UTF_8)
            assertContentEquals(expectedOutput, result.stdout)
            assertFalse(sentinel.exists(), "the shell evaluated one of the git arguments")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertUnquotedGlobsExpand(
        shell: File,
        directory: File,
    ) {
        directory.resolve("a").writeText("glob fixture")
        val command = "set -- * ? [a-z]; printf '%s\\000' \"\$@\""
        val result = runShell(shell, command, directory, "glob-control")
        assertEquals(0, result.exitCode, result.stderr)
        assertContentEquals(
            "a\u0000a\u0000a\u0000".toByteArray(Charsets.UTF_8),
            result.stdout,
            "each unquoted glob must expand in the fixture directory",
        )
    }

    private fun runShell(
        shell: File,
        command: String,
        directory: File,
        name: String,
    ): ShellResult {
        val stdout = directory.resolve(".$name.stdout")
        val stderr = directory.resolve(".$name.stderr")
        val process =
            ProcessBuilder(shell.absolutePath, "-c", command)
                .directory(directory)
                .redirectOutput(stdout)
                .redirectError(stderr)
                .start()

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            val error = if (stderr.exists()) stderr.readText() else ""
            fail("shell command timed out: $error")
        }

        return ShellResult(process.exitValue(), stdout.readBytes(), stderr.readText())
    }

    private data class ShellResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
    )

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
