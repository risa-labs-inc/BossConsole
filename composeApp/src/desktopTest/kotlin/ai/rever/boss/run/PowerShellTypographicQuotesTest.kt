package ai.rever.boss.run

import ai.rever.boss.components.workspaces.ShellPathQuoting
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PowerShell ends a string at more than the ASCII quote.
 *
 * Its tokenizer reads U+2018, U+2019, U+201A and U+201B as single quotes, and U+201C, U+201D and U+201E as double
 * quotes. Quoting that handled only `'` and `"` therefore let one of those characters close the string early and
 * run whatever followed as the next statement. A git branch name may legally hold `;` and U+2019, and so may a
 * folder, and BOSS interpolates both into the command it types into a PowerShell terminal (`git merge <branch>`,
 * `cd <project>`), so the name of a branch in a repository someone else controls was a command line.
 *
 * The pure tests run everywhere. The last two hand the quoted text to a real PowerShell and read back what it
 * parsed, so they hold the actual grammar and not our model of it; they skip on a host without one.
 */
class PowerShellTypographicQuotesTest {
    private val singleQuotes = listOf('\'', '\u2018', '\u2019', '\u201A', '\u201B')
    private val doubleQuotes = listOf('"', '\u201C', '\u201D', '\u201E')

    @Test
    fun `every PowerShell single quote character is doubled`() {
        for (q in singleQuotes) {
            assertEquals("'a$q${q}b'", ShellPathQuoting.powershell("a${q}b"), "U+%04X".format(q.code))
        }
    }

    @Test
    fun `no single quote character is left unpaired inside the literal`() {
        val payloads =
            listOf(
                "x\u2019;calc;\u2019y",
                "\u2018\u2019\u201A\u201B'",
                "C:\\Users\\O\u2019Brien\\proj",
                "\u201B\u201B\u201B",
                "plain",
            )
        for (payload in payloads) {
            val inner = ShellPathQuoting.powershell(payload).removeSurrounding("'")
            var run = 0
            for (c in inner + "\u0000") {
                if (c in singleQuotes) {
                    run++
                } else {
                    assertTrue(run % 2 == 0, "an odd run of quote characters would end the string early: $payload")
                    run = 0
                }
            }
        }
    }

    @Test
    fun `text without quote characters is only wrapped`() {
        assertEquals("'C:\\dir with space'", ShellPathQuoting.powershell("C:\\dir with space"))
        assertEquals("''", ShellPathQuoting.powershell(""))
    }

    @Test
    fun `every PowerShell double quote character is backtick escaped on Windows`() {
        for (q in doubleQuotes) {
            val escaped = ShellUtils.escapeForDoubleQuotes("a${q}b", forWindows = true)
            assertEquals("a`${q}b", escaped, "U+%04X".format(q.code))
        }
    }

    @Test
    fun `the POSIX branch is untouched by typographic quotes`() {
        assertEquals("a\u201Cb", ShellUtils.escapeForDoubleQuotes("a\u201Cb", forWindows = false))
    }

    @Test
    fun `a real PowerShell reads a single quoted branch name back as one value`() {
        val powershell = powershellOrSkip()
        val hostile =
            listOf(
                "x\u2019; Write-Output INJECTED; \u2019y",
                "x\u2018; Write-Output INJECTED; \u2018y",
                "it's a\u201Bb",
            )
        for (name in hostile) {
            val printed = evaluate(powershell, ShellPathQuoting.powershell(name))
            assertEquals(codePoints(name), printed, "PowerShell must see exactly the text it was given: $name")
        }
    }

    @Test
    fun `a real PowerShell reads a double quoted folder name back as one value`() {
        val powershell = powershellOrSkip()
        val hostile =
            listOf(
                "x\u201D; Write-Output INJECTED; \u201Cy",
                "x\u201E; Write-Output INJECTED; \u201Cy",
            )
        for (name in hostile) {
            val quoted = "\"" + ShellUtils.escapeForDoubleQuotes(name, forWindows = true) + "\""
            val printed = evaluate(powershell, quoted)
            assertEquals(codePoints(name), printed, "PowerShell must see exactly the text it was given: $name")
        }
    }

    private fun codePoints(text: String): String = text.map { it.code }.joinToString(",")

    /** Evaluates [expression] as a PowerShell string and returns its UTF-16 code units, comma separated. */
    private fun evaluate(
        powershell: String,
        expression: String,
    ): String {
        val script =
            "\$ProgressPreference = 'SilentlyContinue'; \$s = $expression; " +
                "[string]::Join(',', (\$s.ToCharArray() | ForEach-Object { [int]\$_ }))"
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process =
            ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("NUL")))
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "PowerShell did not finish")
        return output
            .lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("#<") && !it.startsWith("<") }
            .orEmpty()
            .trim()
    }

    private fun powershellOrSkip(): String {
        assumeTrue(System.getProperty("os.name").lowercase().contains("windows"), "needs Windows PowerShell")
        return "powershell"
    }
}
