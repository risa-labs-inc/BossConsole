package ai.rever.boss

import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.plugin.TabUpdateRegistry
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises the drop-path branching in [handleTabDropResult] — the logic-heavy part of the
 * cross-panel tab move:
 *
 * - MoveToPanel transfers the LIVE component instance to the target panel (no destroy, no
 *   recreate-from-config, which would reload a browser tab).
 * - A tab closed mid-drag drops the move instead of resurrecting the tab from the stale
 *   drag-start snapshot.
 * - Cross-panel CreateSplit (edge drop) moves the live component into the new split panel;
 *   same-panel CreateSplit keeps the pre-existing copy semantics (original retained).
 */
class TabDropResultHandlingTest {
    private object DropTestTabType : TabTypeInfo {
        override val typeId = TabTypeId("drop-test", "test.plugin")
        override val displayName = "Drop Test"
        override val icon = Icons.Outlined.Language
    }

    private data class DropTestTabInfo(
        override val id: String,
        override val typeId: TabTypeId = DropTestTabType.typeId,
        override val title: String = "Drop Test Tab",
    ) : TabInfo {
        override val icon get() = Icons.Outlined.Language
    }

    private class DropTestComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = DropTestTabType

        @Composable
        override fun Content() {
        }
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(DropTestTabType) { config, ctx -> DropTestComponent(ctx, config) }
        }

    private fun newSplitViewState() = SplitViewState(tabRegistry, windowId = "test-window")

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    @Test
    fun `MoveToPanel transfers the live component and activates the target panel`() {
        val state = newSplitViewState()
        val targetPanelId = state.splitPanel("main", SplitOrientation.VERTICAL)
        val main = state.getPanel("main")!!.tabsComponent
        val target = state.getPanel(targetPanelId)!!.tabsComponent
        val tab = DropTestTabInfo(id = "tab-1")
        main.addTab(tab)
        val component = main.getComponentById("tab-1")!!

        handleTabDropResult(
            TabDropResult.MoveToPanel(
                tabInfo = tab,
                sourcePanelId = "main",
                sourceIndex = 0,
                targetPanelId = targetPanelId,
            ),
            state,
        )

        assertTrue(
            main.tabsState.value.tabs
                .isEmpty(),
        )
        assertSame(component, target.getComponentById("tab-1"))
        assertEquals(
            "tab-1",
            target.tabsState.value.activeTab
                ?.id,
        )
        assertEquals(targetPanelId, state.activePanelId)
    }

    @Test
    fun `MoveToPanel drops the move when the tab was closed mid-drag`() {
        val state = newSplitViewState()
        val targetPanelId = state.splitPanel("main", SplitOrientation.VERTICAL)
        val main = state.getPanel("main")!!.tabsComponent
        val target = state.getPanel(targetPanelId)!!.tabsComponent
        val tab = DropTestTabInfo(id = "tab-1")
        main.addTab(tab)
        main.removeTabById("tab-1") // closed while the drag was in flight

        handleTabDropResult(
            TabDropResult.MoveToPanel(
                tabInfo = tab,
                sourcePanelId = "main",
                sourceIndex = 0,
                targetPanelId = targetPanelId,
            ),
            state,
        )

        // Not resurrected from the stale drag-start snapshot.
        assertTrue(
            target.tabsState.value.tabs
                .isEmpty(),
        )
        assertTrue(
            main.tabsState.value.tabs
                .isEmpty(),
        )
    }

    @Test
    fun `cross-panel CreateSplit moves the live component into the new split`() {
        val state = newSplitViewState()
        val otherPanelId = state.splitPanel("main", SplitOrientation.VERTICAL)
        val main = state.getPanel("main")!!.tabsComponent
        val tab = DropTestTabInfo(id = "tab-1")
        main.addTab(tab)
        val component = main.getComponentById("tab-1")!!
        val panelsBefore = state.getAllPanels().map { it.id }.toSet()

        // Drag the tab from "main" onto an edge of the OTHER panel -> new split there.
        handleTabDropResult(
            TabDropResult.CreateSplit(
                tabInfo = tab,
                sourcePanelId = "main",
                sourceIndex = 0,
                targetPanelId = otherPanelId,
                orientation = SplitOrientation.HORIZONTAL,
            ),
            state,
        )

        val newPanelId = state.getAllPanels().map { it.id }.single { it !in panelsBefore }
        val newPanel = state.getPanel(newPanelId)!!.tabsComponent
        assertSame(component, newPanel.getComponentById("tab-1"))
        assertTrue(
            main.tabsState.value.tabs
                .isEmpty(),
        )
    }

    @Test
    fun `same-panel CreateSplit keeps copy semantics - original retained and not detached`() {
        val state = newSplitViewState()
        val main = state.getPanel("main")!!.tabsComponent
        val tab = DropTestTabInfo(id = "tab-1")
        main.addTab(tab)
        val original = main.getComponentById("tab-1")!!
        val panelsBefore = state.getAllPanels().map { it.id }.toSet()

        // Edge drop within the tab's own panel: deliberately NOT a move.
        handleTabDropResult(
            TabDropResult.CreateSplit(
                tabInfo = tab,
                sourcePanelId = "main",
                sourceIndex = 0,
                targetPanelId = "main",
                orientation = SplitOrientation.VERTICAL,
            ),
            state,
        )

        // The original stays in the source panel with its live component untouched,
        // and the new split received a separately-created copy.
        assertSame(original, main.getComponentById("tab-1"))
        assertEquals(
            listOf("tab-1"),
            main.tabsState.value.tabs
                .map { it.id },
        )
        val newPanelId = state.getAllPanels().map { it.id }.single { it !in panelsBefore }
        val copied = state.getPanel(newPanelId)!!.tabsComponent
        assertEquals(1, copied.tabsState.value.tabs.size)
        assertTrue(
            copied.getComponentById(
                copied.tabsState.value.tabs
                    .single()
                    .id,
            ) !== original,
        )
    }

    @Test
    fun `cross-panel CreateSplit drops the move when the tab was closed mid-drag`() {
        val state = newSplitViewState()
        val otherPanelId = state.splitPanel("main", SplitOrientation.VERTICAL)
        val main = state.getPanel("main")!!.tabsComponent
        val tab = DropTestTabInfo(id = "tab-1")
        main.addTab(tab)
        main.removeTabById("tab-1") // closed while the drag was in flight
        val panelsBefore = state.getAllPanels().map { it.id }.toSet()

        handleTabDropResult(
            TabDropResult.CreateSplit(
                tabInfo = tab,
                sourcePanelId = "main",
                sourceIndex = 0,
                targetPanelId = otherPanelId,
                orientation = SplitOrientation.HORIZONTAL,
            ),
            state,
        )

        // No new split created for a vanished tab.
        assertEquals(panelsBefore, state.getAllPanels().map { it.id }.toSet())
    }
}
