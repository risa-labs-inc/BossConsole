package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.lang.management.ManagementFactory

@Composable
fun BossSystemHealthDialog(onDismiss: () -> Unit) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val maxWidth = 500.dp

    var usedMemoryMB by remember { mutableStateOf(0L) }
    var totalMemoryMB by remember { mutableStateOf(0L) }
    var maxMemoryMB by remember { mutableStateOf(0L) }
    var threadCount by remember { mutableStateOf(0) }
    var uptimeSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val runtime = Runtime.getRuntime()
        val bean = ManagementFactory.getRuntimeMXBean()
        while (true) {
            val total = runtime.totalMemory()
            val free = runtime.freeMemory()
            usedMemoryMB = (total - free) / (1024 * 1024)
            totalMemoryMB = total / (1024 * 1024)
            maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
            threadCount = Thread.activeCount()
            uptimeSeconds = bean.uptime / 1000
            delay(1000)
        }
    }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).width(450.dp),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "System Health",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Live diagnostics and memory usage for Boss Console.",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Stats Grid
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HealthRow("JVM Memory (Used / Total)", "${usedMemoryMB}MB / ${totalMemoryMB}MB", colors)
                    HealthRow("JVM Max Memory", "${maxMemoryMB}MB", colors)
                    HealthRow("Active Threads", "$threadCount", colors)
                    
                    val uptimeStr = if (uptimeSeconds > 60) "${uptimeSeconds / 60}m ${uptimeSeconds % 60}s" else "${uptimeSeconds}s"
                    HealthRow("Kernel Uptime", uptimeStr, colors)
                    
                    val osInfo = "${System.getProperty("os.name")} (${System.getProperty("os.arch")})"
                    HealthRow("Operating System", osInfo, colors)
                }

                Spacer(modifier = Modifier.height(32.dp))
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
private fun HealthRow(label: String, value: String, colors: BossColorScheme) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = colors.textSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = colors.textPrimary,
            fontFamily = FontFamily.Monospace
        )
    }
}
