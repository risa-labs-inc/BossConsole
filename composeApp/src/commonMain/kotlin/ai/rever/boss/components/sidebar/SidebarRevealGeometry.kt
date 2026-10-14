package ai.rever.boss.components.sidebar

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Full sidebar frame, including its existing 4dp side insets. */
internal fun sidebarFrameWidth(
    width: Dp,
    progress: Float = 1f,
): Dp = (width + 8.dp) * progress.coerceIn(0f, 1f)

/** Header and body share this width equation, including the existing 4dp side insets. */
internal fun sidebarBodyWidth(
    width: Dp,
    progress: Float,
): Dp = (sidebarFrameWidth(width, progress) - 8.dp).coerceIn(0.dp, width)
