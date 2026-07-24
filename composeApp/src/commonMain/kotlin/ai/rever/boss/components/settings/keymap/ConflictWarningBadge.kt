package ai.rever.boss.components.settings.keymap

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.boss.keymap.model.KeyBinding

/**
 * Badge component that displays a warning when keyboard shortcut conflicts are detected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConflictWarningBadge(
    conflicts: List<KeyBinding>,
    modifier: Modifier = Modifier
) {
    if (conflicts.isEmpty()) return

    val conflictCount = conflicts.size

    TooltipArea(
        tooltip = {
            ConflictTooltip(conflicts)
        }
    ) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.alert.copy(alpha = 0.9f),
            elevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Conflict warning",
                    modifier = Modifier.size(16.dp),
                    tint = Color.White
                )
                Text(
                    text = "$conflictCount conflict${if (conflictCount > 1) "s" else ""}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Tooltip showing details of conflicts.
 */
@Composable
private fun ConflictTooltip(conflicts: List<KeyBinding>) {
    Surface(
        modifier = Modifier.widthIn(max = 400.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colors.surface,
        elevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "⚠️ Shortcut Conflicts",
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Bold,
                color = BossTheme.colors.alert
            )

            Divider()

            Text(
                text = "This key combination is used by:",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )

            conflicts.forEach { binding ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = binding.description,
                            style = MaterialTheme.typography.body2,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${binding.category} • ${binding.context.displayName}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Divider()

            Text(
                text = "💡 Tip: Edit or disable one of these shortcuts to resolve the conflict",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary.copy(alpha = 0.8f)
            )
        }
    }
}
