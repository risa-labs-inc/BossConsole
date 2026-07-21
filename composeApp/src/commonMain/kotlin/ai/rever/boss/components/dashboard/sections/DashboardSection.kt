package ai.rever.boss.components.dashboard.sections

import BossDarkTextSecondary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable section wrapper for the Dashboard.
 * Displays a title, optional subtitle, optional action button, and content.
 */
@Composable
fun DashboardSection(
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header row with title and optional action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = BossDarkTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            if (actionText != null && onAction != null) {
                Text(
                    text = actionText,
                    color = Color(0xFF4A9EFF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onAction() }
                        .padding(4.dp)
                )
            }
        }

        // Section content
        content()
    }
}
