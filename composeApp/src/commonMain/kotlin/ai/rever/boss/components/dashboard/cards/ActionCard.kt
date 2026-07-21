package ai.rever.boss.components.dashboard.cards

import BossDarkSurface
import BossDarkTextSecondary
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Card for quick actions with optional keyboard shortcut.
 */
@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    shortcut: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f)
    )

    val backgroundColor = if (isHovered) Color(0xFF2A2D30) else BossDarkSurface
    val iconColor = if (isHovered) Color(0xFF4A9EFF) else BossDarkTextSecondary

    val cardShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .width(120.dp)
            .height(110.dp) // Fixed height for consistency
            .scale(scale)
            .clip(cardShape)
            .background(color = backgroundColor)
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconColor,
            modifier = Modifier.size(32.dp)
        )

        // Title with fixed height for consistency
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = if (isHovered) Color.White else BossDarkTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        // Shortcut with fixed height for consistency
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.Center
        ) {
            if (shortcut != null) {
                Text(
                    text = shortcut,
                    color = BossDarkTextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}
