package ai.rever.boss.components.sidebar

import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarResizeTest {
    private val settings = WindowAppearanceSettings(tabBarVerticalWidth = 240f, tabBarCollapsed = false)

    @Test
    fun `widening a legacy narrow sidebar does not collapse it`() {
        for (savedWidth in listOf(120f, 160f, 199f, 200f, 240f)) {
            val original = settings.copy(tabBarVerticalWidth = savedWidth)
            val requested = sidebarResizeStartWidth(savedWidth) + 10f
            val result = sidebarResizeResult(original, requested)
            assertFalse(result.tabBarCollapsed, "outward drag from $savedWidth")
            assertEquals(requested, result.tabBarVerticalWidth)
        }
    }

    @Test
    fun `inward drag crosses threshold and can reverse before release`() {
        val start = sidebarResizeStartWidth(160f)
        assertEquals(200f, start)
        assertTrue(sidebarResizeResult(settings, start - 10f).tabBarCollapsed)
        assertFalse(sidebarResizeResult(settings, start - 10f + 20f).tabBarCollapsed)
    }

    @Test
    fun `release below minimum collapses and preserves expanded width`() {
        val result = sidebarResizeResult(settings, SIDEBAR_RESIZE_COLLAPSE_WIDTH - 1f)
        assertTrue(result.tabBarCollapsed)
        assertEquals(240f, result.tabBarVerticalWidth)
        assertEquals(settings, result.copy(tabBarCollapsed = false))
    }

    @Test
    fun `release at minimum keeps the sidebar expanded`() {
        val result = sidebarResizeResult(settings, SIDEBAR_RESIZE_COLLAPSE_WIDTH)
        assertFalse(result.tabBarCollapsed)
        assertEquals(SIDEBAR_RESIZE_COLLAPSE_WIDTH, result.tabBarVerticalWidth)
    }

    @Test
    fun `resizing a revealed collapsed sidebar persists expanded state`() {
        val collapsed = settings.copy(tabBarCollapsed = true)
        for (width in listOf(SIDEBAR_RESIZE_COLLAPSE_WIDTH, 300f)) {
            val result = sidebarResizeResult(collapsed, width)
            assertEquals(settings.copy(tabBarVerticalWidth = width), result)
        }
    }

    @Test
    fun `drag can preview below minimum and reverse before release`() {
        assertEquals(80f, sidebarResizePreview(80f))
        assertEquals(44f, sidebarResizePreview(-50f))
        val result = sidebarResizeResult(settings, 220f)
        assertFalse(result.tabBarCollapsed)
        assertEquals(220f, result.tabBarVerticalWidth)
    }

    @Test
    fun `BossTerm threshold collapses widths that used to stay expanded`() {
        for (width in listOf(120f, 180f, 199f)) {
            val result = sidebarResizeResult(settings, width)
            assertTrue(result.tabBarCollapsed)
            assertEquals(240f, result.tabBarVerticalWidth)
        }
    }

    @Test
    fun `expanding respects maximum width`() {
        assertEquals(TabBarVerticalWidthRange.endInclusive, sidebarResizePreview(1000f))
        assertEquals(TabBarVerticalWidthRange.endInclusive, sidebarResizeResult(settings, 1000f).tabBarVerticalWidth)
    }
}
