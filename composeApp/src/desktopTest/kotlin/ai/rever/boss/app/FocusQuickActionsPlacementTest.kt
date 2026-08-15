package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [focusQuickActionsPlacement], the one line that decides whether the three actions are three
 * icons on a rail or an always-on-top window over live content.
 *
 * Both outcomes are correct code and neither crashes, so the failure mode of getting this wrong is
 * entirely cosmetic-looking and entirely real: a native overlay with no click-through parked over
 * the content area of every Windows user in focus mode, which is the exact configuration that has
 * a perfectly good rail sitting empty two inches to its right.
 */
class FocusQuickActionsPlacementTest {
    private val clearsTopKeepsRail = FocusModeSettings(enabled = true, hideTopBar = true, hideRightSidebar = false)
    private val clearsBoth = FocusModeSettings(enabled = true, hideTopBar = true, hideRightSidebar = true)

    @Test
    fun `the rail hosts them whenever focus mode leaves the rail alone`() {
        assertEquals(
            FocusQuickActionsPlacement.RIGHT_RAIL,
            focusQuickActionsPlacement(clearsTopKeepsRail, showTopBar = false),
        )
    }

    @Test
    fun `they float only once focus mode has taken the rail too`() {
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            focusQuickActionsPlacement(clearsBoth, showTopBar = false),
        )
    }

    @Test
    fun `neither rendering while the top bar is up`() {
        // The bar owns these three itself when it is showing, so both placements have to stand
        // down - and the rail one silently, without leaving a divider and a gap behind it.
        assertEquals(
            FocusQuickActionsPlacement.NONE,
            focusQuickActionsPlacement(clearsTopKeepsRail, showTopBar = true),
        )
        assertEquals(
            FocusQuickActionsPlacement.NONE,
            focusQuickActionsPlacement(clearsBoth, showTopBar = true),
        )
    }

    @Test
    fun `a window's first composition asks for no overlay with focus mode off`() {
        // showTopBar reads false on the first composition of every window, focus mode or not,
        // because the effect that turns it back on has not run yet. FLOATING here would create and
        // immediately dispose a native always-on-top window on every window open - the trap
        // focusQuickActionsVisible documents, re-entered one layer up if this delegated to the
        // reveal flags rather than to the settings.
        val off = clearsBoth.copy(enabled = false)

        assertEquals(FocusQuickActionsPlacement.NONE, focusQuickActionsPlacement(off, showTopBar = false))
    }

    @Test
    fun `the Windows defaults put them on the rail`() {
        // The case this split exists for, asserted through the real defaults rather than through a
        // hand-built settings object: Windows clears the top bar but keeps both sidebars, because
        // hover-reveal cannot fire over a browser tab there and a hidden sidebar would be a one-way
        // door. So the platform the floating cluster was built for is the one that now gets a rail.
        val windows = FocusModeSettings.defaultsFor("Windows 11").copy(enabled = true)

        assertTrue(windows.hideTopBar, "the premise: Windows still clears the top bar")
        assertEquals(FocusQuickActionsPlacement.RIGHT_RAIL, focusQuickActionsPlacement(windows, showTopBar = false))
    }

    @Test
    fun `the macOS defaults still float them`() {
        // The mirror image, and the reason the floating path is not dead code: macOS clears every
        // edge by default, so there is no rail to put anything on.
        val mac = FocusModeSettings.defaultsFor("Mac OS X").copy(enabled = true)

        assertEquals(FocusQuickActionsPlacement.FLOATING, focusQuickActionsPlacement(mac, showTopBar = false))
    }

    @Test
    fun `the rail list is empty for every placement except the rail`() {
        // BossRightSideBar reserves height from this list's SIZE before rendering anything from it,
        // so a non-empty list on a placement that is not RIGHT_RAIL is not a stray icon, it is a
        // gap torn out of the icon budget of a bar that is not hosting anything.
        assertEquals(0, railFor(FocusQuickActionsPlacement.NONE).size)
        assertEquals(0, railFor(FocusQuickActionsPlacement.FLOATING).size)
        assertEquals(3, railFor(FocusQuickActionsPlacement.RIGHT_RAIL).size)
    }

    @Test
    fun `the reserve matches the number of icons actually rendered`() {
        // Closes the loop between the two halves. The rail reserves height from
        // focusQuickActionsRailRows while rendering focusQuickActionsRail, and those are separate
        // expressions on purpose - so nothing but this stops a fourth action being added to one and
        // not the other, which under-reserves and pushes an icon off the bottom of the window.
        assertEquals(
            railFor(FocusQuickActionsPlacement.RIGHT_RAIL).size,
            focusQuickActionsRailRows(clearsTopKeepsRail),
        )
    }

    @Test
    fun `the reserve survives a hover-revealed top bar, though the icons do not`() {
        // The churn guard. The rendered list empties the moment the top bar shows, and if the
        // reserve followed it, ADAPTIVE mode would hand ~3 rows back to the plugin slots and take
        // them away again on every hover - popping icons in and out of the More menu each time the
        // user reaches for the top bar, on the very defaults this placement is aimed at.
        assertEquals(
            FocusQuickActionsPlacement.NONE,
            focusQuickActionsPlacement(clearsTopKeepsRail, showTopBar = true),
            "the premise: the icons themselves stand down while the bar is up",
        )
        assertEquals(
            FOCUS_QUICK_ACTION_COUNT,
            focusQuickActionsRailRows(clearsTopKeepsRail),
            "but the rail keeps their rows, because showTopBar is momentary",
        )
    }

    @Test
    fun `nothing is reserved when the actions could never land on the rail`() {
        // Focus mode off, or clearing the sidebar too: the bar must budget exactly as it did before
        // this feature existed, or every user who never enables focus mode loses rail rows to it.
        assertEquals(0, focusQuickActionsRailRows(clearsTopKeepsRail.copy(enabled = false)))
        assertEquals(0, focusQuickActionsRailRows(clearsBoth))
        assertEquals(0, focusQuickActionsRailRows(clearsTopKeepsRail.copy(hideTopBar = false)))
    }

    private fun railFor(placement: FocusQuickActionsPlacement) =
        focusQuickActionsRail(
            placement = placement,
            onShowSettings = {},
            onShowSearch = {},
            onSignOut = {},
        )
}
