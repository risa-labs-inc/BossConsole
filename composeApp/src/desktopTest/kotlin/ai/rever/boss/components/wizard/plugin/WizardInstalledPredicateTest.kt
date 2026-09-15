package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which plugins the first-run wizard is entitled to skip.
 *
 * The wizard's skip guard used `DynamicPluginManager.isInstalled`, which is
 * `pluginStates.containsKey`. An entry is not a usable plugin: `installPlugin` registers a DISABLED
 * entry for a jar it rejected as binary-incompatible and then deletes that jar. So the wizard
 * announced "already installed, skipping" for a plugin that was not there, added its id to
 * `installedIds`, and finished reporting success for something it never installed. The user picked
 * it during setup and did not get it.
 *
 * The predicate is `PluginDependencyResolution.installedAndOnDisk`, which this file already used
 * further down the same flow. These assertions are about the cases where the two disagree, since
 * the cases where they agree would pass either way.
 *
 * What is NOT covered: the wizard loop itself. `PluginInstallService.installPlugins` reaches the
 * network and the plugin loader, so what is pinned here is the decision it now makes, not the
 * install it then performs.
 */
class WizardInstalledPredicateTest {
    private fun info(
        id: String,
        state: PluginState,
        jarPath: String = "/plugins/$id.jar",
    ) = DynamicPluginInfo(
        manifest =
            PluginManifest(
                pluginId = id,
                displayName = id,
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "com.example.Main",
            ),
        jarPath = jarPath,
        state = state,
        loadedAt = 0L,
        enabled = state == PluginState.LOADED,
    )

    private fun installed(
        states: Map<String, DynamicPluginInfo>,
        jarExists: Boolean = true,
        incompatible: Set<String> = emptySet(),
    ) = PluginDependencyResolution.installedAndOnDisk(
        states = states,
        exists = { jarExists },
        isIncompatible = { it in incompatible },
    )

    @Test
    fun `a rejected plugin whose jar was deleted is not skippable`() {
        // The case the entry-only check got wrong. An entry exists, so `containsKey` said yes; the
        // jar is gone, so nothing would run.
        val states = mapOf("a" to info("a", PluginState.DISABLED))

        assertFalse("a" in installed(states, jarExists = false))
    }

    @Test
    fun `a plugin recorded as binary-incompatible is not skippable even with its jar present`() {
        // The second incompatibility path returns success with state DISABLED and leaves the jar on
        // disk, so the jar clause alone is not enough either.
        val states = mapOf("a" to info("a", PluginState.DISABLED))

        assertFalse("a" in installed(states, jarExists = true, incompatible = setOf("a")))
    }

    @Test
    fun `a loaded plugin is skippable however its recorded jar path reads`() {
        // A running plugin can hold a stale path: the reconciler and the updater both rewrite files
        // without repointing the manager. Reinstalling it would fail with "already loaded".
        val states = mapOf("a" to info("a", PluginState.LOADED))

        assertTrue("a" in installed(states, jarExists = false))
    }

    @Test
    fun `a plugin disabled for lack of access is still skippable`() {
        // Access-gated installs leave the row and the jar in place. That plugin IS installed, so
        // the wizard downloading it again would be wasted work, not a fix.
        val states = mapOf("a" to info("a", PluginState.DISABLED))

        assertTrue("a" in installed(states, jarExists = true))
    }

    @Test
    fun `an unknown plugin is never skippable`() {
        assertFalse("a" in installed(emptyMap()))
    }

    @Test
    fun `the two predicates disagree only on the cases that matter`() {
        // Pinned as a set difference rather than case by case, so a future change to either
        // definition shows up here as a changed disagreement rather than silently converging.
        val states =
            mapOf(
                "loaded" to info("loaded", PluginState.LOADED),
                "disabled" to info("disabled", PluginState.DISABLED),
            )
        val entryOnly = states.keys

        assertEquals(
            setOf("disabled"),
            entryOnly - installed(states, jarExists = false),
            "only the entry whose jar is gone should differ",
        )
        assertEquals(
            emptySet(),
            entryOnly - installed(states, jarExists = true),
            "with jars present the two agree",
        )
    }

    @Test
    fun `registration incompatibility is an install failure even when the jar survives`() {
        val result = Result.success(info("a", PluginState.DISABLED))
        assertTrue(usableWizardInstallResult(result, exists = { true }, isIncompatible = { true }).isFailure)
    }

    @Test
    fun `access-disabled installation remains successful`() {
        val result = Result.success(info("a", PluginState.DISABLED))
        assertEquals(result, usableWizardInstallResult(result, exists = { true }, isIncompatible = { false }))
    }

    @Test
    fun `loaded installation wins over a stale path and incompatibility marker`() {
        val result = Result.success(info("a", PluginState.LOADED))
        assertEquals(result, usableWizardInstallResult(result, exists = { false }, isIncompatible = { true }))
    }

    @Test
    fun `missing disabled artifact cannot become an install success`() {
        val result = Result.success(info("a", PluginState.DISABLED))
        assertTrue(usableWizardInstallResult(result, exists = { false }, isIncompatible = { false }).isFailure)
    }

    @Test
    fun `original loader failure survives without checking installation state`() {
        val failure = IllegalStateException("loader failed")
        val result = Result.failure<DynamicPluginInfo>(failure)
        val actual =
            usableWizardInstallResult(
                result,
                exists = { error("Must not inspect a failed load") },
                isIncompatible = { error("Must not inspect a failed load") },
            )
        assertEquals(failure, actual.exceptionOrNull())
    }
}
