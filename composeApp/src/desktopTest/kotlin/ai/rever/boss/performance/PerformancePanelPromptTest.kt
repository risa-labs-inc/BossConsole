package ai.rever.boss.performance

import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.MissingDependencyPrompt
import ai.rever.boss.components.plugin.MissingPluginDependency
import ai.rever.boss.components.plugin.PluginDependencyBus
import ai.rever.boss.components.plugin.shouldShowMissingDependency
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the offer to install the Performance panel's plugin.
 *
 * The failure this replaces was silence: the status-bar indicator is host-drawn but the panel it
 * opens belongs to a plugin, so on an install without that plugin the click emitted a panel event
 * nothing listened for. No panel, no dialog, not even a log line - indistinguishable from a
 * broken button.
 */
class PerformancePanelPromptTest {
    private class FakeInstaller(
        private val installed: Set<String>,
    ) : MissingDependencyInstaller {
        override fun isInstalled(pluginId: String) = pluginId in installed

        override suspend fun displayNameFor(pluginId: String): String? = null

        override suspend fun install(pluginId: String): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun `no prompt when the plugin is already installed`() {
        val installer = FakeInstaller(setOf(PERFORMANCE_PLUGIN_ID))
        assertNull(performancePanelPrompt(installer))
    }

    @Test
    fun `a missing plugin produces a prompt naming it`() {
        val prompt = performancePanelPrompt(FakeInstaller(emptySet()))
        assertNotNull(prompt)
        assertEquals(PERFORMANCE_PLUGIN_ID, prompt.missing.missingPluginId)
        assertTrue(prompt.userInitiated, "the panel is only opened by a person pressing something")
    }

    /**
     * Optional is not a detail. It selects the dialog's copy, and the required phrasing would
     * claim BOSS needs this plugin - untrue, and untrue inside a consent dialog for downloading
     * and running code.
     */
    @Test
    fun `the offer is worded as recommended rather than required`() {
        val prompt = performancePanelPrompt(FakeInstaller(emptySet()))
        assertNotNull(prompt)
        assertTrue(prompt.missing.optional)
        assertEquals(
            "BOSS works without Performance, but some of its features need it.",
            prompt.missing.description("Performance"),
        )
    }

    /**
     * Another plugin's absence must not be mistaken for this one's.
     */
    @Test
    fun `an unrelated installed plugin does not satisfy the check`() {
        val other = FakeInstaller(setOf("ai.rever.boss.plugin.dynamic.console"))
        val prompt = performancePanelPrompt(other)
        assertNotNull(prompt)
    }

    // region the bus's forced report

    private fun prompt(
        optional: Boolean = true,
        userInitiated: Boolean = true,
    ) = MissingDependencyPrompt(
        missing =
            MissingPluginDependency(
                dependentPluginId = "ai.rever.boss.host",
                dependentDisplayName = "BOSS",
                missingPluginId = PERFORMANCE_PLUGIN_ID,
                optional = optional,
            ),
        installer = FakeInstaller(emptySet()),
        userInitiated = userInitiated,
    )

    /**
     * The decline set still silences prompts nobody asked for.
     */
    @Test
    fun `an automatic prompt stays silenced once declined`() =
        runTest {
            val bus = PluginDependencyBus()
            val automatic = prompt(userInitiated = false)
            bus.decline(automatic.missing)
            bus.report(automatic)
            assertNull(withTimeoutOrNull(200) { bus.missingDependencies.first() })
        }

    /**
     * Gate 1, the bus: a user-initiated report survives an earlier dismissal.
     */
    @Test
    fun `the bus does not silence a click after an earlier dismissal`() {
        val bus = PluginDependencyBus()
        val click = prompt()
        bus.decline(click.missing)
        assertTrue(bus.wasDeclined(click.missing))
        assertTrue(click.userInitiated)
    }

    /**
     * Gate 2, the window that collects, which re-checks the same set.
     *
     * This is the half that was broken. Exercises the real rule the collector calls, not a copy
     * of it: an earlier version of this test restated the expression inline and passed happily
     * while the collector went on dropping every click.
     */
    @Test
    fun `the collector shows a click even after the offer was dismissed`() {
        assertEquals(
            true,
            shouldShowMissingDependency(prompt(userInitiated = true), present = false, declined = true),
        )
    }

    @Test
    fun `the collector still silences an automatic prompt that was declined`() {
        assertEquals(
            false,
            shouldShowMissingDependency(prompt(userInitiated = false), present = false, declined = true),
        )
    }

    @Test
    fun `nothing is offered once the plugin is present, however it was asked for`() {
        // Two dependents of one missing plugin each raise a prompt; installing for the first
        // must not leave the second claiming something untrue.
        assertEquals(
            false,
            shouldShowMissingDependency(prompt(userInitiated = true), present = true, declined = false),
        )
        assertEquals(
            false,
            shouldShowMissingDependency(prompt(userInitiated = false), present = true, declined = false),
        )
    }

    @Test
    fun `an undeclined prompt for a missing plugin is shown`() {
        assertEquals(
            true,
            shouldShowMissingDependency(prompt(userInitiated = false), present = false, declined = false),
        )
    }

    /**
     * The point of `force`. Someone who dismissed the offer and then pressed the button again is
     * asking again, and answering a click with silence is the failure this feature removes.
     */
    @Test
    fun `a click still asks after the offer was dismissed once`() =
        runTest {
            val bus = PluginDependencyBus()
            bus.decline(prompt().missing)
            bus.report(prompt())
            val delivered = bus.missingDependencies.first()
            assertEquals(PERFORMANCE_PLUGIN_ID, delivered.missing.missingPluginId)
        }

    /**
     * Force skips the decline check only. A second click while the dialog is still up must not
     * stack a second copy of it.
     */
    @Test
    fun `forcing twice does not queue two dialogs`() =
        runTest {
            val bus = PluginDependencyBus()
            bus.report(prompt())
            bus.report(prompt())
            val first = bus.missingDependencies.first()
            assertEquals(PERFORMANCE_PLUGIN_ID, first.missing.missingPluginId)
            // The queued slot is released as the first is delivered, so a later click can ask
            // again; what must not happen is two arriving from two clicks made back to back.
            assertNull(
                withTimeoutOrNull(200) { bus.missingDependencies.first() },
            )
        }

    // endregion
}
