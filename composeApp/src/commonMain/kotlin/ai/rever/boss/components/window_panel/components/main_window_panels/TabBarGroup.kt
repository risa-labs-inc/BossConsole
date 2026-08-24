package ai.rever.boss.components.window_panel.components.main_window_panels

import androidx.compose.runtime.Stable

/**
 * One pane's worth of tabs inside the window's single vertical bar.
 *
 * A group is a [TabBarState] plus the two things only the window bar can know about it: which
 * pane it belongs to, and whether that pane is the one the user is working in.
 */
@Stable
class TabBarGroup internal constructor(
    val panelId: String,
    val state: TabBarState,
    val isActive: Boolean,
)
