package ai.rever.boss.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dependency prompt must fire on install and not on reload.
 *
 * `doReloadPlugin` finishes by calling `loadPlugin`, and reload is reached by
 * `resetPluginInstances`, the Toolbox's update flow and the evolver's hot reload - none of which
 * is a user asking to install anything, so re-offering a dependency someone already declined on
 * every reload would be worse than saying nothing. The whole distinction is one boolean at two
 * call sites, and collapsing the private overload back into the public one would silently lose
 * it while every behavioural test still passed.
 *
 * A source-level assertion because the seam is unreachable from a unit test:
 * `PluginLoaderDelegateImpl` takes a concrete `DynamicPluginManager`, which cannot be faked.
 * Same approach as `WindowsArm64SourceIsolationTest`, for the same reason - a mistake that no
 * behavioural test can see should still fail a PR.
 */
class DependencyPromptOnInstallOnlyTest {
    private fun source(): String {
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
                "could not locate the repository root",
            )
        val file = File(root, "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginLoaderDelegateImpl.kt")
        assertTrue(file.isFile, "PluginLoaderDelegateImpl.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `the reload path does not report missing dependencies`() {
        val text = source()

        assertEquals(
            1,
            Regex("""reportDependencies\s*=\s*false""").findAll(text).count(),
            "expected exactly one non-reporting load, at the reload call site",
        )
        // Anchored to the comment marking the reload leg of doReloadPlugin, so moving the flag
        // onto some other call fails rather than passing on the count alone.
        assertTrue(
            Regex("""//\s*Reload\s*\n\s*loadPlugin\(\s*jarPath\s*,\s*reportDependencies\s*=\s*false\s*\)""")
                .containsMatchIn(text),
            "doReloadPlugin's load must be the one that does not report",
        )
    }

    @Test
    fun `the public entry point is the one that reports`() {
        // Whitespace-tolerant: a signature change or a ktlint rewrap must not fail this for a
        // non-reason, or the next person deletes the test instead of reading it.
        assertTrue(
            Regex(
                """override\s+suspend\s+fun\s+loadPlugin\(\s*jarPath:\s*String\s*\)""" +
                    """[^\n]*=\s*loadPlugin\(\s*jarPath\s*,\s*reportDependencies\s*=\s*true\s*\)""",
            ).containsMatchIn(source()),
            "the delegate's public loadPlugin must be the reporting one",
        )
    }

    @Test
    fun `the report set and the install guard share one definition of installed`() {
        val text = source()

        // The bug this pins: the reporter used the raw `pluginStates` keys while the installer
        // required keys-plus-jar. A binary-incompatible load leaves a DISABLED entry whose jar
        // the installer has deleted, so the reporter then saw that plugin as present and every
        // LATER dependent of it reported nothing - the silence this feature exists to remove.
        assertTrue(
            Regex("""\.keys\s*\n?\s*\.filter\(\s*installedAndOnDisk\s*\)""").containsMatchIn(text),
            "the report set must be filtered through installedAndOnDisk, not the raw keys",
        )
        assertTrue(
            Regex("""installedNow\s*=\s*installedAndOnDisk""").containsMatchIn(text),
            "the installer's guard must be the same predicate, not a second copy",
        )
        assertEquals(
            1,
            Regex("""val\s+installedAndOnDisk""").findAll(text).count(),
            "there must be exactly one definition of installed",
        )
    }

    @Test
    fun `reporting is gated on the flag rather than always running`() {
        // The flag has to be consulted, not merely accepted: an early-return in the reporter
        // is what makes the reload path silent.
        assertTrue(
            Regex("""fun\s+reportMissingDependencies\([^)]*\)\s*\{\s*\n\s*if\s*\(!\s*\w+\s*\)\s*return""")
                .containsMatchIn(source()),
            "reportMissingDependencies must return early when reporting is off",
        )
    }
}
