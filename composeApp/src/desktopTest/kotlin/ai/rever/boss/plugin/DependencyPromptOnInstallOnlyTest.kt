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
 * down to the properties that cannot have one: the install-only rule above, and the two
 * re-activation paths that must report again since #180 (a dependency removed while its
 * dependent sat disabled leaves a re-enabled plugin registering against a provider that is
 * not there - re-enabling is a user action, so it reports).
 */
class DependencyPromptOnInstallOnlyTest {
    private fun repoRoot(): File =
        assertNotNull(
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
            "could not locate the repository root",
        )

    private fun source(): String {
        val file = File(repoRoot(), "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginLoaderDelegateImpl.kt")
        assertTrue(file.isFile, "PluginLoaderDelegateImpl.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    private fun managerSource(): String {
        val file = File(repoRoot(), "composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DynamicPluginManager.kt")
        assertTrue(file.isFile, "DynamicPluginManager.kt not found at ${file.absolutePath}")
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
    fun `a plugin that did not actually register is not reported for`() {
        // `installPlugin` returns success with `state = DISABLED` when registration failed as
        // binary-incompatible, or the plugin is hidden for lack of access. Reporting then says
        // "Flow needs the AI Gateway" for something that is not running and will not run, and
        // taking Install downloads a second plugin to support a dead one.
        assertTrue(
            Regex(
                """if\s*\(\s*reportDependencies\s*&&\s*info\.state\s*==\s*""" +
                    """PluginState\.LOADED\s*\)[\s\S]{0,300}?\.report\(""",
            ).containsMatchIn(source()),
            "reporting must be gated on the plugin having actually loaded",
        )
    }

    @Test
    fun `the load path consults the flag before reporting`() {
        // Matches the guard wherever it sits, so adding a log line or rewrapping the expression
        // does not fail a test about behaviour.
        assertTrue(
            Regex("""if\s*\(\s*reportDependencies\b[\s\S]{0,400}?\.report\(""").containsMatchIn(source()),
            "the load path must consult reportDependencies before reporting",
        )
    }

    @Test
    fun `the delegate binds the manager's re-activation notifier to the reporter`() {
        // The manager is commonMain and the reporter desktopMain, so the notifier reaches the
        // prompt only if this binding exists. Asserting the property, not the formatting.
        assertTrue(
            Regex("""dependencyMissingNotifier\s*=\s*dependencyReporter\.report""").containsMatchIn(source()),
            "PluginLoaderDelegateImpl must bind dependencyMissingNotifier to the reporter (issue #180)",
        )
    }

    @Test
    fun `re-enabling a disabled plugin reports its unmet dependencies`() {
        // A genuine re-enable (not a redundant one) is a user action, so after the panels
        // refresh it must announce the manifest to the notifier.
        assertTrue(
            Regex(
                """if\s*\(\s*result\.isSuccess\s*&&\s*!wasAlreadyEnabled\s*\)\s*\{[\s\S]{0,600}?""" +
                    """dependencyMissingNotifier\?\.invoke\(""",
            ).containsMatchIn(managerSource()),
            "enablePlugin must report unmet dependencies on a genuine re-enable (issue #180)",
        )
    }

    @Test
    fun `regaining RBAC access reports unmet dependencies`() {
        // Anchor on the log line the success path writes, so the assertion stays tied to the
        // re-register that succeeded rather than to an arbitrary point in the loop.
        assertTrue(
            Regex(
                """Re-registered plugin after access gained[\s\S]{0,600}?dependencyMissingNotifier\?\.invoke\(""",
            ).containsMatchIn(managerSource()),
            "handleAccessChange must report unmet dependencies when a hidden plugin becomes visible (issue #180)",
        )
    }
}
