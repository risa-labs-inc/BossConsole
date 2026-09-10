package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the precedence between the five renderings of Settings / Search / Sign Out.
 *
 * The order is the right rail, the tab bar's foot, that bar's collapsed rail, a reserved row at
 * the foot of the open right panel, and only then the floating cluster - and each step is a choice
 * rather than an accident. The cluster is a native always-on-top window with no click-through, so
 * it is the most intrusive of the five and goes last; every one before it is chrome the app draws
 * anyway, or layout it can carve out.
 *
 * Getting this backwards does not crash - it puts a dead click region over the content area of a
 * window that had a perfectly good place to put four icons.
 */
class QuickActionsFooterPlacementTest {
    private val focusOff = FocusModeSettings()

    private fun placement(
        rightStripHidden: Boolean,
        verticalBar: VerticalBarHost,
        panelFootAvailable: Boolean = false,
    ) = focusQuickActionsPlacement(
        settings = focusOff,
        topBarHidden = true,
        rightStripHidden = rightStripHidden,
        showTopBar = false,
        verticalBar = verticalBar,
        panelFootAvailable = panelFootAvailable,
    )

    @Test
    fun `the rail still wins when there is a rail`() {
        assertEquals(
            FocusQuickActionsPlacement.RIGHT_RAIL,
            placement(rightStripHidden = false, verticalBar = VerticalBarHost.FOOT),
            "the vertical tab bar displaces the floating cluster, not the rail",
        )
    }

    @Test
    fun `the tab bar's foot takes the floating cluster's place`() {
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_FOOTER,
            placement(rightStripHidden = true, verticalBar = VerticalBarHost.FOOT),
        )
    }

    @Test
    fun `a collapsed bar puts them at the foot of its rail, not in the corner`() {
        // The rail has no foot under a split map, but it does have a bottom, and it is still the
        // bar. Collapsing it is a request for content width; answering that with a native overlay
        // parked in the content is the opposite of granting it.
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_RAIL,
            placement(rightStripHidden = true, verticalBar = VerticalBarHost.RAIL),
        )
    }

    @Test
    fun `a collapsed bar keeps the rail even with the right panel open`() {
        // The panel only decides between its own foot and the overlay, and neither is reached
        // while a bar - full or railed - can hold these itself.
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_RAIL,
            placement(rightStripHidden = true, verticalBar = VerticalBarHost.RAIL, panelFootAvailable = true),
        )
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_FOOTER,
            placement(rightStripHidden = true, verticalBar = VerticalBarHost.FOOT, panelFootAvailable = true),
        )
    }

    @Test
    fun `a bar that is not on the left hosts nothing at all`() {
        // Asked through verticalBarHost rather than by naming NONE again, because that is the
        // composition the scaffold performs and the only way these cases differ.
        val tabsOnTop = verticalBarHost(tabBarOnLeft = false, barCollapsed = false, drawerVisible = false)
        assertEquals(VerticalBarHost.NONE, tabsOnTop)
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            placement(rightStripHidden = true, verticalBar = tabsOnTop),
        )

        // With the right panel open, the same window reserves a row instead of covering it.
        assertEquals(
            FocusQuickActionsPlacement.PANEL_FOOTER,
            placement(rightStripHidden = true, verticalBar = tabsOnTop, panelFootAvailable = true),
        )

        // A collapsed bar on the left reaches its own rail by the other route.
        val collapsedOnLeft = verticalBarHost(tabBarOnLeft = true, barCollapsed = true, drawerVisible = false)
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_RAIL,
            placement(rightStripHidden = true, verticalBar = collapsedOnLeft),
        )

        // The drawer is the third state: a collapsed bar with it open HAS a foot again.
        val drawerOpen = verticalBarHost(tabBarOnLeft = true, barCollapsed = true, drawerVisible = true)
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_FOOTER,
            placement(rightStripHidden = true, verticalBar = drawerOpen),
        )
    }

    @Test
    fun `the top bar being up still beats all of them`() {
        assertEquals(
            FocusQuickActionsPlacement.NONE,
            focusQuickActionsPlacement(
                settings = focusOff,
                topBarHidden = false,
                rightStripHidden = true,
                showTopBar = true,
                verticalBar = VerticalBarHost.FOOT,
                panelFootAvailable = true,
            ),
            "the top bar owns these whenever it is on screen",
        )
    }

    @Test
    fun `each layout is empty for every placement but its own`() {
        // What lets all four hosts call their builder unconditionally and render nothing. A
        // non-empty list on the wrong placement is not a stray icon: the right rail reserves
        // height from its list's SIZE, and two hosts drawing at once is Sign Out twice.
        val builders =
            mapOf(
                FocusQuickActionsPlacement.RIGHT_RAIL to
                    { p: FocusQuickActionsPlacement -> focusQuickActionsRail(p, {}, {}, {}, { _, _ -> }) },
                FocusQuickActionsPlacement.TAB_BAR_FOOTER to
                    { p: FocusQuickActionsPlacement -> focusQuickActionsFooter(p, {}, {}, {}, { _, _ -> }) },
                FocusQuickActionsPlacement.TAB_BAR_RAIL to
                    { p: FocusQuickActionsPlacement -> focusQuickActionsTabRail(p, {}, {}, {}, { _, _ -> }) },
                FocusQuickActionsPlacement.PANEL_FOOTER to
                    { p: FocusQuickActionsPlacement -> focusQuickActionsPanelFooter(p, {}, {}, {}, { _, _ -> }) },
            )

        builders.forEach { (owner, build) ->
            FocusQuickActionsPlacement.entries.forEach { placement ->
                val expected = if (placement == owner) FOCUS_QUICK_ACTION_COUNT else 0
                assertEquals(
                    expected,
                    build(placement).size,
                    "the $owner layout for placement $placement",
                )
            }
        }
    }

    @Test
    fun `the launcher adds one more action to each layout without disturbing the reserve`() {
        // The rail's reserve is FOCUS_QUICK_ACTION_COUNT rows, and it stays at four because the
        // launcher can never join the right-rail flavour - see ToolLauncherPlacementTest.
        val hosts =
            listOf(
                FocusQuickActionsPlacement.TAB_BAR_FOOTER to
                    { p: FocusQuickActionsPlacement ->
                        focusQuickActionsFooter(p, {}, {}, {}, toolbox = { _, _ -> }, toolLauncher = { _, _ -> })
                    },
                FocusQuickActionsPlacement.TAB_BAR_RAIL to
                    { p: FocusQuickActionsPlacement ->
                        focusQuickActionsTabRail(p, {}, {}, {}, toolbox = { _, _ -> }, toolLauncher = { _, _ -> })
                    },
                FocusQuickActionsPlacement.PANEL_FOOTER to
                    { p: FocusQuickActionsPlacement ->
                        focusQuickActionsPanelFooter(
                            p,
                            {},
                            {},
                            {},
                            toolbox = { _, _ -> },
                            toolLauncher = { _, _ -> },
                        )
                    },
            )

        hosts.forEach { (placement, build) ->
            assertEquals(FOCUS_QUICK_ACTION_COUNT + 1, build(placement).size, "with the launcher, $placement")
        }

        val rail = focusQuickActionsRail(FocusQuickActionsPlacement.RIGHT_RAIL, {}, {}, {}, { _, _ -> })
        assertEquals(FOCUS_QUICK_ACTION_COUNT, rail.size)
    }

    @Test
    fun `focus mode clearing the right edge also reaches the footer`() {
        // hides(RIGHT) is the other way there is no rail. It should land on the tab bar's foot
        // rather than the floating cluster, exactly as a switched-off strip does.
        val hidesRight = FocusModeSettings(enabled = true, hideRightSidebar = true)

        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_FOOTER,
            focusQuickActionsPlacement(
                settings = hidesRight,
                topBarHidden = true,
                rightStripHidden = false,
                showTopBar = false,
                verticalBar = VerticalBarHost.FOOT,
            ),
        )
    }

    @Test
    fun `the overlay is the only placement that draws over content`() {
        // The whole point of the two additions, stated as one assertion: of the configurations a
        // window with the top bar off can be in, the only one that ends up with an overlay is the
        // one with no bar to host these and nothing open for it to cover.
        val overlaid =
            VerticalBarHost.entries
                .flatMap { bar -> listOf(false, true).map { open -> bar to open } }
                .filter { (bar, open) ->
                    placement(
                        rightStripHidden = true,
                        verticalBar = bar,
                        panelFootAvailable = open,
                    ) == FocusQuickActionsPlacement.FLOATING
                }

        assertEquals(listOf(VerticalBarHost.NONE to false), overlaid)
    }
}

/**
 * Pins the three states of "what can the vertical bar host".
 *
 * Two of them look the same from the settings alone - a collapsed bar with the drawer open and one
 * with it shut differ only in a transient flag - and getting it wrong is silent either way: the
 * actions render twice, or nowhere.
 */
class VerticalBarHostTest {
    @Test
    fun `an expanded left bar has a foot`() {
        assertEquals(
            VerticalBarHost.FOOT,
            verticalBarHost(tabBarOnLeft = true, barCollapsed = false, drawerVisible = false),
        )
    }

    @Test
    fun `a collapsed bar offers its rail`() {
        // It draws its rail and nothing else, and the bottom of that rail is what it has room for.
        assertEquals(
            VerticalBarHost.RAIL,
            verticalBarHost(tabBarOnLeft = true, barCollapsed = true, drawerVisible = false),
        )
    }

    @Test
    fun `a collapsed bar with the drawer open has a foot again`() {
        // The drawer is a full bar, split map and all, for as long as it is up.
        assertEquals(
            VerticalBarHost.FOOT,
            verticalBarHost(tabBarOnLeft = true, barCollapsed = true, drawerVisible = true),
        )
    }

    @Test
    fun `a top tab bar hosts nothing, drawer or not`() {
        // There is no vertical bar at all in TOP position, so no drawer can give it a foot.
        assertEquals(
            VerticalBarHost.NONE,
            verticalBarHost(tabBarOnLeft = false, barCollapsed = false, drawerVisible = true),
        )
    }
}
