package ai.rever.boss.components.bars.vertical

import ai.rever.boss.app.FocusQuickActionsPlacement
import ai.rever.boss.app.focusQuickActionsRail
import ai.rever.boss.components.sidebar.SidebarIconRail
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [SidebarBottomActions] against the rail shape `BossRightSideBar` actually builds.
 *
 * The load-bearing claim is that [SidebarIconRail.bottomSectionHeight] equals what this section
 * renders. That number is what the rail subtracts from its own height before dealing icon rows to
 * the draggable slots, and it is a hand-written mirror of padding written somewhere else - the
 * failure mode `SidebarIconRail`'s own KDoc warns about. Under-reserve and a crowded rail overlaps
 * or clips its own plugin icons; over-reserve and one silently drops into the More menu. Both
 * compile, both pass every other gate, and neither is visible until someone with a crowded sidebar
 * opens focus mode.
 *
 * The second claim is about layout order rather than arithmetic: the slots carry the weight and
 * this section does not, so when the two cannot both fit it is the slots that give way. That is
 * what keeps the actions reachable in FIXED icon-limit mode, where the reserve is not read at all.
 */
class SidebarBottomActionsLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * What the section renders into, standing in for the rail: fixed width, [height] tall, with
     * [slotRows] rows of stand-in plugin icons above it.
     *
     * Mirrors `BossRightSideBar`'s real shape, and the important part is which child carries the
     * weight. The SLOTS do; this section sits outside it. Laid out the other way round - section
     * after a weighted spacer - a rail whose slots ask for more room than it has pushes the
     * section past the bottom edge, and no reserve prevents it in FIXED icon-limit mode.
     */
    @Composable
    private fun Rail(
        actions: List<@Composable () -> Unit>,
        height: Dp,
        slotRows: Int,
    ) {
        Column(
            modifier =
                Modifier
                    .width(RAIL_WIDTH)
                    .height(height)
                    .testTag(RAIL_TAG),
        ) {
            Column(modifier = Modifier.weight(1f).clipToBounds()) {
                repeat(slotRows) { Box(modifier = Modifier.size(SidebarIconRail.RowPitch)) }
            }
            SidebarBottomActions(actions)
        }
    }

    private fun mountRail(
        actions: List<@Composable () -> Unit>,
        height: Dp = RAIL_HEIGHT,
        slotRows: Int = 2,
    ) {
        rule.setContent { Rail(actions, height, slotRows) }
        rule.waitForIdle()
    }

    /** The real thing the rail is handed in focus mode, not a stand-in for it. */
    private fun quickActions(
        onShowSettings: () -> Unit = {},
        onShowSearch: () -> Unit = {},
        onSignOut: () -> Unit = {},
    ) = focusQuickActionsRail(
        placement = FocusQuickActionsPlacement.RIGHT_RAIL,
        onShowSettings = onShowSettings,
        onShowSearch = onShowSearch,
        onSignOut = onSignOut,
    )

    private fun boundsOf(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun iconBounds(contentDescription: String): Rect =
        rule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot

    private fun Float.toDp(): Dp = with(rule.density) { this@toDp.toDp() }

    @Test
    fun `the rail reserves exactly what the section renders`() {
        mountRail(quickActions())

        val section = boundsOf(SIDEBAR_BOTTOM_ACTIONS_TAG)
        val rail = boundsOf(RAIL_TAG)
        // The divider is emitted above the tagged Column and is outside it, so it is added back
        // rather than measured - bottomSectionHeight counts it, and the rail pays for it.
        val rendered = (rail.bottom - section.top).toDp() + SidebarIconRail.SectionDivider
        val reserved = SidebarIconRail.bottomSectionHeight(3)

        // A tolerance, not exact equality: the span is measured back out of integer pixels at the
        // harness's density, so a fraction of a dp is rounding rather than a wrong reserve. 1dp
        // rather than a half, because at 2x a single pixel is already 0.5dp and this should not
        // start flaking on a HiDPI runner. Anything that actually breaks the mirror - a padding
        // change, a fourth action, an icon at 28dp instead of the rail's 32dp - moves it by a whole
        // row or a whole gap, an order of magnitude clear of this.
        assertEquals(
            reserved.value,
            rendered.value,
            1f,
            "the rail budgets its icon rows against $reserved but the section renders $rendered",
        )
    }

    @Test
    fun `an empty section renders nothing, divider included`() {
        // Not an optimisation. A bar not hosting the actions has to be the bar that existed before
        // there was a bottom section at all: a divider and a gap left behind whenever the top bar
        // is hover-revealed would be a visible artefact of a feature that is not on screen.
        mountRail(emptyList())

        rule.onAllNodesWithTag(SIDEBAR_BOTTOM_ACTIONS_TAG).assertCountEquals(0)
        assertEquals(0.dp, SidebarIconRail.bottomSectionHeight(0))
    }

    @Test
    fun `the section sits at the bottom of the rail and inside its width`() {
        // "Bottom right" is the entire request. A section that laid out under the top slots instead
        // would still render three working buttons, and every other assertion here would hold.
        mountRail(quickActions())

        val section = boundsOf(SIDEBAR_BOTTOM_ACTIONS_TAG)
        val rail = boundsOf(RAIL_TAG)

        // Same 1dp tolerance as the reserve assertion, and for the same reason: these are floats
        // reconstructed from integer pixels, so exact equality is a promise the layout never made.
        assertEquals(
            rail.bottom,
            section.bottom,
            1f,
            "the section is anchored to the rail's bottom edge",
        )
        assertTrue(
            section.left >= rail.left && section.right <= rail.right,
            "the section is $section but the 40dp rail it has to fit is $rail",
        )
        assertTrue(
            section.top > rail.top,
            "the weighted slot region above it should push the section down, not leave it at the top",
        )
    }

    @Test
    fun `each icon raises its own action`() {
        // Three callbacks passed positionally into one list is the kind of wiring that survives
        // being crossed: every button still works, and each opens the wrong thing.
        var settings = 0
        var search = 0
        var signOut = 0
        mountRail(
            quickActions(
                onShowSettings = { settings++ },
                onShowSearch = { search++ },
                onSignOut = { signOut++ },
            ),
        )

        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Sign Out").performClick()

        assertEquals(Triple(1, 1, 1), Triple(settings, search, signOut))
    }

    @Test
    fun `an overcrowded rail clips its slots rather than losing the actions`() {
        // The case the reserve cannot cover: computeSlotIconLimits returns early in FIXED mode and
        // never reads reservedHeight, and no reserve helps a rail shorter than its own content.
        // 20 slot rows into a 300dp rail asks for roughly 800dp. What must not happen is the
        // section being pushed past the bottom edge, because the content that goes missing is
        // Settings / Search / Sign Out, with no floating cluster behind it in this placement.
        mountRail(quickActions(), height = SHORT_RAIL, slotRows = 20)

        val section = boundsOf(SIDEBAR_BOTTOM_ACTIONS_TAG)
        val rail = boundsOf(RAIL_TAG)

        assertTrue(
            section.bottom <= rail.bottom + 1f && section.top >= rail.top,
            "the section is $section but the rail it has to stay inside is $rail",
        )
        assertEquals(
            SidebarIconRail.bottomSectionHeight(3).value - SidebarIconRail.SectionDivider.value,
            (section.bottom - section.top).toDp().value,
            1f,
            "and it is not squashed to fit either - the slots are what give way",
        )
    }

    @Test
    fun `sign out is the icon furthest from the corner`() {
        // Order carries intent: the destructive action is deliberately not the one in the very
        // corner of the window, where it is easiest to hit by accident. In a bottom-anchored column
        // that means topmost, which is the opposite end from the row the top bar lays out.
        mountRail(quickActions())

        val signOut = iconBounds("Sign Out")
        val search = iconBounds("Search")
        val settings = iconBounds("Settings")

        assertTrue(
            signOut.top < search.top && search.top < settings.top,
            "expected Sign Out above Search above Settings, got ${signOut.top}, ${search.top}, ${settings.top}",
        )
    }

    private companion object {
        const val RAIL_TAG = "rail-under-test"

        /** `VerticalBar(40.dp)`, the width both rails are built at. */
        val RAIL_WIDTH = 40.dp

        /** Tall enough that the slot region has slack and the section sits at the bottom. */
        val RAIL_HEIGHT = 600.dp

        /** Short enough that 20 slot rows cannot fit, which is the overcrowding case. */
        val SHORT_RAIL = 300.dp
    }
}
