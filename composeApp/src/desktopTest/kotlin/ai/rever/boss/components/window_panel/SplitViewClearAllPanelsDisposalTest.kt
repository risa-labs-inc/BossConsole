@file:Suppress("PackageNaming")

package ai.rever.boss.components.window_panel

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1310: clearAllPanels replaces the split tree wholesale, which used to leave the outgoing
 * tree's tabs undisposed - their lifecycles never fired, so browser/terminal tabs kept their
 * processes alive after the tree was dropped. Disposal now runs for every panel of an
 * outgoing tree UNLESS the tree is still held by preserveCurrentState, which keeps it alive
 * on purpose for switch-back.
 */
class SplitViewClearAllPanelsDisposalTest {
    private object DisposeTabType : TabTypeInfo {
        override val typeId = TabTypeId("dispose-test", "test.plugin")
        override val displayName = "Dispose Test"
        override val icon = Icons.Outlined.Language
    }

    private data class DisposeTabInfo(
        override val id: String,
        override val typeId: TabTypeId = DisposeTabType.typeId,
        override val title: String = "Dispose Tab",
    ) : TabInfo {
        override val icon get() = Icons.Outlined.Language
    }

    private val createdComponents = mutableListOf<DisposeTabComponent>()

    private inner class DisposeTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = DisposeTabType
        var destroyCount = 0

        init {
            lifecycle.subscribe(
                callbacks =
                    object : Lifecycle.Callbacks {
                        override fun onDestroy() {
                            destroyCount++
                        }
                    },
            )
        }

        @Composable
        override fun Content() = Unit
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(DisposeTabType) { config, ctx ->
                DisposeTabComponent(ctx, config).also { createdComponents.add(it) }
            }
        }

    private fun newSplitViewState() = SplitViewState(tabRegistry, windowId = "test-window")

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    @Test
    fun `clearAllPanels destroys outgoing tree tabs when no preserved state holds it`() {
        val state = newSplitViewState()
        val panel = state.getPanel(state.activePanelId)!!
        panel.tabsComponent.addTab(DisposeTabInfo(id = "doomed-a"))
        panel.tabsComponent.addTab(DisposeTabInfo(id = "doomed-b"))
        assertEquals(2, createdComponents.size)

        state.clearAllPanels()

        assertEquals(
            listOf(1, 1),
            createdComponents.map { it.destroyCount },
            "both outgoing tabs must have been destroyed exactly once",
        )
        assertEquals("main", state.activePanelId)
        assertTrue(
            state
                .getPanel("main")!!
                .tabsComponent.tabsState.value.tabs
                .isEmpty(),
            "the fresh main panel starts empty",
        )
    }

    @Test
    fun `clearAllPanels keeps a preserved outgoing tree alive for switch-back`() {
        val state = newSplitViewState()
        // preserveCurrentState records the tree you LEAVE under the workspace you
        // leave it from - a fresh SplitViewState has no current id yet, so enter one
        // first the way the transfer fixture does, add the tab, then leave it.
        state.preserveCurrentState("ws-first")
        state.getPanel(state.activePanelId)!!.tabsComponent.addTab(DisposeTabInfo(id = "kept"))
        state.preserveCurrentState("ws-switch", workspaceName = "ws-switch")

        state.clearAllPanels()

        assertEquals(
            listOf(0),
            createdComponents.map { it.destroyCount },
            "a tree held by preserveCurrentState must not be disposed",
        )
        assertEquals(
            listOf("kept"),
            state.panelsInWorkspace("ws-first").flatMap { panel ->
                panel.tabsComponent.tabsState.value.tabs
                    .map { tab -> tab.id }
            },
        )
    }

    @Test
    fun `clearAllPanels disposes every panel of a split outgoing tree`() {
        val state = newSplitViewState()
        state.getPanel(state.activePanelId)!!.tabsComponent.addTab(DisposeTabInfo(id = "left"))
        val right = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(right)!!.tabsComponent.addTab(DisposeTabInfo(id = "right"))
        assertEquals(2, createdComponents.size)

        state.clearAllPanels()

        assertEquals(listOf(1, 1), createdComponents.map { it.destroyCount })
        assertEquals("main", state.activePanelId)
    }
}
