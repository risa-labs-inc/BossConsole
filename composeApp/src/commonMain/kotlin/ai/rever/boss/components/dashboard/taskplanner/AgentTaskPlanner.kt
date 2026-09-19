package ai.rever.boss.components.dashboard.taskplanner

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Represents the execution state of an AI agent task.
 */
enum class AgentTaskStatus {
    TODO,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

/**
 * A single step in an AI agent workflow.
 */
data class AgentTask(
    val title: String,
    val status: AgentTaskStatus,
)

/**
 * Displays a compact workflow for multi-step AI agent tasks.
 */
@Composable
fun AgentTaskPlanner(
    tasks: List<AgentTask>,
    modifier: Modifier = Modifier,
) {
    val completed = tasks.count { it.status == AgentTaskStatus.COMPLETED }
    val progress = if (tasks.isEmpty()) 0 else (completed * 100) / tasks.size

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AI Task Plan",
                color = BossTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = "$completed/${tasks.size} completed",
                color = BossTheme.colors.textSecondary,
                fontSize = 12.sp,
            )
        }

        tasks.forEach { task ->
            val symbol =
                when (task.status) {
                    AgentTaskStatus.COMPLETED -> "✓"
                    AgentTaskStatus.IN_PROGRESS -> "●"
                    AgentTaskStatus.FAILED -> "!"
                    AgentTaskStatus.TODO -> "○"
                }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = symbol,
                    color = BossTheme.colors.data,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )

                Text(
                    text = task.title,
                    color = BossTheme.colors.textPrimary,
                    fontSize = 13.sp,
                )
            }
        }

        Text(
            text = "Progress: $progress%",
            color = BossTheme.colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}
