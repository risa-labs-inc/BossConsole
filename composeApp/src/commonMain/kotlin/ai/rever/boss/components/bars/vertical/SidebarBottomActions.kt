package ai.rever.boss.components.bars.vertical

import ai.rever.boss.components.dividers.SDivider
import ai.rever.boss.components.sidebar.SidebarIconRail
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag of the section - see `SidebarBottomActionsLayoutTest`. */
internal const val SIDEBAR_BOTTOM_ACTIONS_TAG = "sidebar-bottom-actions"

/**
 * A fixed set of icons pinned below a sidebar rail's draggable slots, under a divider.
 *
 * Deliberately **not** a `SidebarSlotContainer`. The slots are drop targets that register their
 * bounds with the drag model and whose contents the user reorders; this is chrome the host put
 * there, and making it a slot would let an icon be dragged into a section that is only present
 * under some conditions - and dropped into one that is about to disappear.
 *
 * Renders nothing at all when [actions] is empty, divider included, so a bar with no bottom
 * section is byte-for-byte the bar that existed before there was one.
 *
 * **The caller must both reserve [SidebarIconRail.bottomSectionHeight] for it and lay it out
 * outside the weighted region**, and the two do different jobs. The reserve keeps the slots from
 * spending the whole rail on plugin icons, so an icon folds into its More menu instead of anything
 * overlapping - but it is advisory only: `computeSlotIconLimits` returns early in
 * [ai.rever.boss.components.sidebar.SidebarIconLimitMode.FIXED] mode and never reads
 * `reservedHeight` at all, and no reserve helps a rail too short to hold both. The layout is what
 * guarantees this section is on screen: give the *slots* the weight and leave this outside it, so
 * it is measured first and they clip.
 *
 * Getting that backwards is not a cosmetic bug. The content pushed past the bottom of the window
 * is Settings, Search and Sign Out, and in `RIGHT_RAIL` placement the floating cluster is not
 * rendered as a backstop - on the Windows defaults, where hover-reveal is off, that leaves the OS
 * menu bar and a keyboard shortcut as the only ways to reach them.
 */
@Composable
internal fun ColumnScope.SidebarBottomActions(actions: List<@Composable () -> Unit>) {
    if (actions.isEmpty()) return

    SDivider()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // testTag BEFORE padding, and the order is load-bearing rather than style. A tag
                // applied after it names the node inside the padding, so the section's own chrome
                // falls outside its reported bounds - which is 8dp of the height the rail reserves,
                // measured by a test that would then be quietly checking the wrong number.
                .testTag(SIDEBAR_BOTTOM_ACTIONS_TAG)
                .padding(vertical = ROW_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No `key`: the list is fixed-length and fixed-order, so positional identity is already
        // what a key would give, and `QuickActions` invokes the same list the same way.
        actions.forEach { action ->
            Box(modifier = Modifier.padding(vertical = ROW_GAP)) { action() }
        }
    }
}

/**
 * Half the gap between icon rows, applied above and below each, so a 32dp icon plus this on both
 * sides is exactly one [SidebarIconRail.RowPitch] and the reserve arithmetic is a whole number of
 * rows.
 *
 * It is the same 4dp `DraggableSidebarSection` puts *between* its icons, which is what makes the
 * two sections read as one rail. Not identical at the edges though: that section gives its first
 * and last icons 4dp on the inside only, where every icon here gets 4dp on both - so this
 * section's outer edges are 8dp against the slots' 4dp. Deliberate, and counted: it is the
 * `SlotChrome` term in [SidebarIconRail.bottomSectionHeight], which the layout test measures.
 */
private val ROW_GAP = 4.dp
