package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.clampBarWidth
import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.WindowAppearanceSettings

/** BossTerm collapses a resized sidebar below 200 dp; saved legacy widths remain valid. */
internal const val SIDEBAR_RESIZE_COLLAPSE_WIDTH = 200f

/** BossTerm starts from an expanded width, including when restoring legacy narrow settings. */
internal fun sidebarResizeStartWidth(width: Float): Float =
    width.coerceIn(SIDEBAR_RESIZE_COLLAPSE_WIDTH, TabBarVerticalWidthRange.endInclusive)

/** Allow a narrow preview, then decide whether to collapse on release, like BossTerm. */
internal fun sidebarResizePreview(width: Float): Float = width.coerceIn(44f, TabBarVerticalWidthRange.endInclusive)

/** Collapse below the minimum, preserving the width restored by the sidebar toggle. */
internal fun sidebarResizeResult(
    settings: WindowAppearanceSettings,
    requestedWidth: Float,
): WindowAppearanceSettings =
    if (requestedWidth < SIDEBAR_RESIZE_COLLAPSE_WIDTH) {
        settings.copy(tabBarCollapsed = true)
    } else {
        settings.copy(tabBarVerticalWidth = clampBarWidth(requestedWidth), tabBarCollapsed = false)
    }
