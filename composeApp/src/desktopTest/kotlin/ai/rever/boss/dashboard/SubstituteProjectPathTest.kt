package ai.rever.boss.dashboard

import ai.rever.boss.components.workspaces.CommandProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [SplitTemplatesManager.substituteProjectPath] — the {projectPath}
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
            SplitTemplatesManager.substituteProjectPath("{projectPath}", spaced, quote = false)
        )
        assertEquals(
            "$spaced/README.md",
            SplitTemplatesManager.substituteProjectPath("{projectPath}/README.md", spaced, quote = false)
        )
    }

    @Test
    fun quotesBareOccurrenceInCommand() {
        assertEquals(
            "cd $quoted && clear && claude --dangerously-skip-permissions",
            SplitTemplatesManager.substituteProjectPath(
                "cd {projectPath} && clear && claude --dangerously-skip-permissions",
                spaced,
                quote = true
            )
        )
    }

    @Test
    fun leavesAlreadyDoubleQuotedTemplateRaw() {
        // A user who worked around the bug with cd "{projectPath}" must NOT get cd "'…'".
        assertEquals(
            "cd \"$spaced\"",
            SplitTemplatesManager.substituteProjectPath("cd \"{projectPath}\"", spaced, quote = true)
        )
    }

    @Test
    fun leavesAlreadySingleQuotedTemplateRaw() {
        assertEquals(
            "cd '$spaced'",
            SplitTemplatesManager.substituteProjectPath("cd '{projectPath}'", spaced, quote = true)
        )
    }

    @Test
    fun plainPathStillQuotedButHarmless() {
        // No-space path is quoted too; the shell treats 'x' identically to x.
        val plain = "/Users/foo/bar"
        assertEquals(
            "cd '$plain'",
            SplitTemplatesManager.substituteProjectPath("cd {projectPath}", plain, quote = true)
        )
    }
}
