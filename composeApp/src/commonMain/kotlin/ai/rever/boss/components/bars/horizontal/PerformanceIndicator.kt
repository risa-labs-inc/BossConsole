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
 * Format: `"2.1GB · 70/128GB 4%"` - what BOSS holds, then the machine's used and total memory,
 * then process CPU.
 *
 * It used to read `"299MB/30GB 45%"`: JVM heap used over the heap ceiling. Both halves of that
 * were misleading. The numerator counted about an eighth of the memory BOSS was really holding,
 * omitting the Chromium tree and the plugin host JVMs where every memory incident this app has
 * had actually occurred. The denominator was not a BOSS number at all but 25% of the user's
 * installed RAM, so on a large machine the ratio sat near 1% and the colour could not change no
 * matter what went wrong: reaching amber at 75% of a 30 GB ceiling would have needed 22 GB of
 * live Kotlin objects.
 *
 * The two figures answer different questions and both are worth a glance, which is why they are
 * shown side by side rather than combined. The first is what BOSS costs. The second is how much
 * room the machine has left, and it is the one driving the colour - see `MemoryPressure` for the
 * thresholds and why they are shared with the memory-pressure watchdog.
 *
 * Each half degrades independently: an unreadable footprint leaves the machine pair, an
 * unreadable machine leaves the footprint, and losing both falls back to the old heap ratio,
 * which is always available.
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

    val memory = snapshot.memory
    val memoryText =
        memoryIndicatorText(
            footprintMB = if (memory.footprintKnown) memory.footprintMB else null,
            systemUsedMB = memory.systemUsedBytes.takeIf { it > 0L }?.let { it / (1024f * 1024f) },
            systemTotalMB = memory.systemTotalBytes.takeIf { it > 0L }?.let { it / (1024f * 1024f) },
            heapUsedMB = memory.heapUsedMB,
            heapMaxMB = memory.heapMaxMB,
        )
    val cpuText = "${snapshot.cpu.processLoadPercent.toInt()}%"

    BossActionButton(
        text = "$memoryText $cpuText",
        color = color,
        onClick = onClick,
    )
}

/**
 * The memory half of the indicator label.
 *
 * Split out and internal so the fallbacks are testable without a Compose harness. There are four
 * of them and they are not decorative: a footprint reading fails on any platform without a
 * reader, and `SystemMemory` returns 0 for "unknown" on purpose rather than throwing, so both
 * inputs really can be absent at runtime and neither may be rendered as a zero.
 */
internal fun memoryIndicatorText(
    footprintMB: Float?,
    systemUsedMB: Float?,
    systemTotalMB: Float?,
    heapUsedMB: Float,
    heapMaxMB: Float,
): String {
    val footprint = footprintMB?.let { FormatUtils.formatMegabytes(it, compact = true) }
    val machine =
        if (systemUsedMB != null && systemTotalMB != null) {
            sharedUnitPair(systemUsedMB, systemTotalMB)
        } else {
            null
        }

    return when {
        footprint != null && machine != null -> {
            "$footprint · $machine"
        }

        footprint != null -> {
            footprint
        }

        machine != null -> {
            machine
        }

        else -> {
            FormatUtils.formatMegabytes(heapUsedMB, compact = true) +
                "/" +
                FormatUtils.formatMegabytes(heapMaxMB, compact = true)
        }
    }
}

/**
 * `70/128GB` - a used/total pair carrying the unit once.
 *
 * Both halves are scaled by the *total*, never each separately. Formatting them independently
 * would produce `900MB/128GB`, where the two numbers look comparable and are not, and the reader
 * has to notice a unit change mid-string to avoid reading it as 900 of 128. Scaling both by the
 * larger figure keeps the pair on one axis, which is the only reason a shared unit is legible.
 */
private fun sharedUnitPair(
    usedMB: Float,
    totalMB: Float,
): String {
    val gb = totalMB >= 1024f
    val divisor = if (gb) 1024f else 1f
    val unit = if (gb) "GB" else "MB"
    val used = usedMB / divisor
    val total = totalMB / divisor
    return "${trimNumber(used)}/${trimNumber(total)}$unit"
}

/** One decimal below 10, whole numbers above, so `3.2/8GB` and `70/128GB` both read cleanly. */
private fun trimNumber(value: Float): String = if (value >= 10f) value.toInt().toString() else "%.1f".format(value)
