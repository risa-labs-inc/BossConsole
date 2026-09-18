package ai.rever.boss.components.home

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalSplitViewState
import ai.rever.boss.components.plugin.tab_types.registerPanelHostTab
import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.window_panel.ProcessPendingFocusHostedTab
import ai.rever.boss.components.window_panel.ProcessPendingPromoteToTab
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.plugin.api.TabRegistry
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.ComponentContext
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomsNavigationIntegrationTest {
    @get:Rule val rule = createComposeRule()

    private class RoomsWindowFixture(
        val name: String,
    ) {
        val registry = PanelRegistry()
        val model = BossDraggableComponent(registry)
        var created = 0
        val info =
            object : PanelInfo {
                override val id = PanelId("rooms", 65)
                override val displayName = "Rooms"
                override val icon = Icons.Outlined.Forum
                override val defaultSlotPosition = left.bottom
                override val sidebarItem get() = SidebarItem(id, icon, displayName) { model.requestOpenAsTab(id) }
            }
        val store = PanelComponentStore(registry)
        val tabs = TabRegistry().apply { registerPanelHostTab(store, model) }
        val split = SplitViewState(tabs, windowId = name)

        init {
            registry.registerPanel(info) { ctx, panel ->
                created++
                object : PanelComponentWithUI, ComponentContext by ctx {
                    override val panelInfo = panel

                    @Composable override fun Content() = Unit
                }
            }
        }

        @Composable fun Content() {
            model.ProcessPendingPromoteToTab(split, store)
            model.ProcessPendingFocusHostedTab(split)
            CompositionLocalProvider(
                LocalPanelRegistry provides registry,
                LocalSplitViewState provides split,
            ) {
                RoomsNavigationButton()
            }
        }

        fun allTabs() = split.getAllPanels().flatMap { it.tabsComponent.tabsState.value.tabs }
    }

    @Test fun `opening uses active pane and reuses cached tab per window`() {
        val first = RoomsWindowFixture("rooms-first")
        val second = RoomsWindowFixture("rooms-second")
        rule.setContent {
            Row {
                first.Content()
                second.Content()
            }
        }
        val original = first.split.activePanelId
        var target = original
        rule.runOnIdle {
            target = first.split.splitPanel(original, SplitOrientation.HORIZONTAL)
            first.split.setActivePanel(target)
        }
        rule.onAllNodesWithContentDescription("Rooms")[0].performClick()
        rule.runOnIdle {
            assertEquals(1, first.allTabs().size)
            assertTrue(second.allTabs().isEmpty())
            assertTrue(
                first.split
                    .getPanel(original)!!
                    .tabsComponent.tabsState.value.tabs
                    .isEmpty(),
            )
            assertEquals(
                1,
                first.split
                    .getPanel(target)!!
                    .tabsComponent.tabsState.value.tabs.size,
            )
            first.split.setActivePanel(original)
        }
        rule.onAllNodesWithContentDescription("Rooms")[0].performClick()
        rule.runOnIdle {
            assertEquals(target, first.split.activePanelId)
            assertEquals(1, first.allTabs().size)
            first.split
                .getPanel(target)!!
                .tabsComponent
                .removeTab(0)
        }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Rooms")[0].performClick()
        rule.runOnIdle {
            assertEquals(1, first.allTabs().size)
            assertEquals(1, first.created)
        }
        rule.onAllNodesWithContentDescription("Rooms")[1].performClick()
        rule.runOnIdle {
            assertEquals(1, second.allTabs().size)
            assertEquals(1, first.allTabs().size)
        }
    }

    @Test fun `Rooms stays discoverable when removed from utility rail and search repeatedly reveals its tab`() {
        val window = RoomsWindowFixture("rooms-search")
        rule.setContent { window.Content() }
        rule.runOnIdle {
            assertTrue(window.model.getItemsForSlot(left.bottom, emptySet()).none { it.id == window.info.id.panelId })
            assertTrue(window.model.getItemsForSlotUnfiltered(left.bottom).any { it.id == window.info.id.panelId })
            window.model.revealPlugin(window.info.id.panelId)
        }
        rule.runOnIdle {
            assertEquals(1, window.allTabs().size)
            window.model.revealPlugin(window.info.id.panelId)
        }
        rule.runOnIdle { assertEquals(1, window.allTabs().size) }
    }
}
