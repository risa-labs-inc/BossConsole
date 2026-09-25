package ai.rever.boss.dashboard

import ai.rever.boss.components.workspaces.CommandProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ordering inside [WorkspacePlaceholders.processPlaceholders]:
 * command-separator normalization runs on the TEMPLATE, before any value is
 * substituted in (#1181). Normalization rewrites the ` && ` the command author
 * typed between commands; run after substitution it rewrites substituted *data*
 * instead - a Windows project path can legally contain ` && ` (all four chars
 * are valid in a filename), and `normalizeCommand`'s blind whole-string replace
 * turned the quoted `'C:\A && B\proj'` into the nonexistent `'C:\A ; B\proj'`.
 *
 * The real [CommandProcessor.normalizeCommand] only rewrites on Windows, so the
 * ordering is pinned through the internal overload that takes the normalize
 * step as a parameter: these tests inject the Windows rewrite (` && ` to `; `).
 * Quoted expectations go through the platform-aware [CommandProcessor.quotePath],
 * so they hold on any host - like [SubstituteProjectPathTest].
 */
class PlaceholderNormalizeOrderingTest {
    /** What [CommandProcessor.normalizeCommand] does on a Windows host. */
    private val windowsNormalize: (String) -> String = { it.replace(" && ", "; ") }

    /** ` && ` is a legal, if unwise, folder name on Windows - the path that broke. */
    private val windowsPath = "C:\\A && B\\proj"

    @Test
    fun quotedWindowsPathSurvivesSubstitutionByteIntact() {
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} && claude",
                windowsPath,
                null,
                quoteProjectPath = true,
                normalizeCommand = windowsNormalize,
            )
        // The template's separator is normalized; the substituted path is not.
        assertEquals("cd ${CommandProcessor.quotePath(windowsPath)}; claude", result)
        assertTrue(result.contains(" && "), "the substituted path must keep its literal ' && '")
    }

    @Test
    fun rawWindowsPathSurvivesSubstitution() {
        // quoteProjectPath=false is the workingDirectory/filePath/url shape: the
        // path is substituted verbatim there too, and must stay byte-intact.
        assertEquals(
            windowsPath,
            WorkspacePlaceholders.processPlaceholders(
                "{projectPath}",
                windowsPath,
                null,
                quoteProjectPath = false,
                normalizeCommand = windowsNormalize,
            ),
        )
    }

    @Test
    fun substitutedCurrentFileIsDataToo() {
        assertEquals(
            "cat $windowsPath; echo done",
            WorkspacePlaceholders.processPlaceholders(
                "cat {currentFile} && echo done",
                null,
                windowsPath,
                quoteProjectPath = false,
                normalizeCommand = windowsNormalize,
            ),
        )
    }

    @Test
    fun templateSeparatorsStillNormalize() {
        // Regression guard: commands written with ` && ` in the template MUST
        // still come out with platform separators - several of them, not just one.
        val path = "/Users/foo/proj"
        assertEquals(
            "cd ${CommandProcessor.quotePath(path)}; clear; claude --dangerously-skip-permissions",
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} && clear && claude --dangerously-skip-permissions",
                path,
                null,
                quoteProjectPath = true,
                normalizeCommand = windowsNormalize,
            ),
        )
    }

    @Test
    fun placeholderFreeTemplateStillNormalizes() {
        assertEquals(
            "npm install; npm run build",
            WorkspacePlaceholders.processPlaceholders(
                "npm install && npm run build",
                null,
                null,
                quoteProjectPath = false,
                normalizeCommand = windowsNormalize,
            ),
        )
    }

    @Test
    fun publicOverloadAppliesPlatformNormalizeBeforeSubstitution() {
        // The production wiring: the public overload feeds the REAL
        // CommandProcessor.normalizeCommand to the pipeline. Whatever it does on
        // this host, it happened to the template - so on every platform the
        // substituted path keeps its literal ` && `.
        val separator =
            CommandProcessor.normalizeCommand("x && y").removePrefix("x").removeSuffix("y")
        val result =
            WorkspacePlaceholders.processPlaceholders(
                "cd {projectPath} && claude",
                windowsPath,
                quoteProjectPath = true,
            )
        assertEquals("cd ${CommandProcessor.quotePath(windowsPath)}${separator}claude", result)
    }
}
