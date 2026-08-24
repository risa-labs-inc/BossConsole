package ai.rever.boss.components.plugin

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelMenuContribution
import ai.rever.boss.plugin.api.PanelMenuItem
import ai.rever.boss.window.LocalWindowId
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one menu definition behind the panel header's kebab, the header's right-click menu and the
 * sidebar rail icon's right-click menu.
 *
 * Asserted as a LIST rather than through a drawn menu, because what goes wrong here is structural
 * and invisible to a "does this row exist" check: a divider with nothing under it, two rules in a
 * row, a disabled row that is actually enabled, an order that puts the build rows somewhere other
 * than first. The rendering of these rows is covered by BossPanelTopBarMenuTest and
 * SidebarIconMenuTest.
 */
class PanelMenuItemsTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val CONTRIBUTION = "test:menu"
        val PANEL = PanelId("probe", 1)

        /** What the rail passes as its trailing rows. */
        val SIDEBAR_ROW = listOf(ContextMenuItem(text = "Sidebar settings"))
    }

    @After
    fun clearRegistry() {
        PanelMenuRegistryImpl.unregister(CONTRIBUTION)
    }

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

    private fun build(
        actions: PanelMenuActions = PanelMenuActions(),
        onOpenAsTab: (() -> Unit)? = null,
        onMinimize: (() -> Unit)? = null,
        trailingItems: List<ContextMenuItem> = emptyList(),
    ): List<ContextMenuItem> {
        var items: List<ContextMenuItem> = emptyList()
        compose.setContent {
            CompositionLocalProvider(LocalWindowId provides "window-1") {
                items =
                    panelMenuItems(
                        panelId = PANEL,
                        actions = actions,
                        onOpenAsTab = onOpenAsTab,
                        onMinimize = onMinimize,
                        trailingItems = trailingItems,
                    )
            }
        }
        compose.waitForIdle()
        return items
    }

    private fun taggedBuild() =
        PluginBuildInfo(
            pluginId = "p",
            displayName = "Probe",
            version = "1.0.3",
            signedBytes = false,
            storeSourced = false,
            reloadStamp = null,
        )

    private fun List<ContextMenuItem>.labels() = filter { !it.isDivider }.map { it.text }

    @Test
    fun `the build rows come first and Minimize last, which is what the header shows`() {
        val items =
            build(
                actions =
                    PanelMenuActions(
                        buildInfo = taggedBuild(),
                        installStoreVersion = {},
                        reloadPanel = {},
                        checkForUpdates = {},
                    ),
                onOpenAsTab = {},
                onMinimize = {},
            )

        assertEquals(
            listOf(
                "Version 1.0.3-debug",
                "Install Store Version",
                "Reload Panel",
                "Check for Updates",
                "Open as Tab",
                "Minimize",
            ),
            items.labels(),
        )
    }

    @Test
    fun `an action that is not offered leaves no row behind`() {
        // Every callback null is the no-window case, and the menu is then only what the caller adds.
        val items = build(trailingItems = SIDEBAR_ROW)

        assertEquals(listOf("Sidebar settings"), items.labels())
        assertFalse(items.first().isDivider, "nothing to separate, so no leading rule")
    }

    @Test
    fun `an unremovable plugin's uninstall row is present and disabled`() {
        val items =
            build(actions = PanelMenuActions(uninstallPlugin = {}, uninstallEnabled = false))

        val uninstall = items.single { it.text == "Uninstall Plugin" }
        assertFalse(uninstall.enabled, "a system plugin's row is shown so its absence is not read as a missing feature")
    }

    @Test
    fun `a contribution whose items are all disabled adds neither a row nor a divider`() {
        // The regression: the divider was decided from the raw entry list and the disabled items
        // dropped inside the loop, so this left a rule with nothing under it.
        contribute(PanelMenuItem(id = "a", label = "Run Diagnostics", enabled = false))

        val items = build(actions = PanelMenuActions(reloadPanel = {}))

        assertEquals(listOf("Reload Panel"), items.labels())
        assertTrue(items.none { it.isDivider }, "an empty contribution should add no rule: $items")
    }

    @Test
    fun `a contribution with something enabled gets its divider`() {
        contribute(
            PanelMenuItem(id = "a", label = "Hidden", enabled = false),
            PanelMenuItem(id = "b", label = "Run Diagnostics"),
        )

        val items = build(actions = PanelMenuActions(reloadPanel = {}), onMinimize = {})

        assertEquals(listOf("Reload Panel", "Run Diagnostics", "Minimize"), items.labels())
        assertEquals(1, items.count { it.isDivider })
    }

    @Test
    fun `the trailing rows never follow a second rule`() {
        // The reported shape, end to end: an all-disabled contribution used to add a rule, which
        // then counted as "something to separate" for the rail's trailing row - so the menu ended
        // in two rules and then "Sidebar settings".
        contribute(PanelMenuItem(id = "a", label = "Hidden", enabled = false))

        val items = build(actions = PanelMenuActions(reloadPanel = {}), trailingItems = SIDEBAR_ROW)

        val adjacent = items.zipWithNext().any { (a, b) -> a.isDivider && b.isDivider }
        assertFalse(adjacent, "two dividers in a row: $items")
        assertEquals(listOf("Reload Panel", "Sidebar settings"), items.labels())
        assertEquals("Sidebar settings", items.last().text)
    }
}
