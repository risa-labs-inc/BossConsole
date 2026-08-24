package ai.rever.boss.components.window_panel.components.main_window_panels

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
        /** "Left", "Top", or "Pane 3" when no honest word fits. See [paneLabel]. */
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
    )
