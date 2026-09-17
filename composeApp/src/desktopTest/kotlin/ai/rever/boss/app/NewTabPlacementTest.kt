package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitDirection
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NewTabPlacementTest {
    private object Tool : TabTypeInfo {
        override val typeId = TabTypeId("placement-test", "test.plugin")
        override val displayName = "Placement Test"
        override val icon = Icons.Outlined.Language
    }

    private data class TestTab(
        override val id: String,
    ) : TabInfo {
        override val typeId = Tool.typeId
        override val title = id
        override val icon = Tool.icon
    }

    private class TestComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo = Tool

        @Composable
        override fun Content() = Unit
    }

    private fun state(): SplitViewState {
        val registry =
            TabRegistry().apply {
                registerTabType(Tool) { tab, ctx -> TestComponent(ctx, tab) }
            }
        return SplitViewState(registry, windowId = "source-window").also {
            it.findPanel("main")!!.tabsComponent.addTab(TestTab("existing"))
        }
    }

    private fun SplitViewState.panelContaining(tabId: String) =
        getAllPanels().singleOrNull { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .any { it.id == tabId }
        }

    @Test
    fun `removed Home pane still creates exactly one tab with the requested split direction`() {
        for (direction in SplitDirection.entries) {
            val state = state()
            val fallback = state.findPanel("main")!!.tabsComponent
            val source = state.splitPanel("main", SplitOrientation.VERTICAL, tabToMove = TestTab("home"))
            state.requestSplitWithNewTab(source, direction)
            state.closePanel(source)
            assertNull(state.findPanel(source))

            placeNewTab(state, TestTab("created"), source, fallback)

            val createdPanel = assertNotNull(state.panelContaining("created"))
            val expectedOrder =
                if (direction.placeBefore) listOf(createdPanel.id, "main") else listOf("main", createdPanel.id)
            assertEquals(expectedOrder, state.getAllPanels().map { it.id })
            assertEquals(direction.orientation == SplitOrientation.VERTICAL, state.rootNode is SplitNode.VerticalSplit)
            assertEquals(
                1,
                state.getAllPanels().sumOf {
                    it.tabsComponent.tabsState.value.tabs
                        .count { tab -> tab.id == "created" }
                },
            )
            assertNull(state.pendingSplit)
            assertEquals(
                "existing",
                fallback.tabsState.value.tabs
                    .single()
                    .id,
            )

            // A consumed split must not affect the next ordinary dialog request.
            placeNewTab(state, TestTab("next"), null, fallback)
            assertEquals(2, state.getAllPanels().size)
            assertNotNull(state.panelContaining("next"))
        }
    }

    @Test
    fun `existing captured pane wins over later active pane for ordinary creation`() {
        val state = state()
        val fallback = state.findPanel("main")!!.tabsComponent
        val source = state.splitPanel("main", SplitOrientation.VERTICAL, tabToMove = TestTab("home"))
        state.setActivePanel("main")

        placeNewTab(state, TestTab("created"), source, fallback)

        assertEquals(source, state.panelContaining("created")?.id)
        assertEquals(2, state.getAllPanels().size)
    }

    @Test
    fun `removed captured pane falls back to active pane without a split`() {
        val state = state()
        val fallback = state.findPanel("main")!!.tabsComponent
        val source = state.splitPanel("main", SplitOrientation.VERTICAL, tabToMove = TestTab("home"))
        state.closePanel(source)

        placeNewTab(state, TestTab("created"), source, fallback)

        assertEquals("main", state.panelContaining("created")?.id)
        assertEquals(1, state.getAllPanels().size)
    }
}
