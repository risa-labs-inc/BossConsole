package ai.rever.boss.components.wizard.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wizard must report unmet dependencies **after** its batch, not inside the loop.
 *
 * This is the bug the placement fixes: a first-run selection of `[jupyter-notebook, ai-gateway]`,
 * which is the pick this whole feature exists for, reported the gateway as missing while the loop
 * was still two iterations from installing it. `installPlugins` runs on `Dispatchers.IO`, so the
 * collector on Main showed the dialog over the wizard, and taking Install raced the wizard's own
 * download - the two paths write different filenames for one plugin id, so nothing collides at
 * the path level and the process-wide coalescing guard never sees a shared key. Reporting after
 * the loop means `installedAndOnDisk` already contains everything the batch installed, so nothing
 * intra-batch is reported at all.
 *
 * A source-level assertion for the same reason as `DependencyPromptOnInstallOnlyTest`:
 * `PluginInstallService.installPlugins` needs a live `DynamicPluginManager`, a store and a
 * network to reach, so the ordering cannot be observed from a unit test. It is asserted here
 * because ordering is exactly what went wrong.
 */
class WizardDependencyReportTest {
    private fun source(): String {
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
                "could not locate the repository root",
            )
        val file =
            File(
                root,
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/wizard/plugin/PluginInstallService.kt",
            )
        assertTrue(file.isFile, "PluginInstallService.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `the batch collects manifests and reports once, outside the install loop`() {
        val text = source()

        // Exactly one report call, and it iterates the collected batch rather than a single
        // manifest - which is only possible after the loop has finished.
        assertTrue(
            Regex("""installedManifests\.forEach\s*\{[^}]*\.report\(""").containsMatchIn(text),
            "the wizard must report the collected batch, not one manifest per iteration",
        )
        assertTrue(
            // Scoped to the dependency reporter: counting every `.report(` in the file would
            // fail on an unrelated progress or metrics call, with a message about dependency
            // ordering that would send the next person the wrong way.
            Regex("""dependencyReporter\.report\(""").findAll(text).count() == 1,
            "expected exactly one dependencyReporter.report call in the wizard, after the loop",
        )
    }

    @Test
    fun `both branches check the plugin actually registered before reporting it`() {
        val text = source()

        // `installPlugin` returns success with `state = DISABLED` for a registration-time binary
        // incompatibility, so reporting on success alone offers to download a second plugin to
        // support a dead one. Both branches add to the batch, so both need the gate - the store
        // branch had it and the GitHub branch did not.
        assertEquals(
            2,
            Regex("""PluginState\.LOADED""").findAll(text).count(),
            "each branch that feeds the dependency batch must check state == LOADED",
        )
    }

    @Test
    fun `both install branches feed the batch`() {
        val text = source()

        // The GitHub branch reports nowhere else, so if it does not contribute its manifest here
        // it is silent - which it was.
        assertTrue(
            Regex("""installedManifests\.add\(""").findAll(text).count() >= 2,
            "both the store and GitHub branches must add their manifest to the batch",
        )
        assertTrue(
            // No `||` fallback: an alternative matching any function with that return type would
            // always decide the assertion, so the scoped pattern could never fail.
            Regex("""fun\s+installFromGitHub[\s\S]{0,400}?\): Result<DynamicPluginInfo>""")
                .containsMatchIn(text),
            "installFromGitHub must return what registered, so the batch can check it loaded",
        )
    }
}
