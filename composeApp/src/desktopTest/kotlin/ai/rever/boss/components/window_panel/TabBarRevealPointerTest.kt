@file:Suppress("PackageNaming") // Mirrors the existing window_panel package.

package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.edgeRevealTracking
import ai.rever.boss.components.window_panel.components.main_window_panels.pointerReachesSidebarEdge
import ai.rever.boss.components.window_panel.components.main_window_panels.pointerWithinSidebarMargin
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberTabBarRevealState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntRect
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabBarRevealPointerTest {
    @get:Rule
    val rule = createComposeRule()

    private val region = IntRect(40, 20, 1000, 800)

    @Test
    fun `window edge activates even when sidebar starts after plugin columns`() {
        val window = IntRect(0, 0, 1000, 800)
        assertFalse(pointerReachesSidebarEdge(1, 100, 40, window))
        assertTrue(pointerReachesSidebarEdge(0, 100, 1, window))
        assertTrue(pointerReachesSidebarEdge(-2, 100, 1, window))
        assertTrue(pointerReachesSidebarEdge(2, 100, -2, window))
        assertFalse(pointerReachesSidebarEdge(40, 100, 50, window))
        assertTrue(pointerWithinSidebarMargin(380, window, region.left + 240f))
        assertFalse(pointerWithinSidebarMargin(381, window, region.left + 240f))
    }

    @Test
    fun `fullscreen turns the edge tracker off, as #1753 did for the hover strip`() {
        assertTrue(edgeRevealTracking(railShown = true, hoverExpand = true, fullscreen = false))
        assertFalse(edgeRevealTracking(railShown = true, hoverExpand = true, fullscreen = true))
        assertFalse(edgeRevealTracking(railShown = false, hoverExpand = true, fullscreen = false))
        assertFalse(edgeRevealTracking(railShown = true, hoverExpand = false, fullscreen = false))
    }

    @Test
    fun `margin holds an edge reveal but cannot initiate one`() {
        lateinit var state: TabBarRevealState
        rule.setContent { state = rememberTabBarRevealState(true, false, true) }
        rule.runOnIdle { state.pointerInRevealArea = true }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { assertFalse(state.drawerVisible) }

        rule.runOnIdle { state.pointerAtEdge = true }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(state.drawerVisible) }
        rule.runOnIdle { state.pointerAtEdge = false }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { assertTrue(state.drawerVisible) }

        rule.runOnIdle { state.pointerInRevealArea = false }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { assertFalse(state.drawerVisible) }
    }

    @Test
    fun `approaching the rail does not reveal until reaching or crossing its edge`() {
        assertFalse(pointerReachesSidebarEdge(41, 100, 80, region))
        assertFalse(pointerReachesSidebarEdge(60, 100, 80, region))
        assertTrue(pointerReachesSidebarEdge(40, 100, 41, region))
        assertTrue(pointerReachesSidebarEdge(39, 100, 41, region))
        assertFalse(pointerReachesSidebarEdge(39, 100, 38, region))
        assertFalse(pointerReachesSidebarEdge(40, 19, 41, region))
    }

    @Test
    fun `drawer stays open through exactly 100dp past its right edge`() {
        assertTrue(pointerWithinSidebarMargin(280, region, 240f))
        assertTrue(pointerWithinSidebarMargin(379, region, 240f))
        assertTrue(pointerWithinSidebarMargin(380, region, 240f))
        assertFalse(pointerWithinSidebarMargin(381, region, 240f))
    }

    @Test
    fun `leaving through the window left edge holds the reveal until returning past its right margin`() {
        val window = IntRect(0, 0, 1000, 800)
        lateinit var state: TabBarRevealState
        rule.setContent { state = rememberTabBarRevealState(true, false, true) }
        rule.runOnIdle {
            state.pointerAtEdge = pointerReachesSidebarEdge(-2, 100, 1, window)
            state.pointerInRevealArea = pointerWithinSidebarMargin(-2, window, 240f)
        }
        rule.waitForIdle()
        rule.runOnIdle { state.pointerAtEdge = false }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertTrue(state.drawerVisible) }
        rule.runOnIdle {
            state.pointerInRevealArea = pointerWithinSidebarMargin(-500, window, 240f)
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertTrue(state.drawerVisible) }
        rule.runOnIdle {
            state.pointerInRevealArea = pointerWithinSidebarMargin(341, window, 240f)
        }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { assertFalse(state.drawerVisible) }
    }
}
