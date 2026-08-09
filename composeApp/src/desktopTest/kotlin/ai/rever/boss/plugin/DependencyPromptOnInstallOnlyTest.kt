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
        // Asserts the property, not the formatting: doReloadPlugin's own load must be a
        // non-reporting one. Deliberately not a count of `reportDependencies = false` - a second
        // legitimate non-reporting caller is allowed, and a test that forbade one would be
        // deleted rather than understood.
        assertTrue(
            Regex(
                """fun\s+doReloadPlugin[\s\S]{0,4000}?loadPlugin\(\s*jarPath\s*,""" +
                    """\s*reportDependencies\s*=\s*false\s*\)""",
            ).containsMatchIn(source()),
            "doReloadPlugin must load without reporting missing dependencies",
        )
    }

    @Test
    fun `the report set and the install guard come from one function`() {
        val text = source()

        // What the definition *is* now has real tests (`installedAndOnDisk` in
        // PluginDependencyResolutionTest). This only pins that both halves still read it from
        // one place: when they diverged, a failed install left a dangling entry that silenced
        // the prompt for every later dependent of that plugin.
        assertEquals(
            1,
            Regex("""fun\s+installedPluginIds\(""").findAll(text).count(),
            "there must be exactly one definition of the installed set",
        )
        assertTrue(
            Regex("""installedNow\s*=\s*\{[^}]*installedPluginIds\(\)""").containsMatchIn(text),
            "the Install guard must read the same set as the reporter",
        )
        assertTrue(
            Regex("""val\s+installed\s*=\s*installedPluginIds\(\)""").containsMatchIn(text),
            "the report set must read the same set as the Install guard",
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
