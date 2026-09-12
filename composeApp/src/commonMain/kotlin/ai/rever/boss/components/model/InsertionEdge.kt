package ai.rever.boss.components.model

/**
 * Where a tab bar draws the insertion line for the drop in flight.
 *
 * Pure, so the rule that matters - every slot of a list is drawn exactly once - is a unit test
 * (`InsertionEdgeTest`) rather than something only a live drag can show. The same shape
 * [reorderIndexFor] has, and for the same reason.
 */

/** Which edge of a tab's row an insertion line goes on. */
internal enum class InsertionEdge {
    /** The edge a tab's row shares with the slot BEFORE it: above a vertical row, left of a top one. */
    LEADING,

    /**
     * The far edge of the LAST row, which is the only slot no row is beneath.
     *
     * The one slot [LEADING] cannot express: slot k is drawn by row k, and slot `tabCount` has no
     * row k to draw it.
     */
    TRAILING,
}

/**
 * The slot of [panelId]'s own list that [dropTarget] names, or null when it names none.
 *
 * Both drop targets that land a tab in a bar answer here, which is the point: a reorder inside
 * one pane and a move into another are the same question to the row being drawn - "does the tab
 * come to rest in front of me" - and the bar had a condition for only the first, so a drop into
 * any other pane showed no line at all and appended.
 *
 * Null for a target naming a different pane, for a target that carries no position (see
 * [TabDropTarget.ExistingPanel.targetIndex]), for a split-zone or Favorites target, and for a bar
 * with no panel id - a bar built without one cannot tell whether a target is its own.
 */
internal fun paneInsertionIndexFor(
    dropTarget: TabDropTarget?,
    panelId: String?,
): Int? {
    if (panelId == null) return null
    return when (dropTarget) {
        is TabDropTarget.Reorder -> dropTarget.targetIndex.takeIf { dropTarget.panelId == panelId }
        is TabDropTarget.ExistingPanel -> dropTarget.targetIndex.takeIf { dropTarget.panelId == panelId }
        else -> null
    }
}

/**
 * The edge the row at [index] draws its insertion line on, or null for no line.
 *
 * **Every slot is drawn by the row BENEATH it**: slot k is the leading edge of row k. A boundary
 * two rows touch is therefore drawn once rather than by both of them, and the one slot with no row
 * beneath it - past the end of the list - is drawn on the trailing edge of the final row. That is
 * the whole rule, and `InsertionEdgeTest` pins it as "exactly once" over the whole list rather
 * than case by case, because a doubled boundary and a lost final slot both pass every individual
 * case.
 *
 * @param insertionIndex from [paneInsertionIndexFor]: the slot in THIS pane's list, or null.
 * @param index this row's index in the pane's tab list, not its position in a lazy list.
 * @param tabCount tabs the PANE holds. A collapsed group draws fewer rows than it has tabs, so it
 *   simply has no row to carry some of the slots; nothing is drawn twice either way.
 */
internal fun insertionEdgeFor(
    insertionIndex: Int?,
    index: Int,
    tabCount: Int,
): InsertionEdge? =
    when {
        insertionIndex == null -> null

        insertionIndex == index -> InsertionEdge.LEADING

        // Guarded on being the last row rather than on the count alone, so a stale index from a
        // list that shrank mid-drag draws nothing instead of a line on whichever row happens to
        // be there.
        insertionIndex == tabCount && index == tabCount - 1 -> InsertionEdge.TRAILING

        else -> null
    }
