package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.performance.HealthStatus
import ai.rever.boss.performance.PerformanceHealth
import ai.rever.boss.performance.PerformanceSnapshot
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.FormatUtils
import androidx.compose.runtime.Composable

/**
 * Compact performance indicator for the status bar.
 * Shows memory and CPU usage with color-coded health status.
 *
 * Format: "256MB/512MB 45%" or "1.5GB/4GB 45%" (memory usage / max, CPU %)
 */
@Composable
fun PerformanceIndicator(
    snapshot: PerformanceSnapshot?,
    health: PerformanceHealth,
    onClick: () -> Unit
) {
    if (snapshot == null) return

    val color = when (health.overall) {
        HealthStatus.GOOD -> BossTheme.colors.ok
        HealthStatus.WARNING -> BossTheme.colors.warn
        HealthStatus.CRITICAL -> BossTheme.colors.alert
    }

    val memoryUsed = FormatUtils.formatMegabytes(snapshot.memory.heapUsedMB, compact = true)
    val memoryMax = FormatUtils.formatMegabytes(snapshot.memory.heapMaxMB, compact = true)
    val memoryText = "$memoryUsed/$memoryMax"
    val cpuText = "${snapshot.cpu.processLoadPercent.toInt()}%"

    BossActionButton(
        text = "$memoryText $cpuText",
        color = color,
        onClick = onClick
    )
}
