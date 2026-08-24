package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.window_panel.SplitOrientation
import androidx.compose.runtime.Stable

/**
 * One pane's worth of tabs inside the window's single vertical bar.
 *
 * A group is a [TabBarState] plus what only the window bar can know about it: which pane it
 * belongs to, whether that pane is the one the user is working in, and where that pane sits on
 * screen so the group can say so.
 */
@Stable
class TabBarGroup
    // A bundle, not a call site: every field is passed by name and each is a distinct thing the
    // bar needs about this pane. Folding them into sub-holders to satisfy the count would add
    // indirection at every use without making anything clearer - same reasoning as TabBarState.
    @Suppress("LongParameterList")
    internal constructor(
        val panelId: String,
        val state: TabBarState,
        val isActive: Boolean,
        /** This pane's rectangle as a fraction of the split area, for the header glyph. */
        internal val glyph: PaneGlyph? = null,
        /**
         * What to call this pane: the name someone gave it, or where it sits.
         *
         * See [paneLabel] for the derived half. A name replaces it rather than joining it -
         * "Logs" is what the user chose to call that pane, and appending "(Bottom right)" would
         * put the thing they replaced back on screen.
         */
        internal val label: String = "",
        /** Make this pane the active one, for a click on its header. */
        internal val activate: () -> Unit = {},
        /** Open a tab in THIS pane, from the "+" on its header. */
        internal val newTab: () -> Unit = {},
        /**
         * Close this pane and everything in it, from the "x" on its header.
         *
         * Null when there is no pane to close - a window with one pane, where the split does not
         * exist to be undone. The header is not drawn there either, so this is belt and braces.
         */
        internal val close: (() -> Unit)? = null,
        /** Show every tab, rather than the current one plus a count. */
        internal val expanded: Boolean = true,
        /** Open this pane up, or close it again, from a click on its summary row. */
        internal val toggleExpanded: () -> Unit = {},
        /** Show this pane alone, filling the split area. From a double-click on the map. */
        internal val zoom: () -> Unit = {},
        /** Give this pane a name, or clear it with a blank string. */
        internal val rename: (String) -> Unit = {},
        /** Split this pane, side by side or stacked. */
        internal val split: (SplitOrientation) -> Unit = {},
        /**
         * The pointer reached one of this pane's chrome rows, making it the open one.
         *
         * Its header and its "n more tabs" row both, since both are places someone lands while
         * looking for what is in this pane. Its tab rows deliberately do not: those are click
         * targets for switching tab, and passing over one on the way somewhere else should not
         * re-arrange the bar.
         */
        internal val hoverGroup: () -> Unit = {},
    ) {
        /**
         * The tabs this pane holds but is not drawing a row for, with their model indices.
         *
         * What the summary row shows as favicons. Empty when the pane is open, since then every
         * tab already has a row of its own with its name on it.
         */
        internal val hiddenTabs: List<IndexedValue<ai.rever.boss.plugin.api.TabInfo>>
            get() =
                if (expanded) {
                    emptyList()
                } else {
                    state.tabs.withIndex().filter { it.index != state.activeIndex }
                }

        /**
         * Rows this group adds beyond its tabs and its own leading rows: the "n more" line.
         *
         * Absent when the group is open, and absent when collapsing would hide nothing - a pane
         * with one tab is already showing all of them, and "0 more" is a row that says nothing.
         */
        internal val summaryRows: Int
            get() = if (!expanded && state.tabs.size > state.renderedTabCount) 1 else 0
    }
