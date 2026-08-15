package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A plugin addressing a panel - `SplitViewOperations.openPanelAsTab`, `PanelEventProvider.openPanel` -
 * knows the panel's id STRING and nothing else. [PanelId] is a data class of three fields and
 * [PanelRegistry] keys on all of them, so an id built with a guessed `defaultOrder` matches
 * nothing and fails silently.
 *
 * That is not hypothetical: the api's own `AiAvailability` builds `PanelId(TOOLBOX_PANEL_ID, 0)`,
 * and the Toolbox panel registers as order 6.
 */
class PanelIdResolutionTest {
    private fun registryOf(vararg ids: PanelId) =
        object : PanelRegistry() {
            override fun getAllPanels(): List<PanelInfo> = ids.map(::FakePanelInfo)
        }

    private class FakePanelInfo(
        override val id: PanelId,
    ) : PanelInfo {
        override val displayName: String = id.panelId
        override val icon: ImageVector = Icons.Outlined.Tab
        override val defaultSlotPosition: Panel = left.top
    }

    /** The case that would otherwise be a silent no-op. */
    @Test
    fun `a guessed defaultOrder still finds the panel, and yields the registered id`() {
        val registered = PanelId("plugin-manager", 6)
        val registry = registryOf(registered)

        assertEquals(registered, registry.resolveRegisteredPanelId(PanelId("plugin-manager", 0)))
    }

    @Test
    fun `an exact id resolves to itself`() {
        val registered = PanelId("codebase", 1)

        assertEquals(registered, registryOf(registered).resolveRegisteredPanelId(registered))
    }

    @Test
    fun `an unregistered panel resolves to nothing`() {
        assertNull(registryOf(PanelId("codebase", 1)).resolveRegisteredPanelId(PanelId("git-log", 15)))
    }

    /**
     * `pluginId` stays part of the match. It defaults to "ai.rever.boss", and the few plugins
     * that set it (docker, kubernetes, organisation, dna-origami) are distinguishing themselves
     * deliberately - dropping it would let one plugin address another's panel by name collision.
     */
    @Test
    fun `a different owning plugin is a different panel`() {
        val registry = registryOf(PanelId("containers", 20, pluginId = "ai.rever.boss.plugin.dynamic.docker"))

        assertNull(registry.resolveRegisteredPanelId(PanelId("containers", 20)))
    }

    @Test
    fun `the owner is matched, not merely tolerated`() {
        val docker = PanelId("containers", 20, pluginId = "ai.rever.boss.plugin.dynamic.docker")
        val registry = registryOf(PanelId("containers", 1), docker)

        assertEquals(docker, registry.resolveRegisteredPanelId(PanelId("containers", 999, pluginId = docker.pluginId)))
    }
}
