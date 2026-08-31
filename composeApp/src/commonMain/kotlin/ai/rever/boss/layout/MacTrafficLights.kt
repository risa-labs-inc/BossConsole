package ai.rever.boss.layout

import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height a macOS window must keep clear at its top-LEFT for the traffic lights.
 *
 * The window sets `apple.awt.fullWindowContent`, so the close / minimise / zoom buttons are drawn
 * over the content rather than in a strip above it. 28dp covers the buttons and the few points of
 * air macOS leaves around them.
 */
val TRAFFIC_LIGHT_HEIGHT: Dp = 28.dp

/**
 * Width of the same box. Not read by the layout - the column being inset is narrower than this
 * anyway - but stated because it is why a full-width row was the wrong shape: the lights occupy
 * one corner, not a band.
 */
val TRAFFIC_LIGHT_WIDTH: Dp = 78.dp

/**
 * The cluster's geometry, measured off a live window rather than assumed.
 *
 * Three shipped versions of the fourth button's position were guesses and each was visibly wrong:
 * 70dp (the light box minus an arbitrary nudge), then 74dp from an assumed 20pt pitch, then a 12dp
 * circle when the lights are 14. The numbers below come from two measurements of the real thing:
 *
 * - **Frames**, read back from AppKit through the accessibility API (`System Events` ->
 *   `buttons of window 1`), window origin (0, 33): close `{8, 41}`, minimise `{31, 41}`, zoom
 *   `{54, 41}`, each `{16, 16}`. Centres 16 / 39 / 62, so the pitch is 23 and not the 20 that a
 *   reading of the platform conventions suggests.
 * - **Pixels**, from a 2x screen capture scanned along the centre line: the drawn circles are 14pt
 *   across, which is smaller than their 16pt accessibility frame and larger than the 12pt the
 *   button first used.
 *
 * Everything the button needs is derived from these three, so the drawn circle and the space
 * reserved for it cannot disagree - which is exactly how the 12dp button ended up beside 14dp
 * lights.
 */
val TRAFFIC_LIGHT_FIRST_CENTRE: Dp = 16.dp

/** Centre-to-centre spacing. Measured at 23pt; the platform-convention answer of 20 is wrong here. */
val TRAFFIC_LIGHT_PITCH: Dp = 23.dp

/** The DRAWN diameter, which is neither the 16pt accessibility frame nor the 12pt first assumed. */
val TRAFFIC_LIGHT_DIAMETER: Dp = 14.dp

/** Where a fourth button on the same pitch starts, from the window's left edge. */
val CAPTURED_BUTTON_START: Dp = TRAFFIC_LIGHT_FIRST_CENTRE + TRAFFIC_LIGHT_PITCH * 3 - TRAFFIC_LIGHT_DIAMETER / 2

/**
 * The button's top inset, so its centre lines up with the row.
 *
 * Not the middle of the title row: that is 26dp tall, so centring in it puts the button at 13
 * against the lights' 16.
 */
val CAPTURED_BUTTON_TOP: Dp = TRAFFIC_LIGHT_FIRST_CENTRE - TRAFFIC_LIGHT_DIAMETER / 2

/**
 * Where chrome must start when the captured-full-screen button is drawn beside the lights.
 *
 * [TRAFFIC_LIGHT_WIDTH] covers the three OS buttons plus 8pt of trailing air; the fourth extends
 * past it, so anything after the cluster clears its right edge plus the same air.
 */
val TRAFFIC_LIGHT_WIDTH_WITH_BUTTON: Dp =
    TRAFFIC_LIGHT_FIRST_CENTRE + TRAFFIC_LIGHT_PITCH * 3 + TRAFFIC_LIGHT_DIAMETER / 2 + 8.dp

/**
 * Which piece of chrome has to keep clear of the traffic lights, if any.
 *
 * The lights occupy a BOX - about 78dp wide and 28dp tall in the window's top-left corner - not a
 * band across the top. The old answer was a 26dp full-width row (`BossTitleBar`, whose only
 * content is a centred title string), which reserves the whole width to protect one corner.
 *
 * So the clearance goes on whatever is actually under that box, and which chrome that is depends
 * on what is switched on. Two cases were missed on the first attempt and both were visible:
 *
 * - The **top bar** spans the full width at y=0, so when it is on it is what the lights land on -
 *   no column is. Its leftmost control sat under the green button.
 * - The lights are **wider than one column**. An icon strip is 40dp, so a 78dp box covers the
 *   whole strip and another 38dp of whatever is beside it - which is the vertical tab bar. Insetting
 *   only the strip left the second half of the box over the bar's Favorites header.
 */
enum class TrafficLightInset {
    /** No inset: not macOS, or the title row is on and already holds them. */
    NONE,

    /**
     * The top bar is under them. It needs a horizontal indent of [TRAFFIC_LIGHT_WIDTH], not a
     * vertical one - the bar is a row, and the lights sit at its start.
     */
    TOP_BAR,

    /**
     * The columns down the left edge are under them: the icon strip, the vertical tab bar, or
     * both. Every one of them that falls inside [TRAFFIC_LIGHT_WIDTH] takes a top inset, which in
     * practice is both when both are on, since a strip alone is narrower than the box.
     */
    LEFT_COLUMNS,

    /**
     * Nothing down the left is wide enough to hold the box, so there is no column to inset.
     *
     * The caller draws the full-width title row for this, which is what keeps the buttons off the
     * content. Two alternatives were tried and are worse. Padding the content costs the same
     * height across the whole width, and the content is where a browser's native surface lives.
     * Indenting whatever row happens to be in the corner - the leftmost pane's tab strip - moves
     * that row's contents but leaves the buttons ON the pane: its focus boundary is drawn behind
     * them, so the active pane loses the top-left corner of its own outline.
     */
    CONTENT,

    /**
     * The update banner is up, and it is drawn above every bar and column, so it is what the
     * lights land on.
     *
     * This replaces [TOP_BAR] and [LEFT_COLUMNS] for as long as the banner exists, and replacing
     * is the whole point: the clearance belongs to exactly ONE piece of chrome. Leaving the
     * columns their inset while the banner took its own opened an empty 28dp band under the
     * banner, above the tab bar's Favorites shelf - clearance for lights that were no longer
     * there.
     *
     * It does NOT replace [NONE], which is what a title row being on produces: that row is ABOVE
     * the banner, so it goes on holding the lights and the banner needs nothing.
     */
    BANNER,
}

/**
 * Where the traffic-light clearance belongs for these settings.
 *
 * Pure, so the cases are a table rather than a conditional buried in the scaffold. Each wrong
 * answer is a visible defect - the buttons over a tab bar's Favorites header, over the top bar's
 * first control, or a 28dp gap above a column that needed none - and all of them are only visible
 * on macOS, which is not where most of this is developed.
 */
// Six inputs and a table of cases, which is the shape this wants: each one is a piece of chrome
// that can be over, beside or under the buttons, and folding any of them into a data class would
// put the case analysis somewhere the tests cannot reach it as directly.
@Suppress("LongParameterList")
fun macTrafficLightInset(
    appearance: WindowAppearanceSettings,
    isMacOs: Boolean,
    /**
     * Whether the update banner is currently drawing. See [TrafficLightInset.BANNER].
     *
     * Take it from [ai.rever.boss.updater.drawsBanner] rather than from "an update exists": most
     * of `UpdateState` draws no banner at all, and insetting for a banner that is not there is the
     * same empty band by another route.
     */
    bannerVisible: Boolean = false,
    /**
     * Whether the vertical bar is down to its slim rail.
     *
     * Take it from the MEASURED rail state (`SplitViewPanel.onBarRailedChange`), not from the
     * `tabBarCollapsed` preference: a bar also rails itself when the window is too narrow for a
     * full one, and a window that asked the preference read an auto-railed bar as a full column
     * and let the buttons land on the pane beside it.
     */
    barCollapsed: Boolean = false,
    /**
     * Whether a plugin panel is open on the left.
     *
     * It counts as a column, and a wide one - which is what lets a window with a collapsed rail
     * carry the clearance in its columns instead of falling back to the title row.
     */
    leftPanelOpen: Boolean = false,
    /** One icon rail's width at the current density. See [leftChromeWidth]. */
    stripWidth: Dp = ChromeDimens.MIN_STRIP_WIDTH,
): TrafficLightInset {
    val base =
        when {
            // Not macOS: the lights are somebody else's problem, and on Windows and Linux the title
            // row is a normal bar above the content rather than an overlay on top of it.
            !isMacOs -> {
                TrafficLightInset.NONE
            }

            // The row is on and is exactly what it is for.
            appearance.showTitleBar -> {
                TrafficLightInset.NONE
            }

            // Asked BEFORE the columns: the top bar is above them, so when it is on, it is what is
            // under the lights and the columns start below the box entirely.
            appearance.showTopBar -> {
                TrafficLightInset.TOP_BAR
            }

            // Only when the columns can actually hold the box. Insetting chrome narrower than
            // the lights protects part of the corner and leaves the rest on the pane behind it,
            // which is worse than not trying: the buttons end up over content that cannot be
            // inset, and over the active pane's own focus outline.
            leftChromeWidth(appearance, barCollapsed, stripWidth, leftPanelOpen) >= TRAFFIC_LIGHT_WIDTH -> {
                TrafficLightInset.LEFT_COLUMNS
            }

            else -> {
                TrafficLightInset.CONTENT
            }
        }

    // The banner takes over from whatever chrome it is drawn above, and only from that chrome.
    val coverable = base == TrafficLightInset.TOP_BAR || base == TrafficLightInset.LEFT_COLUMNS
    return if (bannerVisible && coverable) TrafficLightInset.BANNER else base
}

/**
 * The top inset for a left-hand column that starts [offsetFromLeft] in from the window's edge.
 *
 * The offset is what makes this per-COLUMN rather than one answer for all of them. They run strip,
 * then an open plugin panel, then the vertical tab bar, and the box is only 78dp wide - so which
 * of them it covers depends on what is switched on. Insetting "the columns" as a group was right
 * only while the tab bar was second: open a plugin panel and the panel is second, and the lights
 * landed on its header while the bar behind it kept a 28dp gap it did not need.
 */
fun TrafficLightInset.columnInset(offsetFromLeft: Dp = 0.dp): Dp =
    if (this == TrafficLightInset.LEFT_COLUMNS && offsetFromLeft < TRAFFIC_LIGHT_WIDTH) {
        TRAFFIC_LIGHT_HEIGHT
    } else {
        0.dp
    }

/**
 * The start indent for the update banner.
 *
 * Non-zero for exactly [TrafficLightInset.BANNER], which is the answer only when the banner is
 * both up and topmost. The banner is a row, so it takes a horizontal indent; the height it has to
 * keep is enforced where it is drawn.
 */
fun TrafficLightInset.bannerStartInset(): Dp = if (this == TrafficLightInset.BANNER) TRAFFIC_LIGHT_WIDTH else 0.dp

/** The start indent for the top bar, which is the width of the box or nothing. */
fun TrafficLightInset.barStartInset(): Dp = if (this == TrafficLightInset.TOP_BAR) TRAFFIC_LIGHT_WIDTH else 0.dp

/**
 * Whether the full-width title row has to be drawn.
 *
 * True when the user asked for it, and true for [TrafficLightInset.CONTENT] - there is no column
 * wide enough to inset, so the row is what keeps the buttons off the pane.
 *
 * The row is a real cost: it is 26dp of the window's full width to protect one 78x28dp corner, and
 * because the bar rails itself as a window narrows, it appears and disappears during a resize
 * drag. Both were the reason for trying to remove it. The reason it stays is that the alternatives
 * put the buttons on the pane itself, where they cover the active pane's focus outline and cannot
 * be moved out of the way - so the answer is to make CONTENT rarer, by counting every column that
 * can hold them, rather than to stop drawing the row.
 */
fun TrafficLightInset.needsTitleRow(showTitleBar: Boolean): Boolean = showTitleBar || this == TrafficLightInset.CONTENT

/** Where each left-hand column starts, for [columnInset]. */
data class LeftColumnOffsets(
    /** An open plugin panel, which follows the icon strip. */
    val panel: Dp,
    /** The vertical tab bar, which follows the panel when there is one. */
    val bar: Dp,
)

/**
 * Where the left columns start, given what is on screen.
 *
 * Pure, and here rather than inline in the scaffold, because the ORDER is the whole point and it
 * is not obvious from the composition: the strip is outermost, an open plugin panel comes next,
 * and the vertical tab bar is behind the panel - so the bar is only second when no panel is open.
 * Getting that backwards is what put the lights on a panel header while the bar, out of reach
 * behind it, kept a 28dp gap for them.
 */
fun leftColumnOffsets(
    showLeftStrip: Boolean,
    leftPanelOpen: Boolean,
    stripWidth: Dp,
): LeftColumnOffsets {
    val panel = if (showLeftStrip) stripWidth else 0.dp
    // TRAFFIC_LIGHT_WIDTH rather than the panel's measured width: a panel narrower than the box
    // would leave the remainder on the bar, but a sidebar panel is hundreds of dp and the floor a
    // user can drag it to is 20dp, so treating "a panel is open" as "the bar is clear" is right
    // everywhere except a deliberately collapsed sliver.
    val bar = if (leftPanelOpen) TRAFFIC_LIGHT_WIDTH else panel
    return LeftColumnOffsets(panel = panel, bar = bar)
}

/**
 * How much chrome runs down the window's left edge, which decides whether the corner can be
 * protected by insetting columns at all.
 *
 * The icon strip is one [stripWidth]; the vertical tab bar is its configured width, or the same
 * rail width when collapsed; an open plugin panel is counted as [TRAFFIC_LIGHT_WIDTH], since it is
 * a sidebar hundreds of dp wide and the only way to make it narrower than the box is to drag it to
 * its 20dp floor deliberately. A bar in TOP position contributes nothing.
 *
 * [stripWidth] is passed in rather than read here, because it is the DENSITY's value and this is
 * pure: the shipped Comfortable preset is 40dp, not the 36dp floor. Measuring with the floor made
 * a strip plus a rail 72dp and fell back to the title row, where what is drawn is 80dp and fits.
 * The default is the floor, which is the conservative direction: it can only over-reserve.
 */
internal fun leftChromeWidth(
    appearance: WindowAppearanceSettings,
    barCollapsed: Boolean,
    stripWidth: Dp = ChromeDimens.MIN_STRIP_WIDTH,
    leftPanelOpen: Boolean = false,
): Dp {
    val strip = if (appearance.showLeftStrip) stripWidth else 0.dp
    val panel = if (leftPanelOpen) TRAFFIC_LIGHT_WIDTH else 0.dp
    val bar =
        when {
            appearance.tabBarPosition != TabBarPosition.LEFT -> 0.dp
            barCollapsed -> stripWidth
            else -> appearance.tabBarVerticalWidth.dp
        }
    return strip + panel + bar
}
