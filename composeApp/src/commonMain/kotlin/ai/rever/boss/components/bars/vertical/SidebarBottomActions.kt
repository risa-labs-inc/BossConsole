package ai.rever.boss.components.bars.vertical

import ai.rever.boss.components.dividers.SDivider
import ai.rever.boss.components.sidebar.SidebarIconRail
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
 * **The caller must reserve [SidebarIconRail.bottomSectionHeight] for it.** This section is
 * outside the slots `computeSlotIconLimits` budgets, and it is laid out *after* the weighted
 * spacer, so in adaptive mode a rail that has spent its whole height on plugin icons will push
 * this off the bottom of the window rather than shrink to fit it. `bottomSectionHeight` is what
 * the two sides agree on and is measured against this layout by a test, because the failure is
 * three actions the user cannot reach and nothing in the build says so.
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
        actions.forEachIndexed { index, action ->
            // Positional keys: the list is fixed-length and built fresh each pass, so an index is
            // the only stable identity there is. It is enough to keep each button's hover state
            // with the button rather than with the position.
            key(index) {
                Box(modifier = Modifier.padding(vertical = ROW_GAP)) { action() }
            }
        }
    }
}

/**
 * Half the gap between icon rows, applied above and below each - the same 4dp
 * `DraggableSidebarSection` gives the icons above, so the two sections read as one rail rather
 * than as two lists that happen to touch. A 32dp icon plus this on both sides is one
 * [SidebarIconRail.RowPitch].
 */
private val ROW_GAP = 4.dp
