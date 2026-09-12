package ai.rever.boss.components.wizard.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginInstallWizardStateTest {
    private val defaultPlugin =
        WizardPluginInfo(
            id = "default-plugin",
            name = "Default Plugin",
            description = "Selected by default",
            version = "1.0.0",
            isDefault = true,
            category = PluginCategory.ESSENTIAL,
        )

    private val mandatoryPlugin =
        WizardPluginInfo(
            id = "mandatory-plugin",
            name = "Mandatory Plugin",
            description = "Required for setup",
            version = "1.0.0",
            isMandatory = true,
            category = PluginCategory.ESSENTIAL,
        )

    private val optionalPlugin =
        WizardPluginInfo(
            id = "optional-plugin",
            name = "Optional Plugin",
            description = "Not selected by default",
            version = "1.0.0",
            category = PluginCategory.DEVELOPER,
        )

    private fun state(): PluginInstallWizardState =
        PluginInstallWizardState(
            listOf(
                defaultPlugin,
                mandatoryPlugin,
                optionalPlugin,
            ),
        )

    @Test
    fun `fresh state waits for an explicit profile choice`() {
        val state = state()

        assertFalse(state.isPluginSelected(defaultPlugin.id))
        assertFalse(state.isPluginSelected(mandatoryPlugin.id))
        assertFalse(state.isPluginSelected(optionalPlugin.id))
        assertFalse(state.hasSelectedPlugins())
        assertEquals(null, state.selectedProfile)
    }

    @Test
    fun `general profile keeps core tools and removes developer tools`() {
        val state = state()

        state.applyProfile(ToolboxProfile.GENERAL)

        assertTrue(state.isPluginSelected(defaultPlugin.id))
        assertTrue(state.isPluginSelected(mandatoryPlugin.id))
        assertFalse(state.isPluginSelected(optionalPlugin.id))
    }

    @Test
    fun `everything profile selects every available tool`() {
        val state = state()

        state.applyProfile(ToolboxProfile.EVERYTHING)

        assertEquals(3, state.getSelectedPlugins().size)
    }

    @Test
    fun `mandatory plugin cannot be deselected`() {
        val state = state()

        state.setPluginSelected(mandatoryPlugin.id, false)
        assertTrue(state.isPluginSelected(mandatoryPlugin.id))

        state.togglePlugin(mandatoryPlugin.id)
        assertTrue(state.isPluginSelected(mandatoryPlugin.id))
    }

    @Test
    fun `retry reset preserves plugin selections`() {
        val state = state()

        state.applyProfile(ToolboxProfile.DEVELOPER)
        state.startInstallation()
        state.failInstallation("temporary failure")

        state.reset()

        assertTrue(state.isPluginSelected(defaultPlugin.id))
        assertTrue(state.isPluginSelected(mandatoryPlugin.id))
        assertTrue(state.isPluginSelected(optionalPlugin.id))
    }

    @Test
    fun `installation retry stays on the current selection`() {
        val state = state()
        state.applyProfile(ToolboxProfile.DEVELOPER)
        state.startInstallation()
        state.updateProgress(0.4f, "Installing")
        state.failInstallation("temporary failure")

        state.prepareInstallationRetry()

        assertFalse(state.installationAttempted)
        assertEquals(null, state.installationError)
        assertEquals(3, state.getSelectedPlugins().size)
    }

    @Test
    fun `retry reset clears transient installation state`() {
        val state = state()

        state.startInstallation()
        state.updateProgress(0.65f, "Installing Default Plugin...")
        state.failInstallation("temporary failure")

        state.reset()

        assertFalse(state.isInstalling)
        assertFalse(state.installationAttempted)
        assertEquals(0f, state.installationProgress)
        assertEquals("", state.installationStatus)
        assertEquals(null, state.installationError)
        assertEquals(emptyList(), state.installedPluginIds)
        assertEquals(emptyList(), state.failedPlugins)
    }

    @Test
    fun `update progress clamps values to valid range`() {
        val state = state()

        state.updateProgress(1.5f, "Too far")
        assertEquals(1f, state.installationProgress)
        assertEquals("Too far", state.installationStatus)

        state.updateProgress(-0.5f, "Too low")
        assertEquals(0f, state.installationProgress)
        assertEquals("Too low", state.installationStatus)
    }

    @Test
    fun `complete installation preserves successful and failed results`() {
        val state = state()

        val installed = listOf(defaultPlugin.id, mandatoryPlugin.id)
        val failed = listOf(optionalPlugin.id to "Download failed")

        state.startInstallation()
        state.completeInstallation(installed, failed)

        assertFalse(state.isInstalling)
        assertEquals(1f, state.installationProgress)
        assertEquals("Installation complete", state.installationStatus)
        assertEquals(installed, state.installedPluginIds)
        assertEquals(failed, state.failedPlugins)
    }
}
