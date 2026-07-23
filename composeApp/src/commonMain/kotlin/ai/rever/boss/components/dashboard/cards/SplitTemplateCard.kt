package ai.rever.boss.components.dashboard.cards

import BossDarkSurface
import BossDarkTextSecondary
import ai.rever.boss.dashboard.SplitTemplate
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
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

/**
 * Card displaying a split template with visual preview.
 */
@Composable
fun SplitTemplateCard(
    template: SplitTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f)
    )

    val backgroundColor = if (isHovered) BossTheme.colors.signalWash else BossDarkSurface
    val borderColor = if (isHovered) BossTheme.colors.signal.copy(alpha = 0.5f) else Color.Transparent

    val cardShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .width(180.dp)
            .height(140.dp) // Fixed height for consistency
            .scale(scale)
            .clip(cardShape)
            .background(color = backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = cardShape
            )
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Template name
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = template.name,
                color = BossTheme.colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Visual preview of the split layout
        SplitPreview(template)

        // Description
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = template.description,
                color = BossDarkTextSecondary,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Visual preview showing the split layout with panel type icons.
 */
@Composable
private fun SplitPreview(template: SplitTemplate) {
    val leftPanel = template.panels.find { it.position == "left" }
    val rightPanel = template.panels.find { it.position == "right" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BossTheme.colors.ink),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Left panel
        if (leftPanel != null) {
            PanelPreview(
                type = leftPanel.type,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // Right panel
        if (rightPanel != null) {
            PanelPreview(
                type = rightPanel.type,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/**
 * Single panel preview with type icon.
 */
@Composable
private fun PanelPreview(
    type: String,
    modifier: Modifier = Modifier
) {
    val (icon, color, label) = when (type) {
        "terminal" -> Triple(Icons.Outlined.Terminal, BossTheme.colors.ok, "Term")
        "browser" -> Triple(Icons.Outlined.Language, BossTheme.colors.data, "Web")
        // Deliberate one-off: the design system has no purple token (editor identity color).
        "editor" -> Triple(Icons.Outlined.Code, Color(0xFFB877DB), "Code")
        else -> Triple(Icons.Outlined.Code, BossDarkTextSecondary, type)
    }

    Box(
        modifier = modifier.background(BossTheme.colors.raised),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = type,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = color.copy(alpha = 0.8f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
