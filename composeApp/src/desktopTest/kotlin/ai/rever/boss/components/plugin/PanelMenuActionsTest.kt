package ai.rever.boss.components.plugin

import ai.rever.boss.mcp.EvolverContract
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.window.LocalWindowId
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a panel's menu is allowed to offer, which is the half of the shared menu that decides rather
 * than draws.
 *
 * Every row here is gated on something a person cannot see: a window to route the action to, a
 * plugin behind the panel, a build that is not the released one, an MCP tool the current user's role
 * exposes. Getting a gate wrong does not look like a bug - it looks like a row that is present and
 * does nothing, which is exactly what this file's rule exists to prevent, so the gates are asserted
 * directly rather than through whichever surface happens to draw them.
 */
class PanelMenuActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val PROVIDER = "test:evolver"
        val PANEL = PanelId("probe", 1)
    }

    @After
    fun clearRegistries() {
        McpToolRegistryImpl.unregisterProvider(PROVIDER)
        PluginBuildRegistry.reset()
    }

    /** Expose [names] as MCP tools, the way the evolver plugin exposes its own. */
    private fun exposeTools(vararg names: String) {
        McpToolRegistryImpl.registerProvider(
            object : McpToolProvider {
                override val providerId = PROVIDER

                override fun tools() =
                    names.map { name ->
                        McpToolDefinition(
                            name = name,
                            description = name,
                            handler = { McpToolResult("ok") },
                        )
                    }
            },
        )
    }

    private fun resolve(
        windowId: String? = "window-1",
        pluginId: String? = PLUGIN,
        uninstallable: Boolean = true,
    ): PanelMenuActions {
        var actions = PanelMenuActions()
        compose.setContent {
            CompositionLocalProvider(
                LocalWindowId provides windowId,
                LocalPanelPluginIdResolver provides { pluginId },
                LocalPluginUninstallable provides { uninstallable },
            ) {
                actions = panelMenuActions(PANEL)
            }
        }
        compose.waitForIdle()
        return actions
    }

    private fun localBuild() =
        PluginBuildInfo(
            pluginId = PLUGIN,
            displayName = "Probe",
            version = "1.0.3",
            signedBytes = false,
            storeSourced = false,
            reloadStamp = null,
        )

    @Test
    fun `a resolvable plugin in a tracked window gets the standing actions`() {
        val actions = resolve()

        assertNotNull(actions.reloadPanel)
        assertNotNull(actions.checkForUpdates)
        assertNotNull(actions.uninstallPlugin)
        assertTrue(actions.uninstallEnabled)
    }

    @Test
    fun `outside a tracked window nothing is offered`() {
        // LocalWindowId is null wherever the caller is not hosted in a tracked window, and every one
        // of these actions is addressed to a window.
        val actions = resolve(windowId = null)

        assertNull(actions.reloadPanel)
        assertNull(actions.checkForUpdates)
        assertNull(actions.uninstallPlugin)
    }

    @Test
    fun `a panel with no resolvable plugin is offered nothing either`() {
        // Every handler in BossAppMenuActionEffects resolves the panel's plugin and returns quietly
        // when it cannot, so Reload Panel and Check for Updates would be rows that do nothing at
        // all. They used to be offered anyway, gated on the window alone.
        val actions = resolve(pluginId = null)

        assertNull(actions.reloadPanel)
        assertNull(actions.checkForUpdates)
        assertNull(actions.uninstallPlugin)
    }

    @Test
    fun `a plugin the manager refuses to unload keeps the row, disabled`() {
        val actions = resolve(uninstallable = false)

        assertNotNull(actions.uninstallPlugin, "the row is shown so its absence is not read as a missing feature")
        assertEquals(false, actions.uninstallEnabled)
    }

    @Test
    fun `the store-version action appears only for a build that is not the released one`() {
        assertNull(resolve().installStoreVersion, "nothing to go back to on the released build")

        PluginBuildRegistry.put(localBuild())
        val tagged = resolve()

        assertNotNull(tagged.installStoreVersion)
        assertEquals("1.0.3-debug", tagged.buildInfo?.displayVersion)
    }

    @Test
    fun `the evolver rows follow the tools the registry exposes`() {
        // The gate is the RBAC-filtered MCP registry rather than anything compiled in: evolver_open
        // is ungated, so Report Issue tracks the plugin being active, while Open Evolver tracks the
        // permission-gated evolver_evolve being exposed to this user at all.
        val none = resolve()
        assertNull(none.reportIssue)
        assertNull(none.openEvolver)

        exposeTools(EvolverContract.OPEN_TOOL)
        val openOnly = resolve()
        assertNotNull(openOnly.reportIssue)
        assertNull(openOnly.openEvolver, "a user who may not evolve is not offered the evolver")

        exposeTools(EvolverContract.OPEN_TOOL, EvolverContract.EVOLVE_TOOL)
        val both = resolve()
        assertNotNull(both.reportIssue)
        assertNotNull(both.openEvolver)
    }
}
