package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.dialogs.McpMissionControlDialog
import ai.rever.boss.mcp.McpTelemetryRecorder
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom-bar telemetry indicator for agent MCP operations.
 * Clicking opens the full [McpMissionControlDialog].
 */
@Composable
fun McpInspectorStatusItem() {
    val stats by McpTelemetryRecorder.stats.collectAsState()
    val globalSafeMode by McpTelemetryRecorder.globalSafeMode.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    val colors = BossTheme.colors
    val radii = BossTheme.radius

    val label = when {
        stats.activeInFlight > 0 -> "⚡ MCP: ${stats.activeInFlight} running"
        stats.totalCalls > 0 -> "⚡ MCP: ${stats.totalCalls} calls"
        globalSafeMode -> "⚡ MCP: Safe Mode"
        else -> "⚡ MCP: Idle"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clickable { showDialog = true }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        // Active pulsing / idle dot
        val dotColor = when {
            stats.pendingApprovalsCount > 0 -> colors.warn
            stats.activeInFlight > 0 -> colors.signal
            stats.errorCount > 0 -> colors.alert
            else -> colors.ok
        }

        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )

        Text(
            text = label,
            fontSize = 11.sp,
            color = colors.textSecondary,
            maxLines = 1,
        )

        // Pending approval badge
        if (stats.pendingApprovalsCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(radii.button))
                    .background(colors.warn)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "${stats.pendingApprovalsCount} wait",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.signalText,
                )
            }
        }

        // Error badge if errors present
        if (stats.errorCount > 0 && stats.pendingApprovalsCount == 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(radii.button))
                    .background(colors.alert.copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "${stats.errorCount} err",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.alert,
                )
            }
        }
    }

    if (showDialog) {
        McpMissionControlDialog(onDismiss = { showDialog = false })
    }
}
