package ai.rever.boss.dashboard

import ai.rever.boss.components.workspaces.CommandProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [WorkspacePlaceholders.substituteProjectPath] — the {projectPath}
 * substitution that decides raw vs shell-quoted. The expected quoted form is
 * computed via the platform-aware [CommandProcessor.quotePath] (POSIX single-quote
 * literal on macOS/Linux; PowerShell single-quote literal on Windows), so the
 * assertions hold on any CI host rather than only a POSIX one.
 */
class SubstituteProjectPathTest {
    private val spaced = "/Users/foo/AI Workflow Tools' Exports/claude-exports"
    private val quoted = CommandProcessor.quotePath(spaced)

    @Test
    fun rawSubstitutionWhenNotQuoting() {
        // workingDirectory / filePath / url path: must stay verbatim (not shell-parsed).
        assertEquals(
            spaced,
            WorkspacePlaceholders.substituteProjectPath("{projectPath}", spaced, quote = false),
        )
        assertEquals(
            "$spaced/README.md",
            WorkspacePlaceholders.substituteProjectPath("{projectPath}/README.md", spaced, quote = false),
        )
    }

    @Test
    fun quotesBareOccurrenceInCommand() {
        assertEquals(
            "cd $quoted && clear && claude --dangerously-skip-permissions",
            WorkspacePlaceholders.substituteProjectPath(
                "cd {projectPath} && clear && claude --dangerously-skip-permissions",
                spaced,
                quote = true,
            ),
        )
    }

    @Test
    fun leavesAlreadyDoubleQuotedTemplateInItsQuoteRegion() {
        // A user who worked around the bug with cd "{projectPath}" must NOT get cd "'…'".
        assertEquals(
            "cd \"$spaced\"",
            WorkspacePlaceholders.substituteProjectPath("cd \"{projectPath}\"", spaced, quote = true),
        )
    }

    @Test
    fun escapesApostrophesInAlreadySingleQuotedTemplate() {
        assertEquals(
            "cd '${CommandProcessor.escapeInsideQuote(spaced, '\'')}'",
            WorkspacePlaceholders.substituteProjectPath("cd '{projectPath}'", spaced, quote = true),
        )
    }

    @Test
    fun plainPathStillQuotedButHarmless() {
        // No-space path is quoted too; the shell treats 'x' identically to x.
        val plain = "/Users/foo/bar"
        assertEquals(
            "cd '$plain'",
            WorkspacePlaceholders.substituteProjectPath("cd {projectPath}", plain, quote = true),
        )
    }

    /**
     * All three project placeholders read "is there a project" the same way, for null and for
     * blank alike. They did not: `{projectPath}` treated blank as absent while the other two
     * took it as a real path, so `{claudeContinueFlag}` ran a session lookup whose encoded
     * path was empty - landing on `~/.claude/projects/` itself.
     *
     * Asserted through `{gitRemoteUrl}` and `{claudeContinueFlag}` only. `{projectPath}` is
     * left out on purpose: its no-project answer is `DefaultWorkingDirectory.ensureDefaultDirectory()`,
     * which would create `~/BossProjects` on the machine running this.
     *
     * This is the direct-caller path. Every production caller resolves before calling, so with
     * no project selected they all pass `~/BossProjects` and take the has-a-project branch -
     * see the note on `processPlaceholders`. What is pinned here is that null and blank mean
     * the same thing to all three placeholders, not that the app reaches this branch.
     */
    @Test
    fun blankProjectPathIsTreatedAsNoProject() {
        for (absent in listOf(null, "", "   ")) {
            assertEquals(
                "https://google.com",
                WorkspacePlaceholders.processPlaceholders("{gitRemoteUrl}", absent),
                "projectPath=${absent.orEmpty().ifEmpty { "<blank>" }}",
            )
            assertEquals(
                "",
                WorkspacePlaceholders.processPlaceholders("{claudeContinueFlag}", absent),
                "projectPath=${absent.orEmpty().ifEmpty { "<blank>" }}",
            )
        }
    }

    @Test
    fun projectPathContainingAmpersandsSurvivesSubstitutionAndNormalization() {
        val ampersandPath =
            if (ai.rever.boss.run.ShellUtils.isWindows) {
                """C:\Users\foo\A && B\proj"""
            } else {
                "/Users/foo/A && B/proj"
            }
        val quotedAmpersand = CommandProcessor.quotePath(ampersandPath)
        val expected =
            if (ai.rever.boss.run.ShellUtils.isWindows) {
                "cd $quotedAmpersand; claude"
            } else {
                "cd $quotedAmpersand && claude"
            }
        assertEquals(
            expected,
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} && claude",
                ampersandPath,
                quoteProjectPath = true,
            ),
        )
    }

    @Test
    fun normalizeCommandPreservesAmpersandsInsideQuotedStrings() {
        if (!ai.rever.boss.run.ShellUtils.isWindows) return
        assertEquals(
            "cd 'C:\\A && B'; echo '1 && 2'",
            CommandProcessor.normalizeCommand("cd 'C:\\A && B' && echo '1 && 2'"),
        )
        assertEquals(
            "echo \"A && B\"; dir",
            CommandProcessor.normalizeCommand("echo \"A && B\" && dir"),
        )
        assertEquals(
            "cmd1&&cmd2",
            CommandProcessor.normalizeCommand("cmd1&&cmd2"),
        )
    }
}
