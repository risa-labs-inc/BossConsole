package ai.rever.boss.components.window_panel.components.main_window_panels

/**
 * Pinned tabs are the first `pinnedCount` tabs of a panel, always.
 *
 * That invariant is the whole design, and it is worth stating why it was chosen over the obvious
 * alternative of a `Set<String>` of pinned ids.
 *
 * The sidebar draws pinned tabs in their own section above a separator. If pinned-ness were a set,
 * the drawn order would differ from the model order, and every index in the system would need
 * translating: the reorder arithmetic, the drag bounds registration keyed by `actualIndex`, the
 * MRU cycle, `closeTabsToRight`, and the positional tab shortcuts. Keeping pinned tabs at the
 * front instead means the drawn order IS the model order, the sidebar only has to know where to
 * put one line, and none of that translation exists to get wrong.
 *
 * It also collapses persistence to a single integer per panel rather than a flag per tab, which is
 * what let restore stay one call per panel instead of threading a flag through six `addTab` sites.
 *
 * The cost is that pinning moves a tab, which is visible - but that is exactly what pinning does
 * in Arc too, so it reads as the feature rather than as a side effect.
 */

/**
 * The pinned count after a tab moves from [fromIndex] to [toIndex], where [toIndex] is the
 * FINAL index of the tab in the new list (what `endDrag` already computes).
 *
 * Pinned-ness follows position, so dragging a tab across the separator changes it - up into the
 * pinned block pins it, down out of it unpins it. That is the Arc behaviour, and here it is not
 * even a special case: it falls out of the invariant.
 *
 * Both directions reduce to the same test, `toIndex < pinnedCount`, which is worth checking rather
 * than trusting. Moving a PINNED tab: removing it leaves the block `0..pinnedCount-2`, and landing
 * at `pinnedCount-1` puts it immediately after that block, which under the invariant is still
 * pinned (count unchanged); landing at `pinnedCount` puts it after a block that is now one shorter
 * (count-1). Moving an UNPINNED tab: the block is untouched by the removal, so landing below
 * `pinnedCount` pushes into it (count+1) and landing at `pinnedCount` lands just past it
 * (unchanged).
 */
internal fun pinnedCountAfterMove(
    pinnedCount: Int,
    fromIndex: Int,
    toIndex: Int,
): Int {
    val wasPinned = fromIndex < pinnedCount
    val landsPinned = toIndex < pinnedCount
    return when {
        wasPinned && !landsPinned -> pinnedCount - 1
        !wasPinned && landsPinned -> pinnedCount + 1
        else -> pinnedCount
    }
}

/**
 * The pinned count after the tab at [removedIndex] is closed.
 *
 * Every close path in `BossTabsComponent` funnels through `removeTab`, including
 * `closeOtherTabs`, `closeTabsToRight`, `closeTabsToLeft` and `clearAllTabs`, so maintaining the
 * count here covers all of them and none of those has to know pinning exists.
 */
internal fun pinnedCountAfterRemove(
    pinnedCount: Int,
    removedIndex: Int,
): Int = if (removedIndex < pinnedCount) pinnedCount - 1 else pinnedCount

/**
 * [pinnedCount] clamped to a list of [tabCount] tabs.
 *
 * Restore reads this off persisted state that a previous version wrote, and a panel whose tabs
 * partly failed to restore (an unknown tab type, a `PanelHostTabInfo` that is deliberately never
 * persisted) can legitimately come back shorter than the count that was saved with it. Clamping
 * is the difference between "the last pinned tab quietly becomes unpinned" and a separator drawn
 * past the end of the list.
 */
internal fun clampPinnedCount(
    pinnedCount: Int,
    tabCount: Int,
): Int = pinnedCount.coerceIn(0, tabCount)
