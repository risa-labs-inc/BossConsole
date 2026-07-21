package ai.rever.boss.components.dashboard.cards

import BossDarkAccent
import BossDarkSurface
import BossDarkTextSecondary
import ai.rever.boss.window.Project
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Card displaying a recent project.
 */
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f)
    )

    val backgroundColor = if (isHovered) Color(0xFF2A2D30) else BossDarkSurface
    val cardShape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .width(140.dp)
            .scale(scale)
            .clip(cardShape)
            .background(color = backgroundColor)
            .clickable { onClick() }
            .hoverable(interactionSource)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Project logo - same as top bar selection
            Surface(
                modifier = Modifier
                    .padding(2.dp)
                    .size(32.dp),
                shape = RoundedCornerShape(4.dp),
                color = BossDarkAccent,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val initials = when {
                        project.name.length >= 2 -> project.name.substring(0, 2)
                        project.name.isNotEmpty() -> project.name[0].toString()
                        else -> "?"
                    }
                    Text(
                        text = initials.uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Text(
                text = project.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = formatRelativeTime(project.lastOpened),
                color = BossDarkTextSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }

        // Remove button (visible on hover)
        if (onRemove != null) {
            AnimatedVisibility(
                visible = isHovered,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3D40))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = BossDarkTextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Format timestamp as relative time (e.g., "2h ago", "Yesterday").
 */
private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return "Never"

    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 172800_000 -> "Yesterday"
        else -> SimpleDateFormat("MMM d").format(Date(timestamp))
    }
}
