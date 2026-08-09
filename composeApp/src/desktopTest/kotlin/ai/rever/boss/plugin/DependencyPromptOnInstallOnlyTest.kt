package ai.rever.boss.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dependency prompt must fire on install and not on reload.
 *
 * `doReloadPlugin` finishes by calling `loadPlugin`, and reload is reached by
 * `resetPluginInstances`, the Toolbox's update flow and the evolver's hot reload - none of which
 * is a user asking to install anything, so re-offering a dependency someone already declined on
 * every reload would be worse than saying nothing.
 *
 * A source-level assertion because this one seam is unreachable from a unit test:
 * `PluginLoaderDelegateImpl` takes a concrete `DynamicPluginManager`, which cannot be faked.
 * Same approach as `WindowsArm64SourceIsolationTest`, for the same reason - a mistake no
 * behavioural test can see should still fail a PR. Everything else about the prompt now has real
 * tests (`MissingDependencyReporterTest`, `PluginDependencyResolutionTest`), so this file is
 * deliberately down to the one property that cannot have one.
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
    fun `the load path consults the flag before reporting`() {
        // Matches the guard wherever it sits, so adding a log line or rewrapping the expression
        // does not fail a test about behaviour.
        assertTrue(
            Regex("""if\s*\(\s*reportDependencies\s*\)[\s\S]{0,200}?\.report\(""").containsMatchIn(source()),
            "the load path must consult reportDependencies before reporting",
        )
    }
}
