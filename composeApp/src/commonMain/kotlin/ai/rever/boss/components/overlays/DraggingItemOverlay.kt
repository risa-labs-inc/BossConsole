package ai.rever.boss.components.overlays

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round

// Overlay composable to draw the ghost item following the pointer
@Composable
fun BossDraggableComponent.DraggingItemOverlay() {
    // Observe the dragging item and its position from the model
    val draggedItemInfo = draggingItem
    // Get the start position and delta from the model
    val startPosition = dragStartPosition
    val delta = dragDelta

    if (draggedItemInfo != null && startPosition != null) {
        val (item, _) = draggedItemInfo
        // Calculate the current absolute position
        val currentPosition = startPosition + delta

        // Calculate the offset to center the ghost icon on the pointer
        val iconSizePx = with(LocalDensity.current) { 22.dp.toPx() }
        val centeredOffset = Offset(currentPosition.x - iconSizePx / 2, currentPosition.y - iconSizePx / 2)

        Box(
            modifier = Modifier
                // Position the ghost based on calculated absolute position, centered
                .offset { centeredOffset.round() }
                .alpha(0.7f) // Apply transparency
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null, // Decorative
                modifier = Modifier.size(22.dp), // Match icon size
                tint = BossTheme.colors.textPrimary
            )
        }
    }
}
