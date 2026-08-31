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

    /** Defaults say "nothing is switched off for good", so each test names only what it changes. */
    private fun placementOf(
        settings: FocusModeSettings,
        showTopBar: Boolean,
        topBarHidden: Boolean = false,
        rightStripHidden: Boolean = false,
    ) = focusQuickActionsPlacement(
        settings = settings,
        topBarHidden = topBarHidden,
        rightStripHidden = rightStripHidden,
        showTopBar = showTopBar,
    )

    private fun rowsOf(
        settings: FocusModeSettings,
        topBarHidden: Boolean = false,
        rightStripHidden: Boolean = false,
    ) = focusQuickActionsRailRows(
        settings = settings,
        topBarHidden = topBarHidden,
        rightStripHidden = rightStripHidden,
    )

    @Test
    fun `the rail hosts them whenever focus mode leaves the rail alone`() {
        assertEquals(
            FocusQuickActionsPlacement.RIGHT_RAIL,
            placementOf(clearsTopKeepsRail, showTopBar = false),
        )
    }

    @Test
    fun `they float only once focus mode has taken the rail too`() {
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            placementOf(clearsBoth, showTopBar = false),
        )
    }

    @Test
    fun `neither rendering while the top bar is up`() {
        // The bar owns these three itself when it is showing, so both placements have to stand
        // down - and the rail one silently, without leaving a divider and a gap behind it.
        assertEquals(
            FocusQuickActionsPlacement.NONE,
            placementOf(clearsTopKeepsRail, showTopBar = true),
        )
        assertEquals(
            FocusQuickActionsPlacement.NONE,
            placementOf(clearsBoth, showTopBar = true),
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

        assertEquals(FocusQuickActionsPlacement.NONE, placementOf(off, showTopBar = false))
    }

    @Test
    fun `the Windows defaults put them on the rail`() {
        // The case this split exists for, asserted through the real defaults rather than through a
        // hand-built settings object: Windows clears the top bar but keeps both sidebars, because
        // hover-reveal cannot fire over a browser tab there and a hidden sidebar would be a one-way
        // door. So the platform the floating cluster was built for is the one that now gets a rail.
        val windows = FocusModeSettings.defaultsFor("Windows 11").copy(enabled = true)

        assertTrue(windows.hideTopBar, "the premise: Windows still clears the top bar")
        assertEquals(FocusQuickActionsPlacement.RIGHT_RAIL, placementOf(windows, showTopBar = false))
    }

    @Test
    fun `the macOS defaults still float them`() {
        // The mirror image, and the reason the floating path is not dead code: macOS clears every
        // edge by default, so there is no rail to put anything on.
        val mac = FocusModeSettings.defaultsFor("Mac OS X").copy(enabled = true)

        assertEquals(FocusQuickActionsPlacement.FLOATING, placementOf(mac, showTopBar = false))
    }

    @Test
    fun `the rail list is empty for every placement except the rail`() {
        // BossRightSideBar reserves height from this list's SIZE before rendering anything from it,
        // so a non-empty list on a placement that is not RIGHT_RAIL is not a stray icon, it is a
        // gap torn out of the icon budget of a bar that is not hosting anything.
        assertEquals(0, railFor(FocusQuickActionsPlacement.NONE).size)
        assertEquals(0, railFor(FocusQuickActionsPlacement.FLOATING).size)
        // The constant, not a literal: it is what the rail reserves height from, so a test that
        // repeats the number cannot notice the two drifting apart - which is the whole job here.
        assertEquals(FOCUS_QUICK_ACTION_COUNT, railFor(FocusQuickActionsPlacement.RIGHT_RAIL).size)
    }

    @Test
    fun `the reserve matches the number of icons actually rendered`() {
        // Closes the loop between the two halves. The rail reserves height from
        // focusQuickActionsRailRows while rendering focusQuickActionsRail, and those are separate
        // expressions on purpose - so nothing but this stops a fourth action being added to one and
        // not the other, which under-reserves and pushes an icon off the bottom of the window.
        assertEquals(
            railFor(FocusQuickActionsPlacement.RIGHT_RAIL).size,
            rowsOf(clearsTopKeepsRail),
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
            placementOf(clearsTopKeepsRail, showTopBar = true),
            "the premise: the icons themselves stand down while the bar is up",
        )
        assertEquals(
            FOCUS_QUICK_ACTION_COUNT,
            rowsOf(clearsTopKeepsRail),
            "but the rail keeps their rows, because showTopBar is momentary",
        )
    }

    @Test
    fun `nothing is reserved when the actions could never land on the rail`() {
        // Focus mode off, or clearing the sidebar too: the bar must budget exactly as it did before
        // this feature existed, or every user who never enables focus mode loses rail rows to it.
        assertEquals(0, rowsOf(clearsTopKeepsRail.copy(enabled = false)))
        assertEquals(0, rowsOf(clearsBoth))
        assertEquals(0, rowsOf(clearsTopKeepsRail.copy(hideTopBar = false)))
    }

    @Test
    fun `a top bar hidden for good puts them on the rail, at the showTopBar the app really has`() {
        // `showTopBar = true` because with focus mode off EdgeRevealEffects leaves `shown` true -
        // that is the only value the scaffold can pass here, and asserting the unreachable `false`
        // is how the NONE-placement bug shipped past this suite. See FocusModeVisibilityTest.
        val off = FocusModeSettings(enabled = false)

        assertEquals(
            FocusQuickActionsPlacement.RIGHT_RAIL,
            placementOf(off, showTopBar = true, topBarHidden = true),
        )
    }

    @Test
    fun `they float when the right strip is hidden for good, not onto a bar that is not there`() {
        // The trap this closes: `hides(RIGHT)` is false when the user hid the strip by preference
        // rather than through focus mode, so without the flag this would answer RIGHT_RAIL and hand
        // three icons to a bar the scaffold is not composing at all.
        val off = FocusModeSettings(enabled = false)

        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            placementOf(off, showTopBar = true, topBarHidden = true, rightStripHidden = true),
        )
    }

    @Test
    fun `the reserve is never held for a placement of NONE`() {
        // The corroborating symptom of the same root cause: rows are computed without showTopBar,
        // so if the placement says NONE while the reserve says three, the rail keeps three empty
        // icon rows. Swept across every reachable combination rather than a hand-picked one.
        val cases =
            listOf(
                FocusModeSettings(enabled = false),
                FocusModeSettings(enabled = true, hideTopBar = true, hideRightSidebar = false),
                FocusModeSettings(enabled = true, hideTopBar = true, hideRightSidebar = true),
                FocusModeSettings(enabled = true, hideTopBar = false, hideRightSidebar = true),
            )

        val flags = listOf(false to false, false to true, true to false, true to true)
        val combinations = cases.flatMap { settings -> flags.map { settings to it } }

        combinations.forEach { (settings, hidden) ->
            val (topBarHidden, rightStripHidden) = hidden
            val showTopBar = !settings.hideTopBar || !settings.enabled
            val placement = placementOf(settings, showTopBar, topBarHidden, rightStripHidden)
            val rows = rowsOf(settings, topBarHidden, rightStripHidden)
            val expected = if (placement == FocusQuickActionsPlacement.RIGHT_RAIL) FOCUS_QUICK_ACTION_COUNT else 0

            assertEquals(
                expected,
                rows,
                "reserved $rows rows for placement $placement " +
                    "(settings=$settings, topBarHidden=$topBarHidden, rightStripHidden=$rightStripHidden)",
            )
        }
    }

    @Test
    fun `the reserve follows the appearance flags exactly as the placement does`() {
        // Same closed loop as `the reserve matches...` above, over the new inputs: reserving rows on
        // a strip that is switched off, or failing to reserve them on one that is not, is the
        // under-reserve that pushes an icon off the bottom of the window.
        val off = FocusModeSettings(enabled = false)

        assertEquals(FOCUS_QUICK_ACTION_COUNT, rowsOf(off, topBarHidden = true))
        assertEquals(0, rowsOf(off, topBarHidden = true, rightStripHidden = true))
        assertEquals(0, rowsOf(off, rightStripHidden = true))
    }

    private fun railFor(placement: FocusQuickActionsPlacement) =
        focusQuickActionsRail(
            placement = placement,
            onShowSettings = {},
            onShowSearch = {},
            onSignOut = {},
            // The row production draws: the Toolbox is a bundled system plugin, so
            // FOCUS_QUICK_ACTION_COUNT counts it and the rail reserves a row for it.
            toolbox = { _, _ -> },
        )

    @Test
    fun `captured full screen keeps the actions in the cluster they already use`() {
        // Not NONE. Dropping them left Toolbox unreachable on macOS - its menu is in the menu bar,
        // which the mode hides - and Sign Out unreachable everywhere, since it has no shortcut.
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            focusQuickActionsPlacement(
                settings = FocusModeSettings(enabled = true, hideTopBar = true),
                topBarHidden = true,
                rightStripHidden = true,
                showTopBar = false,
                capturedFullScreen = true,
            ),
        )
    }

    @Test
    fun `captured full screen never answers a placement that renders into hidden chrome`() {
        // The trap this branch exists for. With a right strip configured the normal answer is
        // RIGHT_RAIL, and captured full screen draws no rail - so the actions would be handed to a
        // bar that is not composed, which is the v9.4.13 regression for the third time. FLOATING is
        // the only placement that owns its own surface.
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            focusQuickActionsPlacement(
                settings = FocusModeSettings(enabled = true, hideTopBar = true),
                topBarHidden = true,
                rightStripHidden = false,
                showTopBar = false,
                capturedFullScreen = true,
            ),
        )
    }

    @Test
    fun `captured full screen shows them even when the top bar is switched on`() {
        // focusQuickActionsVisible would answer false here - the bar is neither hidden by the
        // preference nor cleared by focus mode - but the mode hides it anyway. Asking capture first
        // is what stops a user who keeps the top bar losing these four on entering.
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            focusQuickActionsPlacement(
                settings = FocusModeSettings(enabled = false),
                topBarHidden = false,
                rightStripHidden = true,
                showTopBar = true,
                capturedFullScreen = true,
            ),
        )
    }
}
