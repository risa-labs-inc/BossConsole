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
class TabBarGroup internal constructor(
    val panelId: String,
    val state: TabBarState,
    val isActive: Boolean,
    /** This pane's rectangle as a fraction of the split area, for the header glyph. */
    internal val glyph: PaneGlyph? = null,
    /** "Left", "Top", or "Pane 3" when no honest word fits. See [paneLabel]. */
    internal val label: String = "",
    /** Make this pane the active one, for a click on its header. */
    internal val activate: () -> Unit = {},
)
