package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitDirection
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HomeNavigationTest {
    private class Stub(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo = FluckTabType

        @Composable
        override fun Content() = Unit
    }

    private fun registry() =
        TabRegistry().apply {
            registerTabType(FluckTabType) { config, ctx -> Stub(ctx, config) }
        }

    private fun tab(
        id: String,
        url: String,
        title: String = "Home",
    ) = FluckTabInfo(id = id, typeId = FluckTabType.typeId, _title = title, url = url)

    @Test
    fun `empty pane stays empty even without browser`() {
        val registry = TabRegistry()
        val state = SplitViewState(registry, windowId = "home-empty")
        assertEquals(state.activePanelId, goHome(state, registry))
        assertEquals(
            0,
            state
                .getPanel(state.activePanelId)!!
                .tabsComponent.tabsState.value.tabs.size,
        )
    }

    @Test
    fun `repeated navigation preserves work and reuses Home`() {
        val registry = registry()
        val state = SplitViewState(registry, windowId = "home-repeat")
        val component = state.getPanel(state.activePanelId)!!.tabsComponent
        component.addTab(tab("website", "https://example.com"))
        goHome(state, registry)
        goHome(state, registry)
        val tabs = component.tabsState.value
        assertEquals(2, tabs.tabs.size)
        assertEquals("website", tabs.tabs.first().id)
        assertEquals("Home", tabs.tabs[tabs.activeIndex].title)
        assertFalse(isHomeTab(tabs.tabs.first()))
    }

    @Test
    fun `uses current URL rather than original URL or mutable title`() {
        val registry = registry()
        val state = SplitViewState(registry, windowId = "home-url")
        val component = state.getPanel(state.activePanelId)!!.tabsComponent
        component.addTab(tab("left-home", "about:blank").updateNavigation("Home", "https://example.com"))
        component.addTab(tab("real-home", "https://example.org").updateNavigation("Renamed", "about:blank"))
        component.selectTab(0)
        goHome(state, registry)
        assertEquals(2, component.tabsState.value.tabs.size)
        assertEquals(1, component.tabsState.value.activeIndex)
    }

    @Test
    fun `does not steal another pane Home or consume pending split`() {
        val registry = registry()
        val state = SplitViewState(registry, windowId = "home-split")
        val original = state.activePanelId
        state.getPanel(original)!!.tabsComponent.addTab(tab("other-home", "about:blank"))
        val other =
            state.splitPanel(original, SplitOrientation.HORIZONTAL, tabToMove = tab("work", "https://example.com"))
        state.setActivePanel(other)
        state.requestSplitWithNewTab(original, SplitDirection.RIGHT)
        val pending = state.pendingSplit
        assertEquals(other, goHome(state, registry))
        assertEquals(other, state.activePanelId)
        assertEquals(2, state.getAllPanels().size)
        assertEquals(pending, state.pendingSplit)
        assertEquals(
            1,
            state
                .getPanel(original)!!
                .tabsComponent.tabsState.value.tabs.size,
        )
        assertEquals(
            2,
            state
                .getPanel(other)!!
                .tabsComponent.tabsState.value.tabs.size,
        )
    }

    @Test
    fun `missing provider cannot create Home or discard existing work`() {
        val registry = registry()
        val state = SplitViewState(registry, windowId = "home-missing")
        val component = state.getPanel(state.activePanelId)!!.tabsComponent
        component.addTab(tab("work", "https://example.com"))
        assertNull(goHome(state, TabRegistry()))
        assertEquals(
            listOf("work"),
            component.tabsState.value.tabs
                .map { it.id },
        )
    }

    @Test
    fun `navigation is isolated to supplied window`() {
        val registry = registry()
        val first = SplitViewState(registry, windowId = "home-first")
        val second = SplitViewState(registry, windowId = "home-second")
        first.getPanel(first.activePanelId)!!.tabsComponent.addTab(tab("first-work", "https://example.com"))
        second.getPanel(second.activePanelId)!!.tabsComponent.addTab(tab("second-work", "https://example.org"))
        assertNotNull(goHome(first, registry))
        assertEquals(
            1,
            second
                .getPanel(second.activePanelId)!!
                .tabsComponent.tabsState.value.tabs.size,
        )
    }
}
