package ai.rever.boss.components.model

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BossDraggableComponent.revealPlugin] is the verb the double-shift search uses to open a tool,
 * and it is deliberately not the icon's.
 *
 * Three behaviours live here, all reachable by typing a tool's name and pressing Enter, and all of
 * them were wrong when the search called [BossDraggableComponent.activatePlugin] directly:
 *
 * - a plugin-supplied `onClick` still wins, as it does from the icon;
 * - a tool already open as a main tab gets that tab focused, not a second copy in the sidebar;
 * - the default path SHOWS, and never toggles shut - "open the terminal" must not close it.
 *
 * The last one had nothing pinning it: reverting the `toggle &&` guard in `activatePlugin` used to
 * fail no test at all.
 */
class RevealPluginTest {
    private val panel = PanelId("codebase", 1)

    private fun sidebarItem(onClick: (() -> Unit)? = null) = SidebarItem(panel, Icons.Outlined.Tab, "Codebase", onClick)

    /**
     * A component whose sidebar holds exactly [item].
     *
     * `getDefaultSidebarMap` is what `itemsBySlot` is built from, so overriding it is enough to
     * give the component a real tool without a plugin or a composition - the same approach
     * `PanelIdResolutionTest` takes to `getAllPanels`.
     */
    private fun componentWith(item: SidebarItem): BossDraggableComponent =
        BossDraggableComponent(
            object : PanelRegistry() {
                override fun getDefaultSidebarMap(): Map<Panel, List<SidebarItem>> = mapOf(left.bottom to listOf(item))
            },
        )

    @Test
    fun `a tool already hosted as a tab has that tab focused, not reopened in the sidebar`() {
        // The case the KDoc calls the worst one to get wrong: for the JxBrowser-backed panels a
        // BrowserViewState can only attach to one Compose view, which is what
        // PANEL_DISPOSAL_DELAY_MS exists for.
        val item = sidebarItem()
        val component = componentWith(item)
        component.markHostedAsTab(panel)

        component.revealPlugin("codebase")

        assertEquals(panel, component.pendingFocusHostedTab)
        assertFalse(component.isSelected(item), "it must not also be opened in the sidebar")
    }

    @Test
    fun `revealing an already-visible tool leaves it visible`() {
        val item = sidebarItem()
        val component = componentWith(item)
        component.activatePlugin("codebase")
        assertTrue(component.isSelected(item), "precondition: the panel is showing")

        component.revealPlugin("codebase")

        assertTrue(component.isSelected(item), "a search asks for a thing; it must not close it")
    }

    @Test
    fun `the icon still toggles, which is why reveal has to be a different verb`() {
        // Establishes that the behaviour above is not vacuous: the same call the icon makes DOES
        // hide a visible panel, so `toggle = false` is carrying real weight.
        val item = sidebarItem()
        val component = componentWith(item)
        component.activatePlugin("codebase")

        component.activatePlugin("codebase")

        assertFalse(component.isSelected(item))
    }

    @Test
    fun `revealing a hidden tool shows it`() {
        val item = sidebarItem()
        val component = componentWith(item)

        component.revealPlugin("codebase")

        assertTrue(component.isSelected(item))
    }

    @Test
    fun `a plugin-supplied onClick wins and the panel is left alone`() {
        // handleSidebarItemClick is explicit that a custom click "always wins", and the tools
        // launcher advertises honouring it. A plugin that takes over its own click owns what
        // clicking means, including whether it toggles.
        var clicked = 0
        val item = sidebarItem(onClick = { clicked++ })
        val component = componentWith(item)

        component.revealPlugin("codebase")

        assertEquals(1, clicked)
        assertFalse(component.isSelected(item), "the default show path must not also run")
    }

    @Test
    fun `an unknown panel id does nothing and does not throw`() {
        // activatePlugin owns the "no such panel" warning; revealPlugin falls through to it rather
        // than duplicating the fence.
        val component = componentWith(sidebarItem())

        component.revealPlugin("not-a-panel")

        assertNull(component.pendingFocusHostedTab)
    }
}
