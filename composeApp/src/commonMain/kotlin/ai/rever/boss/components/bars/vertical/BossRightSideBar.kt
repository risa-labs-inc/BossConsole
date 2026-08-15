package ai.rever.boss.components.bars.vertical

import ai.rever.boss.components.dividers.SDivider
import ai.rever.boss.components.dividers.VDivider
import ai.rever.boss.components.misc.DraggableSidebarSection
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.sidebar.SidebarIconRail
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import ai.rever.boss.components.sidebar.computeSlotIconLimits
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The right icon rail.
 *
 * [bottomActions] are host-owned icons pinned below the draggable slots, which is where the focus
 * mode quick actions go while the top bar is cleared (see `focusQuickActionsPlacement`). Empty by
 * default and empty most of the time: the bar then reserves nothing for them and is unchanged.
 */
@Composable
fun BossDraggableComponent.BossRightSideBar(bottomActions: List<@Composable () -> Unit> = emptyList()) {
    val visibility by SidebarVisibilitySettingsManager.currentSettings.collectAsState()
    val customizeSlotId = visibility.customizeButtonSlotId
    val customizeOnThisBar = !SidebarVisibilitySettings.isLeftSide(customizeSlotId)

    VDivider()
    VerticalBar(40.dp) {
        // BoxWithConstraints gives the rail's full height so adaptive
        // mode can budget icon rows; recomposes on window resize.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val iconLimits =
                computeSlotIconLimits(
                    slots = listOf(right.top.top, right.top.bottom),
                    settings = visibility,
                    barHeight = maxHeight,
                    // The bottom actions are not one of the slots above, and they are laid out
                    // after the weighted spacer - so without reserving for them a full rail
                    // would push them off the bottom of the window instead of capping an icon.
                    reservedHeight =
                        SidebarIconRail.SectionDivider +
                            (if (customizeOnThisBar) SidebarIconRail.CustomizeButton else 0.dp) +
                            SidebarIconRail.bottomSectionHeight(bottomActions.size),
                )
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DraggableSidebarSection(
                    slot = right.top.top,
                    maxVisibleIcons = iconLimits[right.top.top],
                )
                if (customizeSlotId == SidebarVisibilitySettings.SLOT_RIGHT_TOP_TOP) {
                    SidebarCustomizeMenu(slot = right.top.top)
                }
                SDivider()
                DraggableSidebarSection(
                    slot = right.top.bottom,
                    maxVisibleIcons = iconLimits[right.top.bottom],
                )
                if (customizeSlotId == SidebarVisibilitySettings.SLOT_RIGHT_TOP_BOTTOM) {
                    SidebarCustomizeMenu(slot = right.top.bottom)
                }
                Spacer(modifier = Modifier.weight(1f))
                SidebarBottomActions(bottomActions)
            }
        }
    }
}
