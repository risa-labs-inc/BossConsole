package ai.rever.boss.components.model

import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.util.concurrent.atomic.AtomicLong

/**
 * Information about a tab's bounds and its actual index in the tab list.
 * Used for proper reorder calculation when LazyRow virtualizes tabs.
 */
data class TabBoundInfo(
    val bounds: Rect,
    val actualIndex: Int,
)

/**
 * A panel's tab bar rectangle together with the axis its tabs run along.
 *
 * The axis travels WITH the rectangle rather than being re-derived from its shape, because a
 * single-tab horizontal bar in a narrow split is taller than it is wide and would be read as
 * vertical. Every consumer of a bar's geometry - reorder, edge scroll, the shifted left drop
 * zone - needs the axis, so pairing them is what keeps them from disagreeing.
 */
data class TabBarBoundInfo(
    val bounds: Rect,
    val vertical: Boolean,
    /**
     * The rectangle whose ENDS mean "scroll" - the whole scrolling list, which is not always
     * this bar's own rectangle.
     *
     * A window-level bar registers one slice of itself per pane, so a pane's [bounds] end where
     * the next pane's begin. Taking the edge-scroll trigger from those would auto-scroll the
     * list whenever a drag approached the rule between two panes, which is precisely where the
     * user is aiming when moving a tab from one pane to the other. Defaults to [bounds], which
     * is right whenever a bar is the whole of its own list.
     */
    val scrollBounds: Rect = bounds,
)

/**
 * Direction for auto-scroll during tab drag when ghost reaches edge.
 *
 * Named along the tab ORDER, not the screen: a top bar's BACKWARD is leftwards and a left
 * bar's is upwards. The axis-named members this replaces were a lie on one of the two
 * orientations, and the handler that acts on them scrolls a list by index either way.
 */
enum class ScrollDirection { BACKWARD, FORWARD }

/**
 * Information about a tab being dragged.
 */
data class DraggingTabInfo(
    val tabInfo: TabInfo,
    val sourcePanelId: String,
    val sourceIndex: Int,
    val title: String,
    val icon: TabIcon?,
)

/**
 * Represents the target location where a tab can be dropped.
 */
sealed class TabDropTarget {
    /**
     * Reorder within the same tab bar.
     */
    data class Reorder(
        val panelId: String,
        val targetIndex: Int,
    ) : TabDropTarget()

    /**
     * Create a new split panel.
     */
    data class SplitPanel(
        val panelId: String,
        val orientation: SplitOrientation,
    ) : TabDropTarget()

    /**
     * Move to an existing panel's tab bar.
     */
    data class ExistingPanel(
        val panelId: String,
    ) : TabDropTarget()
}

/**
 * Result of a tab drop operation.
 */
sealed class TabDropResult {
    data class Reorder(
        val panelId: String,
        val fromIndex: Int,
        val toIndex: Int,
    ) : TabDropResult()

    data class MoveToPanel(
        val tabInfo: TabInfo,
        val sourcePanelId: String,
        val sourceIndex: Int,
        val targetPanelId: String,
    ) : TabDropResult()

    data class CreateSplit(
        val tabInfo: TabInfo,
        val sourcePanelId: String,
        val sourceIndex: Int,
        val targetPanelId: String,
        val orientation: SplitOrientation,
    ) : TabDropResult()
}

/**
 * Drop zone positions for a panel (edges for split creation).
 */
data class PanelDropZones(
    val panelBounds: Rect,
    val leftZone: Rect,
    val rightZone: Rect,
    val topZone: Rect,
    val bottomZone: Rect,
    val centerZone: Rect,
) {
    companion object {
        /**
         * @param leadingInset width of a VERTICAL tab bar occupying this panel's leading edge,
         *   in pixels. Every zone is resolved inside the panel rectangle MINUS that strip,
         *   because the bar is not part of the panel's droppable content: `updateDropTarget`
         *   tests tab-bar bounds first, so a zone reaching under the bar can never be hit and
         *   would only mislead the highlight drawn from it. Zero for a top tab bar, which sits
         *   above the content and so takes nothing off the sides.
         *
         *   A bar so wide that nothing is left produces an empty rectangle, and every
         *   `contains` against it is false - the panel simply offers no split zones, which is
         *   the honest answer at that size.
         */
        fun fromBounds(
            bounds: Rect,
            edgeSize: Float = 60f,
            leadingInset: Float = 0f,
        ): PanelDropZones {
            val content =
                Rect(
                    left = minOf(bounds.left + leadingInset, bounds.right),
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                )
            val effectiveEdgeSize = minOf(edgeSize, content.width / 4, content.height / 4)

            return PanelDropZones(
                // The panel's WHOLE rectangle, inset included: this is the panel's identity for
                // anything measuring or highlighting it, not a droppable region of its own.
                panelBounds = bounds,
                leftZone =
                    Rect(
                        left = content.left,
                        top = content.top + effectiveEdgeSize,
                        right = content.left + effectiveEdgeSize,
                        bottom = content.bottom - effectiveEdgeSize,
                    ),
                rightZone =
                    Rect(
                        left = content.right - effectiveEdgeSize,
                        top = content.top + effectiveEdgeSize,
                        right = content.right,
                        bottom = content.bottom - effectiveEdgeSize,
                    ),
                topZone =
                    Rect(
                        left = content.left + effectiveEdgeSize,
                        top = content.top,
                        right = content.right - effectiveEdgeSize,
                        bottom = content.top + effectiveEdgeSize,
                    ),
                bottomZone =
                    Rect(
                        left = content.left + effectiveEdgeSize,
                        top = content.bottom - effectiveEdgeSize,
                        right = content.right - effectiveEdgeSize,
                        bottom = content.bottom,
                    ),
                centerZone =
                    Rect(
                        left = content.left + effectiveEdgeSize,
                        top = content.top + effectiveEdgeSize,
                        right = content.right - effectiveEdgeSize,
                        bottom = content.bottom - effectiveEdgeSize,
                    ),
            )
        }
    }
}

/**
 * Index a dragged tab would be INSERTED at, given where the pointer is over the tab bar.
 *
 * Pure, and therefore unit-tested (`ReorderIndexTest`) rather than only reachable through a
 * live drag - the same shape `computeTabWidthPx` has, and for the same reason: this is the one
 * piece of the drag whose correctness is arithmetic rather than layout.
 *
 * [vertical] picks the axis. A top bar orders its tabs left to right and compares x against
 * each tab's horizontal midpoint; a left bar orders them top to bottom and compares y against
 * the vertical one. Nothing else about the calculation differs.
 *
 * Returns [TabBoundInfo.actualIndex] rather than a position in [tabs]: a lazy list only
 * registers the tabs it has composed, so a scrolled bar's first REGISTERED tab may be the
 * list's fifth. Sorting by screen position and reading the index back off the tab that wins is
 * what survives that.
 *
 * Insertion index, so "past the last tab" is `actualIndex + 1` - one beyond the end, which
 * `endDrag` then adjusts down when the source came from earlier in the list. An empty bar
 * (nothing composed yet) is 0.
 */
internal fun reorderIndexFor(
    tabs: List<TabBoundInfo>,
    position: Offset,
    vertical: Boolean,
): Int {
    val ordered = tabs.sortedBy { if (vertical) it.bounds.top else it.bounds.left }
    val along = if (vertical) position.y else position.x
    val before =
        ordered.firstOrNull { info ->
            val midpoint =
                if (vertical) {
                    info.bounds.top + info.bounds.height / 2
                } else {
                    info.bounds.left + info.bounds.width / 2
                }
            along < midpoint
        }
    return before?.actualIndex ?: ordered.lastOrNull()?.let { it.actualIndex + 1 } ?: 0
}

/**
 * Which way to auto-scroll [bar] for a pointer at [position], or null for neither.
 *
 * Split out of `checkEdgeScroll` so the axis projection - the only part that differs between a
 * top strip and a left bar - reads as one idea instead of six paired ternaries inline. Along the
 * tab order the pointer's distance from each end decides; across it, leaving the bar cancels.
 */
private fun edgeScrollDirection(
    bar: TabBarBoundInfo,
    position: Offset,
    threshold: Float,
): ScrollDirection? {
    val b = bar.scrollBounds
    val along = if (bar.vertical) position.y else position.x
    val across = if (bar.vertical) position.x else position.y
    val acrossRange = if (bar.vertical) b.left..b.right else b.top..b.bottom
    val alongMin = if (bar.vertical) b.top else b.left
    val alongMax = if (bar.vertical) b.bottom else b.right

    if (across !in acrossRange) return null
    return when {
        along < alongMin + threshold -> ScrollDirection.BACKWARD
        along > alongMax - threshold -> ScrollDirection.FORWARD
        else -> null
    }
}

/**
 * Holds the state and logic for the tab drag-and-drop system.
 */
@Stable
class TabDraggableComponent {
    /**
     * The tab currently being dragged, or null if no drag is in progress.
     */
    var draggingTab by mutableStateOf<DraggingTabInfo?>(null)
        private set

    /**
     * Absolute position where the drag started.
     */
    var dragStartPosition by mutableStateOf<Offset?>(null)
        private set

    /**
     * Accumulated delta since the drag started.
     */
    var dragDelta by mutableStateOf(Offset.Zero)
        private set

    /**
     * The current drop target being hovered over, or null.
     */
    var dropTarget by mutableStateOf<TabDropTarget?>(null)
        private set

    /**
     * Track tab bounds for reorder detection within a tab bar.
     * Key: tabId (format: "panelId:tabId"), Value: bounds and actual index
     */
    val tabBounds = mutableStateMapOf<String, TabBoundInfo>()

    /**
     * Track tab bar bounds for each panel.
     * Key: panelId, Value: bounds of the tab bar area and the axis its tabs run along
     */
    val tabBarBounds = mutableStateMapOf<String, TabBarBoundInfo>()

    /**
     * Track panel drop zones for split creation.
     * Key: panelId, Value: drop zone bounds
     */
    val panelDropZones = mutableStateMapOf<String, PanelDropZones>()

    /**
     * Whether a drag operation is currently in progress.
     */
    val isDragging: Boolean
        get() = draggingTab != null

    /**
     * Timestamp of last drop target update for throttling.
     * Prevents excessive calculations during drag (every pixel movement).
     * Uses AtomicLong for thread safety even though primarily accessed from UI thread.
     */
    private val lastDropTargetUpdateTime = AtomicLong(0L)

    /**
     * Minimum interval between drop target updates in milliseconds.
     * ~60fps = 16ms between updates
     */
    private val dropTargetUpdateInterval = 16L

    /**
     * Distance from tab bar edge (in pixels) to trigger auto-scroll.
     * When drag position is within this threshold of either end of the bar along its tab
     * order (left/right for a top bar, top/bottom for a left one), auto-scroll is triggered.
     */
    private val edgeScrollThreshold = 60f

    /**
     * Edge scroll interval in milliseconds.
     * Prevents excessive scroll animations by throttling callback invocations.
     */
    private val edgeScrollInterval = 150L

    /**
     * Timestamp of last edge scroll callback invocation for throttling.
     * Prevents multiple simultaneous scroll animations.
     */
    private val lastEdgeScrollTime = AtomicLong(0L)

    /**
     * Map of edge scroll callbacks keyed by panelId.
     * Each panel registers its own callback to handle scroll in its tab bar.
     * This avoids the race condition where multiple panels would overwrite a single callback.
     */
    private val edgeScrollCallbacks = mutableStateMapOf<String, (ScrollDirection) -> Unit>()

    /**
     * Register an edge scroll callback for a specific panel.
     * Called when a panel's tab bar is composed.
     *
     * @param panelId The unique identifier for the panel
     * @param callback Callback invoked with scroll direction when drag reaches edge
     */
    fun registerEdgeScrollCallback(
        panelId: String,
        callback: (ScrollDirection) -> Unit,
    ) {
        edgeScrollCallbacks[panelId] = callback
    }

    /**
     * Unregister the edge scroll callback for a specific panel.
     * Called when a panel's tab bar is disposed.
     *
     * @param panelId The unique identifier for the panel
     */
    fun unregisterEdgeScrollCallback(panelId: String) {
        edgeScrollCallbacks.remove(panelId)
    }

    /**
     * Start dragging a tab.
     */
    fun startDragging(
        tabInfo: TabInfo,
        panelId: String,
        index: Int,
        startPosition: Offset,
    ) {
        if (draggingTab != null) return

        draggingTab =
            DraggingTabInfo(
                tabInfo = tabInfo,
                sourcePanelId = panelId,
                sourceIndex = index,
                title = tabInfo.title,
                icon = tabInfo.tabIcon,
            )
        dragStartPosition = startPosition
        dragDelta = Offset.Zero
        updateDropTarget()
    }

    /**
     * Update the drag delta during a drag gesture.
     * Throttled to ~60fps to avoid excessive drop target and edge scroll calculations.
     */
    fun updateDrag(delta: Offset) {
        if (draggingTab == null || dragStartPosition == null) return
        dragDelta += delta

        // Throttle drop target updates to avoid excessive calculations
        val now = System.currentTimeMillis()
        val lastUpdate = lastDropTargetUpdateTime.get()
        if (now - lastUpdate >= dropTargetUpdateInterval) {
            // Use compareAndSet to avoid race conditions
            if (lastDropTargetUpdateTime.compareAndSet(lastUpdate, now)) {
                updateDropTarget()
                checkEdgeScroll() // Check if we should trigger auto-scroll
            }
        }
    }

    /**
     * Calculate the current absolute position of the drag.
     */
    fun getCurrentPosition(): Offset? {
        val start = dragStartPosition ?: return null
        return start + dragDelta
    }

    /**
     * Update the drop target based on current position.
     */
    private fun updateDropTarget() {
        val currentPosition = getCurrentPosition() ?: return
        val dragging = draggingTab ?: return

        // First, check if we're over any tab bar (for reorder or move to panel).
        //
        // Deliberately BEFORE the panel drop zones below: a vertical bar occupies its panel's
        // leading edge, so without this precedence a drop there would be ambiguous. The zones
        // are resolved inside the panel minus that strip (see PanelDropZones.fromBounds), so
        // the two partitions of the panel do not overlap at all.
        for ((panelId, bar) in tabBarBounds) {
            if (bar.bounds.contains(currentPosition)) {
                // We're over a tab bar
                if (panelId == dragging.sourcePanelId) {
                    // Same panel - calculate reorder position
                    val reorderIndex = calculateReorderIndex(panelId, currentPosition)
                    dropTarget = TabDropTarget.Reorder(panelId, reorderIndex)
                } else {
                    // Different panel - move to that panel
                    dropTarget = TabDropTarget.ExistingPanel(panelId)
                }
                return
            }
        }

        // Check panel drop zones for split creation
        for ((panelId, zones) in panelDropZones) {
            when {
                zones.leftZone.contains(currentPosition) -> {
                    dropTarget = TabDropTarget.SplitPanel(panelId, SplitOrientation.VERTICAL)
                    return
                }

                zones.rightZone.contains(currentPosition) -> {
                    dropTarget = TabDropTarget.SplitPanel(panelId, SplitOrientation.VERTICAL)
                    return
                }

                zones.topZone.contains(currentPosition) -> {
                    dropTarget = TabDropTarget.SplitPanel(panelId, SplitOrientation.HORIZONTAL)
                    return
                }

                zones.bottomZone.contains(currentPosition) -> {
                    dropTarget = TabDropTarget.SplitPanel(panelId, SplitOrientation.HORIZONTAL)
                    return
                }

                zones.centerZone.contains(currentPosition) -> {
                    // Center means add to existing panel
                    if (panelId != dragging.sourcePanelId) {
                        dropTarget = TabDropTarget.ExistingPanel(panelId)
                        return
                    }
                }
            }
        }

        // No valid drop target
        dropTarget = null
    }

    /**
     * Calculate the index where a tab would be inserted during reorder.
     */
    private fun calculateReorderIndex(
        panelId: String,
        position: Offset,
    ): Int =
        reorderIndexFor(
            tabs = tabBounds.entries.filter { (tabId, _) -> tabId.startsWith("$panelId:") }.map { it.value },
            position = position,
            vertical = tabBarBounds[panelId]?.vertical == true,
        )

    /**
     * End the drag and return the result, or null if cancelled.
     */
    fun endDrag(): TabDropResult? {
        val dragging = draggingTab
        val target = dropTarget

        // Reset state
        draggingTab = null
        dragStartPosition = null
        dragDelta = Offset.Zero
        dropTarget = null

        if (dragging == null) return null

        return when (target) {
            is TabDropTarget.Reorder -> {
                val toIndex =
                    if (target.targetIndex > dragging.sourceIndex) {
                        target.targetIndex - 1
                    } else {
                        target.targetIndex
                    }
                if (toIndex != dragging.sourceIndex) {
                    TabDropResult.Reorder(
                        panelId = target.panelId,
                        fromIndex = dragging.sourceIndex,
                        toIndex = toIndex,
                    )
                } else {
                    null
                }
            }

            is TabDropTarget.ExistingPanel -> {
                if (target.panelId != dragging.sourcePanelId) {
                    TabDropResult.MoveToPanel(
                        tabInfo = dragging.tabInfo,
                        sourcePanelId = dragging.sourcePanelId,
                        sourceIndex = dragging.sourceIndex,
                        targetPanelId = target.panelId,
                    )
                } else {
                    null
                }
            }

            is TabDropTarget.SplitPanel -> {
                TabDropResult.CreateSplit(
                    tabInfo = dragging.tabInfo,
                    sourcePanelId = dragging.sourcePanelId,
                    sourceIndex = dragging.sourceIndex,
                    targetPanelId = target.panelId,
                    orientation = target.orientation,
                )
            }

            null -> {
                null
            }
        }
    }

    /**
     * Cancel the drag without performing any action.
     */
    fun cancelDrag() {
        draggingTab = null
        dragStartPosition = null
        dragDelta = Offset.Zero
        dropTarget = null
    }

    /**
     * Check if drag position is near tab bar edge and trigger auto-scroll.
     * Called from updateDrag() at throttled intervals (~60fps).
     * Additional throttling (150ms) is applied to the scroll callback itself
     * to prevent multiple simultaneous scroll animations.
     */
    private fun checkEdgeScroll() {
        val currentPosition = getCurrentPosition() ?: return
        val dragging = draggingTab ?: return
        val bar = tabBarBounds[dragging.sourcePanelId] ?: return

        val direction = edgeScrollDirection(bar, currentPosition, edgeScrollThreshold)

        // If at an edge, trigger scroll with throttling
        if (direction != null) {
            val now = System.currentTimeMillis()
            val lastScroll = lastEdgeScrollTime.get()
            if (now - lastScroll >= edgeScrollInterval) {
                if (lastEdgeScrollTime.compareAndSet(lastScroll, now)) {
                    edgeScrollCallbacks[dragging.sourcePanelId]?.invoke(direction)
                }
            }
        }
    }

    /**
     * Register tab bounds for a specific tab.
     * The tabId should be in the format "panelId:tabId" for proper grouping.
     * @param compositeTabId Format: "panelId:tabId"
     * @param bounds The tab's bounds in window coordinates
     * @param actualIndex The tab's actual index in the tab list (important for LazyRow)
     */
    fun registerTabBounds(
        compositeTabId: String,
        bounds: Rect,
        actualIndex: Int,
    ) {
        tabBounds[compositeTabId] = TabBoundInfo(bounds, actualIndex)
    }

    /**
     * Unregister tab bounds when a tab is removed.
     */
    fun unregisterTabBounds(compositeTabId: String) {
        tabBounds.remove(compositeTabId)
    }

    /**
     * Register tab bar bounds for a panel.
     *
     * @param vertical true when this panel's tabs run down a left bar rather than across a
     *   top strip. Supplied by the bar itself rather than inferred from [bounds]; see
     *   [TabBarBoundInfo].
     */
    fun registerTabBarBounds(
        panelId: String,
        bounds: Rect,
        vertical: Boolean,
        scrollBounds: Rect = bounds,
    ) {
        tabBarBounds[panelId] = TabBarBoundInfo(bounds, vertical, scrollBounds)
    }

    /**
     * Forget a panel's tab bar rectangle while keeping everything else about the panel.
     *
     * For a bar that stops showing a panel's tabs without the panel going anywhere: a
     * window-level bar collapsed to its rail, or a group scrolled out of the list entirely. A
     * rectangle left behind would keep claiming a piece of the screen for tabs that are not
     * drawn there any more, and [updateDropTarget] tests these before anything else.
     */
    fun unregisterTabBarBounds(panelId: String) {
        tabBarBounds.remove(panelId)
    }

    /**
     * Register panel drop zones for split creation.
     *
     * The left zone is pushed in by however much of a VERTICAL tab bar actually covers this
     * panel's leading edge. Without that it lands entirely underneath the bar, and since
     * [updateDropTarget] tests tab-bar bounds first, dropping a tab on a left-positioned
     * panel's leading edge could only ever mean "move into that panel" - left-split-by-drag
     * would be unreachable there.
     *
     * Measured as the OVERLAP of the bar over the panel rather than taken as the bar's width,
     * because a bar is not always inside the panel whose tabs it lists. A window-level bar
     * lists every panel's tabs and sits outside the split tree entirely, so it covers none of
     * them and every panel keeps its full left zone; taking the width there would shrink each
     * panel's leading edge by a bar that is nowhere near it. One rule, and it is the geometry
     * itself, so the two cases cannot disagree.
     *
     * Read from this panel's own registered bar rather than passed in, so the caller does not
     * have to know a rectangle the bar has already measured and reported. It is one frame
     * behind on the very first layout (zones registered before the bar reports), which costs
     * a mis-placed left zone until the next layout pass and nothing after that.
     */
    fun registerPanelDropZones(
        panelId: String,
        bounds: Rect,
    ) {
        val bar = tabBarBounds[panelId]
        val leadingInset =
            if (bar != null && bar.vertical) {
                (bar.bounds.right - bounds.left).coerceIn(0f, bounds.width)
            } else {
                0f
            }
        panelDropZones[panelId] = PanelDropZones.fromBounds(bounds, leadingInset = leadingInset)
    }

    /**
     * Clear all registered bounds (e.g., when layout changes significantly).
     */
    fun clearBounds() {
        tabBounds.clear()
        tabBarBounds.clear()
        panelDropZones.clear()
    }

    /**
     * Unregister all bounds for a specific panel.
     * Should be called when a panel is destroyed to prevent memory leaks.
     */
    fun unregisterPanel(panelId: String) {
        // Remove tab bar bounds for this panel
        tabBarBounds.remove(panelId)

        // Remove panel drop zones
        panelDropZones.remove(panelId)

        // Remove all tab bounds for this panel (format: "panelId:tabId")
        val tabsToRemove = tabBounds.keys.filter { it.startsWith("$panelId:") }
        tabsToRemove.forEach { tabBounds.remove(it) }
    }
}
