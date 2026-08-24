package ai.rever.boss.components.overlays

import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The ghost card's size. Shared with the ghost's own window, which must match it exactly. */
private val GHOST_WIDTH = 180.dp
private val GHOST_HEIGHT = 32.dp

/**
 * Overlay composable to draw a ghost tab following the pointer during drag.
 */
@Composable
fun TabDraggableComponent.TabDraggingOverlay() {
    val dragging = draggingTab ?: return
    val startPosition = dragStartPosition ?: return
    val delta = dragDelta

    val currentPosition = startPosition + delta

    // Where the pointer sits inside the ghost card: a quarter in from the leading edge, vertically
    // centred, so the drop point is visible beside it. Both paths take it from this one value -
    // stated twice, they drift, and the drift shows up only in whichever rendering mode you are not
    // in.
    val hotspot = DpOffset(GHOST_WIDTH / 4, GHOST_HEIGHT / 2)
    val hotspotPx = with(LocalDensity.current) { Offset(hotspot.x.toPx(), hotspot.y.toPx()) }
    val offsetPosition = currentPosition - hotspotPx

    // OverlayGhost puts the ghost in its own cursor-tracking window under HARDWARE, where the
    // browser's native surface would otherwise paint over it - so the ghost used to vanish for
    // exactly the part of the drag that crosses a page. On OFF_SCREEN this is the same offset Box
    // it has always been.
    OverlayGhost(
        size = DpSize(GHOST_WIDTH, GHOST_HEIGHT),
        hotspot = hotspot,
        windowOffset = { IntOffset(offsetPosition.x.toInt(), offsetPosition.y.toInt()) },
    ) {
        Box(
            modifier =
                Modifier
                    .shadow(8.dp, RoundedCornerShape(4.dp))
                    .width(GHOST_WIDTH)
                    .height(GHOST_HEIGHT)
                    .background(BossTheme.colors.raised.copy(alpha = 0.95f), RoundedCornerShape(4.dp))
                    .border(1.dp, BossTheme.colors.signal, RoundedCornerShape(4.dp))
                    .alpha(0.9f),
        ) {
            Row(
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp)
                        .align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tab icon
                when (val icon = dragging.icon) {
                    is ai.rever.boss.plugin.api.TabIcon.Vector -> {
                        Icon(
                            imageVector = icon.imageVector,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BossTheme.colors.textPrimary,
                        )
                    }

                    is ai.rever.boss.plugin.api.TabIcon.Image -> {
                        Icon(
                            painter = icon.painter,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BossTheme.colors.textPrimary,
                        )
                    }

                    null -> {
                        // Use default icon from tabInfo
                        Icon(
                            imageVector = dragging.tabInfo.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BossTheme.colors.textPrimary,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Tab title
                Text(
                    text = dragging.title,
                    color = BossTheme.colors.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}
