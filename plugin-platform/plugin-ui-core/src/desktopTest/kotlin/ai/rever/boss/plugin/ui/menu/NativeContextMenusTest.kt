package ai.rever.boss.plugin.ui.menu

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the parts a headless CI run can actually see: the menu plan, invoker selection, the
 * dismissal predicate and the platform gate.
 *
 * No AWT menu construction here on purpose - `java.awt.MenuComponent` throws `HeadlessException`,
 * which is exactly why [planNativeMenu] and [pickInvoker] exist as toolkit-independent steps.
 */
class NativeContextMenusTest {
    private fun item(
        label: String,
        enabled: Boolean = true,
    ) = NativeMenuNode.Item(label = label, enabled = enabled)

    private fun labels(nodes: List<NativeMenuNode>): List<String> =
        nodes.map {
            when (it) {
                is NativeMenuNode.Item -> it.label
                is NativeMenuNode.Submenu -> "${it.label}>"
                NativeMenuNode.Separator -> "---"
            }
        }

    // ----- the plan -----

    @Test
    fun `items keep their label and enabled state`() {
        val plan = planNativeMenu(listOf(item("Copy", enabled = false), item("Paste")))
        assertEquals(listOf("Copy", "Paste"), labels(plan))
        assertFalse((plan[0] as NativeMenuNode.Item).enabled)
        assertTrue((plan[1] as NativeMenuNode.Item).enabled)
    }

    @Test
    fun `a separator between items is kept`() {
        val plan = planNativeMenu(listOf(item("A"), NativeMenuNode.Separator, item("B")))
        assertEquals(listOf("A", "---", "B"), labels(plan))
    }

    @Test
    fun `leading, trailing and consecutive separators are dropped`() {
        // Menus assembled with `if` guards routinely produce these, and a native menu renders a
        // dangling separator as a stray line rather than ignoring it.
        val plan =
            planNativeMenu(
                listOf(
                    NativeMenuNode.Separator,
                    NativeMenuNode.Separator,
                    item("A"),
                    NativeMenuNode.Separator,
                    NativeMenuNode.Separator,
                    item("B"),
                    NativeMenuNode.Separator,
                ),
            )
        assertEquals(listOf("A", "---", "B"), labels(plan))
    }

    @Test
    fun `a menu that is only separators plans to nothing`() {
        assertTrue(planNativeMenu(List(3) { NativeMenuNode.Separator }).isEmpty())
    }

    @Test
    fun `submenus are planned recursively`() {
        val plan =
            planNativeMenu(
                listOf(
                    NativeMenuNode.Submenu(
                        "Share",
                        listOf(NativeMenuNode.Separator, item("Tab"), NativeMenuNode.Separator),
                    ),
                ),
            )
        val submenu = plan.single() as NativeMenuNode.Submenu
        assertEquals(listOf("Tab"), labels(submenu.children))
    }

    @Test
    fun `an empty submenu is removed rather than left unopenable`() {
        val plan =
            planNativeMenu(
                listOf(
                    item("A"),
                    NativeMenuNode.Submenu("Empty", emptyList()),
                    NativeMenuNode.Submenu("AlsoEmpty", listOf(NativeMenuNode.Separator)),
                ),
            )
        assertEquals(listOf("A"), labels(plan))
    }

    @Test
    fun `item actions survive planning`() {
        var fired = 0
        val plan = planNativeMenu(listOf(NativeMenuNode.Item("Go", action = { fired += 1 })))
        (plan.single() as NativeMenuNode.Item).action()
        assertEquals(1, fired)
    }

    // ----- Windows mnemonic escaping -----

    @Test
    fun `ampersands are doubled on windows only`() {
        // A branch named feat/a&b would otherwise render as "feat/ab" with an underlined b.
        assertEquals(
            "feat/a&&b",
            (
                planNativeMenu(listOf(item("feat/a&b")), isWindows = true)
                    .single() as NativeMenuNode.Item
            ).label,
        )
        assertEquals(
            "feat/a&b",
            (
                planNativeMenu(listOf(item("feat/a&b")), isWindows = false)
                    .single() as NativeMenuNode.Item
            ).label,
        )
    }

    @Test
    fun `escaping reaches submenu labels and their children`() {
        val plan =
            planNativeMenu(
                listOf(NativeMenuNode.Submenu("A&B", listOf(item("C&D")))),
                isWindows = true,
            )
        val submenu = plan.single() as NativeMenuNode.Submenu
        assertEquals("A&&B", submenu.label)
        assertEquals("C&&D", (submenu.children.single() as NativeMenuNode.Item).label)
    }

    // ----- the shortcut contract -----

    @Test
    fun `a shortcut that is not a VK constant is rejected at construction`() {
        // 'c'.code is 99, which is not KeyEvent.VK_C and would render as something arbitrary.
        assertFailsWith<IllegalArgumentException> { NativeMenuNode.Item("Copy", shortcut = 'c') }
        NativeMenuNode.Item("Copy", shortcut = 'C')
        NativeMenuNode.Item("One", shortcut = '1')
        NativeMenuNode.Item("None", shortcut = null)
    }

    // ----- invoker selection -----

    private fun candidate(
        name: String,
        active: Boolean = false,
        x: Int = 0,
        y: Int = 0,
        w: Int = 100,
        h: Int = 100,
        frameOrDialog: Boolean = true,
        showing: Boolean = true,
    ) = InvokerCandidate(name, frameOrDialog, showing, active, Rectangle(x, y, w, h))

    @Test
    fun `an active window wins over a larger inactive one`() {
        val picked =
            pickInvoker(
                listOf(candidate("big", w = 2000, h = 2000), candidate("active", active = true)),
                at = null,
            )
        assertEquals("active", picked?.window)
    }

    @Test
    fun `among inactive windows the smallest wins, not the largest`() {
        // Largest is the one most likely to be UNDERNEATH.
        val picked =
            pickInvoker(
                listOf(candidate("fullscreen", w = 3000, h = 2000), candidate("small", w = 400, h = 300)),
                at = null,
            )
        assertEquals("small", picked?.window)
    }

    @Test
    fun `popup and hidden windows are not eligible invokers`() {
        val picked =
            pickInvoker(
                listOf(
                    candidate("popup", frameOrDialog = false, w = 10, h = 10),
                    candidate("hidden", showing = false, w = 20, h = 20),
                    candidate("frame", w = 900, h = 900),
                ),
                at = null,
            )
        assertEquals("frame", picked?.window)
    }

    @Test
    fun `only windows containing the point are eligible`() {
        val picked =
            pickInvoker(
                listOf(
                    candidate("left", x = 0, y = 0, w = 100, h = 100),
                    candidate("right", x = 500, y = 500, w = 300, h = 300),
                ),
                at = Point(600, 600),
            )
        assertEquals("right", picked?.window)
    }

    @Test
    fun `a click on the half-open right or bottom edge still finds a window`() {
        // Rectangle.contains is half-open, so (100,100) is NOT inside a 0,0 100x100 frame.
        // Without the ifEmpty fallback this returns null and no menu appears at all.
        val picked =
            pickInvoker(
                listOf(candidate("frame", x = 0, y = 0, w = 100, h = 100)),
                at = Point(100, 100),
            )
        assertEquals("frame", picked?.window)
    }

    @Test
    fun `no eligible window yields null rather than an arbitrary one`() {
        assertNull(pickInvoker(listOf(candidate("hidden", showing = false)), at = null))
        assertNull(pickInvoker(emptyList<InvokerCandidate<String>>(), at = null))
    }

    // ----- the dismissal heuristic -----

    private val grace = NativeContextMenus.DISMISS_GRACE_MS
    private val moved = java.awt.event.MouseEvent.MOUSE_MOVED
    private val released = java.awt.event.MouseEvent.MOUSE_RELEASED

    @Test
    fun `nothing inside the grace window counts as dismissal`() {
        assertFalse(NativeContextMenus.isDismissalEvent(moved, 0))
        assertFalse(NativeContextMenus.isDismissalEvent(moved, grace - 1))
    }

    @Test
    fun `an ordinary event after the grace window counts as dismissal`() {
        assertTrue(NativeContextMenus.isDismissalEvent(moved, grace))
        assertTrue(NativeContextMenus.isDismissalEvent(moved, 5_000))
    }

    @Test
    fun `a mouse release never counts, however late it arrives`() {
        // Wall-clock alone is not enough: under a busy EDT the opening right-click's own release
        // can be dispatched well after the grace window expires.
        assertFalse(NativeContextMenus.isDismissalEvent(released, grace + 1))
        assertFalse(NativeContextMenus.isDismissalEvent(released, 60_000))
    }

    // ----- the platform gate -----

    @Test
    fun `only macOS gets native menus, and only when the setting allows`() {
        assertTrue(shouldUseNativeMenus(settingEnabled = true, isMacOs = true))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isMacOs = true))
    }

    @Test
    fun `windows and linux stay on the app's own menus even with the setting on`() {
        // Windows is unverified (TrackPopupMenu may block the EDT); Linux's XAWT peer ignores GTK.
        assertFalse(shouldUseNativeMenus(settingEnabled = true, isMacOs = false))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isMacOs = false))
    }

    @Test
    fun `a live remote view turns native menus off`() {
        assertFalse(shouldUseNativeMenus(settingEnabled = true, isMacOs = true, remotelyViewed = true))
        assertTrue(shouldUseNativeMenus(settingEnabled = true, isMacOs = true, remotelyViewed = false))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isMacOs = true, remotelyViewed = true))
    }

    @Test
    fun `remote view count is shared and each handle closes once`() {
        val base = RemoteViewState.count.value
        val first = RemoteViewState.begin()
        val second = RemoteViewState.begin()
        assertEquals(base + 2, RemoteViewState.count.value)
        first.close()
        first.close()
        assertEquals(base + 1, RemoteViewState.count.value)
        second.close()
        assertEquals(base, RemoteViewState.count.value)
    }

    @Test
    fun `show reports failure rather than throwing when unsupported or empty`() {
        // On a Linux CI runner isSupported() is false; on a mac dev box the empty plan is what
        // makes this false. Either way it must return, not throw, and not leave a menu behind.
        assertFalse(NativeContextMenus.show(emptyList()))
    }

    // ----- icon sizing -----

    @Test
    fun `the icon contract is a 2x rasterisation of the point size`() {
        // AppKit reads a plain bitmap's PIXEL dimensions as its POINT size, so a Retina-scale
        // rasterisation renders an icon twice too large. The point size is stated separately by
        // the multi-resolution image, and the bitmap is a fixed 2x of it - never the screen scale.
        assertEquals(16, NATIVE_MENU_ICON_POINTS)
        assertEquals(2, NATIVE_MENU_ICON_SCALE)
        assertEquals(32, NATIVE_MENU_ICON_POINTS * NATIVE_MENU_ICON_SCALE)
    }

    @Test
    fun `the point size sits in the range macOS uses for menu items`() {
        // The system checkmark is 14x13pt and 16pt is the common convention for custom menu
        // icons; anything materially larger is the bug this range exists to catch.
        assertTrue(NATIVE_MENU_ICON_POINTS in 14..18)
    }

    // ----- the show() decision, testable off macOS -----

    @Test
    fun `a native menu needs support, the EDT and a non-empty plan`() {
        assertTrue(canShowNatively(isSupported = true, isEventDispatchThread = true, plannedSize = 1))
    }

    @Test
    fun `each precondition alone is enough to decline`() {
        // Going through show() on a Linux runner only ever re-tests the platform gate, so these
        // guards need coverage that actually runs everywhere.
        assertFalse(canShowNatively(isSupported = false, isEventDispatchThread = true, plannedSize = 1))
        assertFalse(canShowNatively(isSupported = true, isEventDispatchThread = false, plannedSize = 1))
        assertFalse(canShowNatively(isSupported = true, isEventDispatchThread = true, plannedSize = 0))
    }

    @Test
    fun `an empty plan declines even though the menu had items`() {
        // A menu of only separators plans to nothing; the caller must draw its own instead.
        val planned = planNativeMenu(List(3) { NativeMenuNode.Separator })
        assertFalse(
            canShowNatively(isSupported = true, isEventDispatchThread = true, plannedSize = planned.size),
        )
    }
}
