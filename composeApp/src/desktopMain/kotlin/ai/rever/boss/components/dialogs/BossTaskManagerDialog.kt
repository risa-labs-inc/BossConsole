package ai.rever.boss.components.dialogs

import ai.rever.boss.kernel.KernelBootstrap
import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.process.ManagedProcess
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

@Composable
fun BossTaskManagerDialog(onDismiss: () -> Unit) {
    val registry = KernelBootstrap.instance?.processRegistry
    val processCount by registry?.processCount?.collectAsState(0) ?: mutableStateOf(0)
    var processes by remember { mutableStateOf<List<ManagedProcess>>(emptyList()) }

    LaunchedEffect(processCount) {
        while (true) {
            processes = registry?.getAllProcesses() ?: emptyList()
            delay(1000)
        }
    }

    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val maxWidth = 800.dp
    val maxHeight = 600.dp

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).width(750.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Agent Task Manager",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Live monitoring of all background plugins, agents, and kernels.",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (processes.isEmpty()) {
                        Text(
                            text = "No processes currently running.",
                            fontSize = 14.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        processes.sortedByDescending { it.startTime }.forEach { process ->
                            ProcessRow(process, colors)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = colors.raised,
                            contentColor = colors.textPrimary,
                        ),
                        shape = RoundedCornerShape(radii.button),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(process: ManagedProcess, colors: BossColorScheme) {
    val isAlive = process.isAlive
    val uptime = if (isAlive) (System.currentTimeMillis() - process.startTime) / 1000 else 0
    val uptimeStr = if (uptime > 60) "${uptime / 60}m ${uptime % 60}s" else "${uptime}s"
    
    Surface(
        color = colors.raised,
        shape = RoundedCornerShape(BossTheme.radius.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isAlive) Color(0xFF4CAF50) else Color(0xFFE53935))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = process.config.processType.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "PID: ${process.pid} • Uptime: $uptimeStr • Restarts: ${process.restartCount}",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            if (isAlive) {
                Button(
                    onClick = { process.process.destroyForcibly() },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = colors.alert,
                        contentColor = colors.textPrimary,
                    ),
                    shape = RoundedCornerShape(BossTheme.radius.button),
                ) {
                    Text("Kill")
                }
            }
        }
    }
}
