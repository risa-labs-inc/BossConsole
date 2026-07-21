package ai.rever.boss.components.overlays

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkSurface
import BossDarkTextPrimary
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Overlay composable to draw a ghost tab following the pointer during drag.
 */
@Composable
fun TabDraggableComponent.TabDraggingOverlay() {
    val dragging = draggingTab ?: return
    val startPosition = dragStartPosition ?: return
    val delta = dragDelta

    val currentPosition = startPosition + delta

    // Offset to position the ghost tab with its left edge near the cursor
    val density = LocalDensity.current
    val tabWidthPx = with(density) { 180.dp.toPx() }
    val tabHeightPx = with(density) { 32.dp.toPx() }

    // Position slightly offset from cursor so user can see where they're dropping
    val offsetPosition = Offset(
        currentPosition.x - tabWidthPx / 4,
        currentPosition.y - tabHeightPx / 2
    )

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPosition.x.toInt(), offsetPosition.y.toInt()) }
            .shadow(8.dp, RoundedCornerShape(4.dp))
            .width(180.dp)
            .height(32.dp)
            .background(BossDarkSurface.copy(alpha = 0.95f), RoundedCornerShape(4.dp))
            .border(1.dp, BossDarkAccent, RoundedCornerShape(4.dp))
            .alpha(0.9f)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab icon
            when (val icon = dragging.icon) {
                is ai.rever.boss.plugin.api.TabIcon.Vector -> {
                    Icon(
                        imageVector = icon.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = BossDarkTextPrimary
                    )
                }
                is ai.rever.boss.plugin.api.TabIcon.Image -> {
                    Icon(
                        painter = icon.painter,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = BossDarkTextPrimary
                    )
                }
                null -> {
                    // Use default icon from tabInfo
                    Icon(
                        imageVector = dragging.tabInfo.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = BossDarkTextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Tab title
            Text(
                text = dragging.title,
                color = BossDarkTextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
