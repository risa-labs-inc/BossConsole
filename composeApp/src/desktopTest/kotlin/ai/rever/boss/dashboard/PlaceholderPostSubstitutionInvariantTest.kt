package ai.rever.boss.dashboard

import ai.rever.boss.components.workspaces.CommandProcessor
import ai.rever.boss.components.workspaces.ShellPathQuoting
import ai.rever.boss.components.workspaces.ShellQuoteRegions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Post-substitution invariants of [WorkspacePlaceholders.processPlaceholders]:
 * once a value is substituted in, it is INERT DATA.
 *
 * The pipeline used to be four sequential whole-string `replace` passes, so every pass
 * re-scanned what earlier passes had substituted in: a project path legitimately named
 * `{claudeContinueFlag}` (braces are legal in POSIX and Windows filenames) came out as
 * `--continue`, and `{projectPath}`'s own raw second stage re-expanded a value that
 * contained `{projectPath}` into a doubled path. A value is data - it must arrive
 * byte-intact and must never be expanded again.
 *
 * The other half is the execution boundary. `quoteProjectPath = true` marks shell command
 * content (a terminal tab's `initialCommand`), which is typed into a live shell. Only
 * `{projectPath}` was substituted shell-quoted there; `{currentFile}` and `{gitRemoteUrl}`
 * - the latter the captured output of `git remote get-url origin`, validated only by
 * prefix - were inserted raw, so a value carrying `$()`, backticks, a semicolon, a
 * newline or a glob reached the shell live. In shell context those data placeholders are
 * now quoted like `{projectPath}` always was. `{claudeContinueFlag}` stays raw: its value
 * is app-generated ("--continue" or empty), never data.
 *
 * Quoted expectations go through the platform-aware [CommandProcessor.quotePath] (POSIX
 * single-quote literal on macOS/Linux, PowerShell single-quote literal on Windows), so
 * the assertions hold on any host. Templates avoid ` && ` so the final separator
 * normalization, which rewrites it only on Windows, cannot perturb the expectations.
 */
class PlaceholderPostSubstitutionInvariantTest {
    /** A directory nobody has: no git remote, no Claude session, deterministic values. */
    private val noRepo = "/tmp/placeholder-invariant-no-such-repository"

    @Test
    fun `both shell quote strategies escape expansion within double quotes`() {
        val value = "a\$(id)`whoami`\"b"
        assertEquals("a" + "\\$" + "(id)\\`whoami\\`\\\"b", ShellPathQuoting.posixInsideQuote(value, '"'))
        assertEquals("a" + "`$" + "(id)``whoami```\"b", ShellPathQuoting.powershellInsideQuote(value, '"'))
    }

    @Test
    fun `PowerShell template single quotes also escape typographic quote delimiters`() {
        val value = "name\u2019;calc;\u2019tail"
        assertEquals("name\u2019\u2019;calc;\u2019\u2019tail", ShellPathQuoting.powershellInsideQuote(value, '\''))
    }

    @Test
    fun `quote scanner distinguishes both escape models and unbalanced regions`() {
        val posix = ShellQuoteRegions.scan("echo \\\" && run", '\\')
        val powershell = ShellQuoteRegions.scan("echo `\" && run", '`')
        assertEquals(null, posix.quoteBefore(9))
        assertEquals(null, powershell.quoteBefore(9))
        assertEquals(false, ShellQuoteRegions.scan("echo don't && run", '`').balanced)
    }

    @Test
    fun `separator rewrite preserves quoted literals and recovers from unbalanced quotes`() {
        assertEquals("echo \"a && b\"; run", CommandProcessor.replaceUnquotedSeparators("echo \"a && b\" && run"))
        assertEquals("echo don't; run", CommandProcessor.replaceUnquotedSeparators("echo don't && run"))
    }

    @Test
    fun `a project path containing currentFile syntax is not re-expanded`() {
        val path = "/opt/{currentFile}-proj"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} ; cat {currentFile}",
                path,
                currentFile = "/tmp/evidence.txt",
                quoteProjectPath = true,
            )
        assertEquals(
            "cd ${CommandProcessor.quotePath(path)} ; cat ${CommandProcessor.quotePath("/tmp/evidence.txt")}",
            result,
        )
    }

    @Test
    fun `a project path containing claudeContinueFlag syntax is not re-expanded`() {
        val path = "/opt/{claudeContinueFlag}-proj"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} ; cat",
                path,
                quoteProjectPath = true,
            )
        assertEquals("cd ${CommandProcessor.quotePath(path)} ; cat", result)
    }

    @Test
    fun `a project path containing projectPath syntax is not re-expanded`() {
        val path = "/opt/{projectPath}-proj"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} ; cat",
                path,
                quoteProjectPath = true,
            )
        assertEquals("cd ${CommandProcessor.quotePath(path)} ; cat", result)
    }

    @Test
    fun `a current file containing claudeContinueFlag syntax is not re-expanded`() {
        val file = "/tmp/{claudeContinueFlag}.md"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat {currentFile}",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals("cat ${CommandProcessor.quotePath(file)}", result)
    }

    @Test
    fun `substituteProjectPath does not re-expand a value containing the token itself`() {
        val path = "/opt/{projectPath}"
        assertEquals(
            "cd ${CommandProcessor.quotePath(path)}",
            WorkspacePlaceholders.substituteProjectPath("cd {projectPath}", path, quote = true),
        )
        assertEquals(
            path,
            WorkspacePlaceholders.substituteProjectPath("{projectPath}", path, quote = false),
        )
    }

    @Test
    fun `shell context quotes a current file carrying command substitution`() {
        val file = "/tmp/\$(touch pwned).md"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat {currentFile}",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals("cat ${CommandProcessor.quotePath(file)}", result)
    }

    @Test
    fun `shell context quotes backticks newlines and globs in a current file`() {
        val file = "/tmp/`reboot` a*.md\nnext"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat {currentFile}",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals("cat ${CommandProcessor.quotePath(file)}", result)
    }

    @Test
    fun `shell context quotes the git remote url value`() {
        // [noRepo] is not a git repository, so the remote lookup fails and the value is
        // the documented no-remote fallback - still DATA, still quoted in shell context.
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "open {gitRemoteUrl}",
                noRepo,
                quoteProjectPath = true,
            )
        assertEquals(
            "open ${CommandProcessor.quotePath("https://google.com")}",
            result,
        )
    }

    @Test
    fun `raw contexts keep values verbatim because they are not shell-parsed`() {
        // The url / workingDirectory / filePath shape: quoteProjectPath = false, and the
        // value must not be quoted (a browser url with '…' around it would be broken).
        val file = "/tmp/a; rm -rf ~.md"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "open {currentFile}",
                null,
                currentFile = file,
                quoteProjectPath = false,
            )
        assertEquals("open $file", result)
    }

    @Test
    fun `a current file inside template quotes remains one argument`() {
        val file = "/tmp/space name.md"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat \"{currentFile}\"",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals("cat \"$file\"", result)
    }

    @Test
    fun `wider template quote regions preserve a path suffix and prefix`() {
        val path = "/tmp/space name"
        assertEquals(
            "cd \"$path/frontend\"",
            WorkspacePlaceholders.substituteProjectPath("cd \"{projectPath}/frontend\"", path, quote = true),
        )
        assertEquals(
            "echo \"opening $path\"",
            WorkspacePlaceholders.substituteProjectPath("echo \"opening {projectPath}\"", path, quote = true),
        )
    }

    @Test
    fun `values inside template quotes cannot end the quote region`() {
        val value = "/tmp/a' ; echo forged ; '/file"
        val result = WorkspacePlaceholders.substituteProjectPath("cat '{projectPath}'", value, quote = true)
        assertEquals("cat '${CommandProcessor.escapeInsideQuote(value, '\'')}'", result)
    }

    @Test
    fun `values inside double quotes cannot start command substitution`() {
        val value = "/tmp/\$(touch forged)`id`\"/file"
        val result = WorkspacePlaceholders.substituteProjectPath("cat \"{projectPath}\"", value, quote = true)
        assertEquals("cat \"${CommandProcessor.escapeInsideQuote(value, '"')}\"", result)
    }

    @Test
    fun `an apostrophe after a current file does not suppress quoting`() {
        // {currentFile}'s - the quote after the token closes nothing, so the token is bare.
        val file = "/tmp/space name.md"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat {currentFile}'s backup",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals("cat ${CommandProcessor.quotePath(file)}'s backup", result)
    }

    @Test
    fun `quotes from neighbouring regions do not suppress quoting`() {
        // 'prefix'{currentFile}'suffix' - both adjacent quotes close and open other regions;
        // the token sits between quoted spans, so it is bare.
        val file = "/tmp/space name.md"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat 'prefix'{currentFile}'suffix'",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals("cat 'prefix'${CommandProcessor.quotePath(file)}'suffix'", result)
    }

    @Test
    fun `escaped quotes around a current file do not suppress quoting`() {
        // Each shell's escape character makes the quote literal, not a delimiter.
        val file = "/tmp/space name.md"
        val escape = CommandProcessor.quoteEscapeCharacter()
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat $escape\"{currentFile}$escape\"",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals("cat $escape\"${CommandProcessor.quotePath(file)}$escape\"", result)
    }

    @Test
    fun `current file stays literal when no file is open`() {
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cat {currentFile}",
                noRepo,
                quoteProjectPath = true,
            )
        assertEquals("cat {currentFile}", result)
    }

    @Test
    fun `a current file at the very start or end of a template is quoted`() {
        val file = "/tmp/a b.md"
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "{currentFile} ; tail {currentFile}",
                noRepo,
                currentFile = file,
                quoteProjectPath = true,
            )
        assertEquals(
            "${CommandProcessor.quotePath(file)} ; tail ${CommandProcessor.quotePath(file)}",
            result,
        )
    }

    @Test
    fun `one scan substitutes every data token in a shell command template`() {
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} ; cat {currentFile} ; open {gitRemoteUrl}",
                noRepo,
                currentFile = "/tmp/f.kt",
                quoteProjectPath = true,
            )
        assertEquals(
            "cd ${CommandProcessor.quotePath(noRepo)} ; cat ${CommandProcessor.quotePath("/tmp/f.kt")} ; " +
                "open ${CommandProcessor.quotePath("https://google.com")}",
            result,
        )
    }

    @Test
    fun `claudeContinueFlag stays raw because its value is app-generated`() {
        // No project means no session, so the flag is the empty constant - unquoted.
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "claude {claudeContinueFlag}",
                null,
                quoteProjectPath = true,
            )
        assertEquals("claude ", result)
    }
}
