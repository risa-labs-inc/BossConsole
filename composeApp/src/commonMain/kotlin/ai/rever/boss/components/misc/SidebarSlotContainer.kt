package ai.rever.boss.components.misc

import ai.rever.boss.components.bars.vertical.SidebarOverflowButton
import ai.rever.boss.components.buttons.DraggableActionButton
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.plugin.api.Panel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

// A container for a specific sidebar slot, handling hover feedback
@Composable
fun SidebarSlotContainer(
    slot: Panel,
    sidebarModel: BossDraggableComponent,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Determine hover state based on the model's dropTargetSlot
    val isHovered = sidebarModel.dropTargetSlot == slot && sidebarModel.draggingItem != null
    val borderColor = if (isHovered) Color.Black else Color.Transparent
    // Use a semi-transparent black background for hover indication ("black slot" on hover)
    val backgroundColor = if (isHovered) Color.Black.copy(alpha = 0.3f) else Color.Transparent

    // Use rememberUpdatedState for slot to ensure DisposableEffect cleans up correctly if slot changes
    val currentSlot by rememberUpdatedState(slot)

    DisposableEffect(currentSlot, sidebarModel) {
        onDispose {
            // Remove bounds when the composable leaves the composition
            sidebarModel.slotBounds.remove(currentSlot)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor)
            .padding(vertical = 4.dp) // Padding inside the slot
            .onGloballyPositioned { coordinates ->
                // Register this slot's bounds (in window coordinates) with the model
                sidebarModel.slotBounds[currentSlot] = coordinates.boundsInWindow()
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
    }
}

// Renders a specific section of the sidebar using the DraggableSidebarModel
@Composable
fun BossDraggableComponent.DraggableSidebarSection(
    slot: Panel,
    modifier: Modifier = Modifier,
    /**
     * Cap on plugin icons rendered inline; items beyond it collapse into
     * a trailing [SidebarOverflowButton]. null = unlimited (legacy
     * behaviour). Computed per bar by
     * [ai.rever.boss.components.sidebar.computeSlotIconLimits].
     */
    maxVisibleIcons: Int? = null,
) {
    // Observe sidebar visibility and pass the hidden set explicitly into
    // getItemsForSlot — that's what registers the Compose snapshot
    // observation that re-renders this section when a checkbox toggle in
    // the customize menu mutates the set. Reading .value inside the model
    // method wouldn't, so the parameter is the contract.
    val visibility by ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
        .currentSettings.collectAsState()

    SidebarSlotContainer(
        slot = slot,
        sidebarModel = this,
        modifier = modifier
    ) {
        val items = getItemsForSlot(slot, visibility.hiddenPanelIds)
        val visibleItems = if (maxVisibleIcons != null && items.size > maxVisibleIcons) {
            items.take(maxVisibleIcons.coerceAtLeast(0))
        } else {
            items
        }
        visibleItems.forEachIndexed { index, item ->

            key (item.id) {
                DraggableActionButton(
                    item = item,
                    slot = slot,
                    modifier = Modifier
                        .run {
                            if (index == 0) {
                                padding(bottom = 4.dp)
                            } else if (index == visibleItems.lastIndex) {
                                padding(top = 4.dp)
                            } else {
                                padding(vertical = 4.dp)
                            }
                        }
                        .size(32.dp)
                )
            }
        }
        if (visibleItems.size < items.size) {
            SidebarOverflowButton(
                slot = slot,
                items = items.drop(visibleItems.size),
            )
        }
        // Add a minimum height to the slot even when empty to ensure it's a valid drop target
        if (items.isEmpty()) {
            Spacer(Modifier.height(40.dp)) // Height approx one button
        }
    }
}
