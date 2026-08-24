package ai.rever.boss.components.window_panel.components.main_window_panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Which pane in the window bar is showing all its tabs.
 *
 * A pane the user is not working in collapses to its current tab, so a four-way split costs a few
 * rows rather than twenty. This tracks the ones that are open ANYWAY - the active pane is always
 * open and is not asked about here, because that is a fact about the split rather than something
 * hovering or clicking decided.
 *
 * **Hover is sticky, and that is the whole design.** The obvious reading - expand while the
 * pointer is over the group - collapses the group the instant the pointer moves down onto the
 * rows it just revealed, because those rows are underneath where the pointer was going. So
 * hovering a header does not expand-while-hovered; it *chooses* which pane is the open one, and
 * that choice survives until another header is hovered or the pointer leaves the bar entirely.
 * Moving straight down from a header onto its tabs is then an ordinary thing to do.
 *
 * A pane can also be pinned open by clicking its summary row, which is what survives the pointer
 * leaving. Pinned and hovered are tracked separately so that leaving the bar cannot silently undo
 * something the user clicked.
 */
@Stable
class TabGroupExpansion internal constructor() {
    private var hovered by mutableStateOf<String?>(null)
    private val pinned = mutableStateListOf<String>()

    /** Whether this pane is showing every tab it has. */
    fun isExpanded(panelId: String): Boolean = panelId == hovered || panelId in pinned

    /** The pointer reached this pane's header: it becomes the open one. */
    fun hover(panelId: String) {
        hovered = panelId
    }

    /** Keep this pane open after the pointer leaves, or stop keeping it open. */
    fun togglePinned(panelId: String) {
        if (!pinned.remove(panelId)) pinned.add(panelId)
        // A pane pinned open while it was the hovered one would otherwise stay open on the next
        // bar exit through the hover path, making the unpin look like it did nothing.
        if (panelId == hovered) hovered = null
    }

    /**
     * The pointer left the bar, so nothing is hover-open any more.
     *
     * Only the hover choice is dropped. Pinned panes are a decision someone made with a click and
     * are not something moving the mouse away should undo.
     */
    fun barExited() {
        hovered = null
    }
}

@Composable
internal fun rememberTabGroupExpansion(): TabGroupExpansion = remember { TabGroupExpansion() }
