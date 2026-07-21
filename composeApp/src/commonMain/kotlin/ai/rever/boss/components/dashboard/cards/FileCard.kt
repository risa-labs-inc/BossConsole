package ai.rever.boss.components.dashboard.cards

import BossDarkSurface
import BossDarkTextSecondary
import ai.rever.boss.dashboard.RecentFile
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.utils.extractParentName
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Card displaying a recent file.
 */
@Composable
fun FileCard(
    file: RecentFile,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f)
    )

    val backgroundColor = if (isHovered) Color(0xFF2A2D30) else BossDarkSurface
    val fileIconInfo = FileIcons.forFile(file.name)
    val cardShape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier.width(120.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(cardShape)
                .background(color = backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = fileIconInfo.icon,
                contentDescription = file.name,
                tint = fileIconInfo.color,
                modifier = Modifier.size(32.dp)
            )

            // File name with fixed height for consistency
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = file.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // Parent folder with fixed height for consistency
            val parentFolder = file.path.extractParentName()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (parentFolder.isNotEmpty()) {
                    Text(
                        text = parentFolder,
                        color = BossDarkTextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Remove button (visible on hover)
        if (isHovered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .background(
                        color = Color(0xFF3A3D40),
                        shape = CircleShape
                    )
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = BossDarkTextSecondary,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

