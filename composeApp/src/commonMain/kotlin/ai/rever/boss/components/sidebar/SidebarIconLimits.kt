package ai.rever.boss.components.sidebar

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.plugin.api.Panel
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout constants for the 40dp sidebar icon rails, used to translate a
 * bar's measured height into an icon-row budget. These mirror the sizes
 * hard-coded in `SidebarSlotContainer` / `SidebarCustomizeMenu` / `SDivider`;
 * if those change, adaptive mode degrades gracefully (a row too many just
 * clips, a row too few overflows one icon early) rather than breaking.
 */
object SidebarIconRail {
    /** One icon row: 32dp button + 8dp vertical padding. */
    val RowPitch = 40.dp

    /** `SidebarSlotContainer`'s own vertical padding (4dp top + 4dp bottom). */
    val SlotChrome = 8.dp

    /** Spacer kept in an empty slot so it stays a valid drop target. */
    val EmptySlotSpacer = 40.dp

    /** The three-dot customize button row (4dp padding + 32dp button). */
    val CustomizeButton = 40.dp

    /** `SDivider`: 1dp line + 8dp padding on either side. */
    val SectionDivider = 17.dp
}

/**
 * Fairly divide [totalRows] icon rows among slots holding [itemCounts]
 * items. Rows are dealt round-robin, one per slot per pass, so a crowded
 * slot can't starve its neighbours. Every slot with at least one item is
 * guaranteed one row even when [totalRows] is smaller — that row hosts
 * the "More" overflow button, which is the minimum needed to keep every
 * plugin reachable.
 */
fun allocateIconRows(
    itemCounts: List<Int>,
    totalRows: Int,
): List<Int> {
    val allocations = IntArray(itemCounts.size)
    var remaining = totalRows
    itemCounts.forEachIndexed { index, count ->
        if (count > 0) {
            allocations[index] = 1
            remaining--
        }
    }
    var progressed = true
    while (remaining > 0 && progressed) {
        progressed = false
        for (index in itemCounts.indices) {
            if (remaining <= 0) break
            if (allocations[index] < itemCounts[index]) {
                allocations[index]++
                remaining--
                progressed = true
            }
        }
    }
    return allocations.toList()
}

/**
 * Per-slot plugin-icon caps for [itemCounts] given [totalRows] of rail
 * space. Pure companion to [allocateIconRows]: a slot whose count fits
 * its allocation is capped at that allocation (equivalently, its count);
 * an overflowing slot yields its last allocated row to the "More"
 * button (cap = rows − 1, floored at 0), so a slot's rendered rows —
 * icons plus More — never exceed its allocation.
 */
fun iconCapsForRows(
    itemCounts: List<Int>,
    totalRows: Int,
): List<Int> {
    val rows = allocateIconRows(itemCounts, totalRows)
    return itemCounts.indices.map { index ->
        if (itemCounts[index] > rows[index]) {
            (rows[index] - 1).coerceAtLeast(0)
        } else {
            rows[index]
        }
    }
}

/**
 * Per-slot cap on *plugin icons* for one sidebar column. The "More"
 * overflow button is not counted by the cap: a slot whose item count
 * exceeds its cap renders cap icons plus the More button.
 *
 * FIXED mode applies [SidebarVisibilitySettings.fixedIconLimit] to every
 * slot as-is (the More row makes each overflowing slot one row taller —
 * acceptable, since the user asked for "N icons", not "N rows").
 *
 * ADAPTIVE mode converts [barHeight] minus [reservedHeight] (dividers,
 * customize button — whatever the caller renders besides the slots) into
 * a row budget, splits it across the slots via [allocateIconRows], and
 * spends the last row of any overflowing slot on its More button so the
 * column never exceeds the measured height.
 *
 * Reads `getItemsForSlot` (snapshot state) and must be called from a
 * composition observing [settings], so limits recompute on resize,
 * drag-and-drop, and hide-toggles.
 */
fun BossDraggableComponent.computeSlotIconLimits(
    slots: List<Panel>,
    settings: SidebarVisibilitySettings,
    barHeight: Dp,
    reservedHeight: Dp,
): Map<Panel, Int> {
    if (settings.iconLimitMode == SidebarIconLimitMode.FIXED) {
        val limit =
            settings.fixedIconLimit
                .coerceIn(SidebarVisibilitySettings.FIXED_ICON_LIMIT_RANGE)
        return slots.associateWith { limit }
    }

    val counts = slots.map { getItemsForSlot(it, settings.hiddenPanelIds).size }
    var available = barHeight - reservedHeight - SidebarIconRail.SlotChrome * slots.size
    counts.forEach { count ->
        if (count == 0) available -= SidebarIconRail.EmptySlotSpacer
    }
    val totalRows = (available / SidebarIconRail.RowPitch).toInt()
    val caps = iconCapsForRows(counts, totalRows)
    return slots.indices.associate { index -> slots[index] to caps[index] }
}
