package ai.rever.boss.components.buttons

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.sidebar.rememberSidebarSettingsMenuItems
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.opposite
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp

@Composable
fun BossDraggableComponent.DraggableActionButton(
    item: SidebarItem,
    slot: Panel,
    modifier: Modifier = Modifier
) {
    val currentItem by rememberUpdatedState(item)
    val currentSlot by rememberUpdatedState(slot)
    val isBeingDragged = draggingItem?.first?.id == item.id

    var componentPositionInWindow by remember { mutableStateOf<Offset?>(null) }
    var pendingDragStartOffset by remember { mutableStateOf<Offset?>(null) }

    // Right-click (long-press on touch) → "Sidebar settings"
    val settingsMenuItems = rememberSidebarSettingsMenuItems()

    // Log recomposition state

    LaunchedEffect(componentPositionInWindow, pendingDragStartOffset) {
        val startOffset = pendingDragStartOffset
        val currentPos = componentPositionInWindow


        if (startOffset != null && currentPos != null) {
            val startPosition = currentPos + startOffset
            startDragging(currentItem, currentSlot, startPosition)
            // Reset pending offset AFTER starting the drag
            pendingDragStartOffset = null
        }
    }

    // Cleanup drag state if this component is disposed while dragging
    // This prevents "stuck" drag overlays when gesture is interrupted
    DisposableEffect(item.id) {
        onDispose {
            // Only cancel if THIS item is the one being dragged
            if (draggingItem?.first?.id == item.id) {
                stopDragging()
            }
        }
    }

    BossActionButton(
        imageVector = item.icon,
        text = item.label,
        hintDirection = slot.opposite,
        isSelected = isSelected(item),
        modifier = modifier
            .onGloballyPositioned { layoutCoordinates ->
                val newPos = layoutCoordinates.positionInWindow()
                // Only update state if the position actually changed to avoid redundant recompositions/effect triggers
                if (componentPositionInWindow != newPos) {
                    componentPositionInWindow = newPos
                }
            }
            .size(40.dp)
            .alpha(if (isBeingDragged) 0f else 1f)
            .contextMenu(items = settingsMenuItems)
            .pointerInput(Unit) { // Keep Unit key
                detectDragGesturesAfterLongPress(
                    onDragStart = { touchOffset ->
                        // Just record the intention to drag
                        pendingDragStartOffset = touchOffset
                    },
                    onDragEnd = {
                        if (pendingDragStartOffset != null) {
                            pendingDragStartOffset = null
                        }
                        if (draggingItem != null) {
                            stopDragging()
                        }
                    },
                    onDragCancel = {
                        pendingDragStartOffset = null
                        if (draggingItem != null) {
                            stopDragging()
                        }
                    },
                    onDrag = { change: PointerInputChange, dragAmount: Offset ->
                        // Check model state directly to see if drag has officially started
                        if (draggingItem?.first?.id == item.id) {
                            change.consume()
                            updateDragDelta(dragAmount)
                        }
                    }
                )
            }
    ) {
        handleSidebarItemClick(item)
    }
}
