package ai.rever.boss.plugin.ui

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Data class for context menu items used across panel plugins.
 */
data class ContextMenuItemData(
    val label: String,
    val icon: ImageVector? = null,
    val isDivider: Boolean = false,
    val onClick: () -> Unit = {},
    val subMenu: List<ContextMenuItemData>? = null
)
