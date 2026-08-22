package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.performance.HealthStatus
import ai.rever.boss.performance.PerformanceHealth
import ai.rever.boss.performance.PerformanceSnapshot
import ai.rever.boss.performance.PerformanceState
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.FormatUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Compact performance indicator for the status bar.
 *
 * Format: `"3.4GB 45%"` - what BOSS is holding across every process it owns, and process CPU.
 *
 * It used to read `"299MB/30GB 45%"`: JVM heap used over the heap ceiling. Both halves of that
 * were misleading. The numerator counted about an eighth of the memory BOSS was really holding,
 * omitting the Chromium tree and the plugin host JVMs where every memory incident this app has
 * had actually occurred. The denominator was not a BOSS number at all but 25% of the user's
 * installed RAM, so on a large machine the ratio sat near 1% and the colour could not change no
 * matter what went wrong: reaching amber at 75% of a 30 GB ceiling would have needed 22 GB of
 * live Kotlin objects.
 *
 * So the ratio is gone. A single absolute figure is the honest reading, because there is no
 * meaningful ceiling to divide by - the limit on what BOSS can hold is the machine's free
 * memory, which is shared with everything else running and is exactly what now drives the
 * colour. See `MemoryPressure` for the thresholds and why they are shared with the watchdog.
 *
 * Falls back to the old heap ratio when the footprint cannot be read, so a platform without a
 * reader shows less rather than nothing.
 */
@Composable
fun PerformanceIndicator(
    snapshot: PerformanceSnapshot?,
    health: PerformanceHealth,
    onClick: () -> Unit,
) {
    // Before the null-return below, deliberately. Whole-process sampling is gated on this being
    // registered, and the reading is part of the snapshot, so registering only once a snapshot
    // exists would be a standoff neither side could break.
    DisposableEffect(Unit) {
        PerformanceState.setIndicatorMounted(true)
        onDispose { PerformanceState.setIndicatorMounted(false) }
    }

    if (snapshot == null) return

    val color =
        when (health.overall) {
            HealthStatus.GOOD -> BossTheme.colors.ok
            HealthStatus.WARNING -> BossTheme.colors.warn
            HealthStatus.CRITICAL -> BossTheme.colors.alert
        }

    val memoryText =
        if (snapshot.memory.footprintKnown) {
            FormatUtils.formatMegabytes(snapshot.memory.footprintMB, compact = true)
        } else {
            val memoryUsed = FormatUtils.formatMegabytes(snapshot.memory.heapUsedMB, compact = true)
            val memoryMax = FormatUtils.formatMegabytes(snapshot.memory.heapMaxMB, compact = true)
            "$memoryUsed/$memoryMax"
        }
    val cpuText = "${snapshot.cpu.processLoadPercent.toInt()}%"

    BossActionButton(
        text = "$memoryText $cpuText",
        color = color,
        onClick = onClick,
    )
}
