package ai.rever.boss.components.sidebar

import ai.rever.boss.components.buttons.DraggableActionButton
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.NativeContextMenuTestOverride
import ai.rever.boss.components.plugin.LocalPanelPluginIdResolver
import ai.rever.boss.components.plugin.LocalPluginUninstallable
import ai.rever.boss.components.plugin.PluginBuildInfo
import ai.rever.boss.components.plugin.PluginBuildRegistry
import ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelMenuContribution
import ai.rever.boss.plugin.api.PanelMenuItem
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.window.LocalWindowId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * What right-clicking a plugin's icon in the sidebar rail offers.
 *
 * The rail icon is the only handle a plugin has while its panel is closed, and it used to offer a
 * one-item menu ("Sidebar settings") - reloading, updating or uninstalling a plugin meant opening
 * its panel first just to reach the header's "…" kebab. These pin that the icon now opens the
 * panel's own menu, including the rows that only exist for a resolvable plugin, and that the
 * sidebar's own row is still there behind it.
 */
class SidebarIconMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val LABEL = "Probe"
        const val WINDOW = "window-1"
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val CONTRIBUTION = "$PLUGIN:menu"
        val PANEL = PanelId("probe", 1)
    }

    /**
     * The rows are found as Compose nodes, so this must run against the drawn menu. A native menu is
     * an OS window with no Compose tree, which would make these fail on macOS and pass on CI for a
     * reason unrelated to what they assert.
     */
    @Before
    fun useDrawnMenu() {
        NativeContextMenuTestOverride.enabled = false
    }

    @After
    fun clearRegistries() {
        NativeContextMenuTestOverride.enabled = null
        PluginBuildRegistry.reset()
        PanelMenuRegistryImpl.unregister(CONTRIBUTION)
    }

    /** A build that is not the released one, so the version rows apply. */
    private fun localBuild() =
        PluginBuildInfo(
            pluginId = PLUGIN,
            displayName = LABEL,
            version = "1.0.3",
            signedBytes = false,
            storeSourced = false,
            reloadStamp = null,
        )

    /** A contribution whose items this test controls, targeting this panel only. */
    private fun contribute(vararg items: PanelMenuItem) {
        PanelMenuRegistryImpl.register(
            object : PanelMenuContribution {
                override val contributionId = CONTRIBUTION
                override val targetPanels = setOf(PANEL.panelId)

                override fun items(panelId: PanelId) = items.toList()

                override fun onItemClick(
                    panelId: PanelId,
                    itemId: String,
                    windowId: String?,
                ) = Unit
            },
        )
    }

    private fun showIcon(
        windowId: String? = WINDOW,
        pluginId: String? = null,
        uninstallable: Boolean = true,
    ) {
        val model = BossDraggableComponent(PanelRegistry())
        val item = SidebarItem(PANEL, Icons.Default.Extension, LABEL)
        compose.setContent {
            CompositionLocalProvider(
                LocalWindowId provides windowId,
                LocalPanelPluginIdResolver provides { pluginId },
                LocalPluginUninstallable provides { uninstallable },
            ) {
                with(model) {
                    DraggableActionButton(item = item, slot = left.top.top)
                }
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(LABEL).performMouseInput { rightClick() }
    }

    @Test
    fun `the icon's menu offers the plugin's own actions, not only the sidebar's`() {
        showIcon()
        openMenu()

        compose.onNodeWithText("Reload Panel").assertExists()
        compose.onNodeWithText("Check for Updates").assertExists()
        compose.onNodeWithText("Open as Tab").assertExists()
    }

    @Test
    fun `a resolvable plugin brings its uninstall row to the rail`() {
        // Everything gated on the OWNING PLUGIN rather than on the panel: without a resolver the
        // menu still has rows, so asserting only the ones above would pass with this half missing.
        showIcon(pluginId = PLUGIN)
        openMenu()

        compose.onNodeWithText("Uninstall Plugin").assertExists()
    }

    @Test
    fun `an unremovable plugin's uninstall row is still shown, not hidden`() {
        // Same rule as the header: hiding it for a system plugin reads as the feature missing. That
        // it is inert is asserted where the flag is visible, in PanelMenuItemsTest - the drawn row
        // greys out rather than carrying disabled semantics.
        showIcon(pluginId = PLUGIN, uninstallable = false)
        openMenu()

        compose.onNodeWithText("Uninstall Plugin").assertExists()
    }

    @Test
    fun `a local build brings both version rows to the rail`() {
        PluginBuildRegistry.put(localBuild())
        showIcon(pluginId = PLUGIN)
        openMenu()

        compose.onNodeWithText("Version 1.0.3-debug").assertExists()
        compose.onNodeWithText("Install Store Version").assertExists()
    }

    @Test
    fun `the plugin's contributed rows reach the rail too`() {
        contribute(PanelMenuItem(id = "diagnose", label = "Run Diagnostics"))
        showIcon(pluginId = PLUGIN)
        openMenu()

        compose.onNodeWithText("Run Diagnostics").assertExists()
    }

    @Test
    fun `a contribution with nothing enabled contributes no row`() {
        // The divider used to be decided before the disabled items were dropped, so an all-disabled
        // contribution left a rule with nothing under it - and on the rail that rule then counted as
        // "something to separate", so the menu ended in two rules and then "Sidebar settings". The
        // divider count itself is asserted in PanelMenuItemsTest, where the list is visible.
        contribute(PanelMenuItem(id = "diagnose", label = "Run Diagnostics", enabled = false))
        showIcon(pluginId = PLUGIN)
        openMenu()

        compose.onNodeWithText("Run Diagnostics").assertDoesNotExist()
        compose.onNodeWithText("Sidebar settings").assertExists()
    }

    @Test
    fun `the sidebar's own row survives, after the plugin's`() {
        showIcon()
        openMenu()

        compose.onNodeWithText("Sidebar settings").assertExists()
    }

    @Test
    fun `no Minimize row, since the menu opens just as often with the panel closed`() {
        // The icon's own click already toggles the panel. A Minimize row would be the only item in
        // this menu that silently does nothing for the state the rail is most often in.
        showIcon()
        openMenu()

        compose.onNodeWithText("Minimize").assertDoesNotExist()
    }

    @Test
    fun `outside a tracked window the panel rows are left out rather than shown inert`() {
        // LocalWindowId defaults to null wherever the rail is not hosted in a tracked window, and
        // every one of these actions routes through a window. A row named as an imperative that
        // does nothing is worse than no row.
        showIcon(windowId = null)
        openMenu()

        compose.onNodeWithText("Reload Panel").assertDoesNotExist()
        compose.onNodeWithText("Sidebar settings").assertExists()
    }
}
