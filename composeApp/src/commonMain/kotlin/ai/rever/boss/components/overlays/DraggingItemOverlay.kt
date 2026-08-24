package ai.rever.boss.components.overlays

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round

/** The ghost icon's size. Shared with the ghost's own window, which must match it exactly. */
private val GHOST_ICON_SIZE = 22.dp

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

        // Centred on the pointer: the icon is being carried, so the pointer holds its middle. The
        // two paths take it from the same value rather than each doing the arithmetic - stated
        // twice, they drift, and the drift shows up only in whichever rendering mode you are not in.
        val hotspot = DpOffset(GHOST_ICON_SIZE / 2, GHOST_ICON_SIZE / 2)
        val hotspotPx = with(LocalDensity.current) { Offset(hotspot.x.toPx(), hotspot.y.toPx()) }

        // Under HARDWARE the browser's native surface paints over the Compose scene, so this ghost
        // disappeared as soon as the drag crossed a page. OverlayGhost gives it its own
        // cursor-tracking window there; on OFF_SCREEN it is the same offset Box as before.
        OverlayGhost(
            size = DpSize(GHOST_ICON_SIZE, GHOST_ICON_SIZE),
            hotspot = hotspot,
            windowOffset = { (currentPosition - hotspotPx).round() },
        ) {
            Box(
                modifier = Modifier.alpha(0.7f), // Apply transparency
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null, // Decorative
                    modifier = Modifier.size(GHOST_ICON_SIZE), // Match icon size
                    tint = BossTheme.colors.textPrimary,
                )
            }
        }
    }
}
