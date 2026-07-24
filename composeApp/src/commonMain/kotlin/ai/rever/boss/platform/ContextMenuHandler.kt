package ai.rever.boss.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * Platform-specific implementation for handling context menu activation.
 * Each platform (desktop, mobile) will provide its own implementation of this interface.
 */
expect class ContextMenuHandler() {
    /**
     * Apply context menu behavior to a Modifier for a specific platform.
     *
     * @param showMenu Current state of menu visibility
     * @param setShowMenu Function to update menu visibility
     * @param setMenuPosition Function to update menu position
     * @return Modified Modifier with platform-specific context menu behavior
     */
    fun Modifier.applyContextMenuBehavior(
        showMenu: Boolean,
        setShowMenu: (Boolean) -> Unit,
        setMenuPosition: (IntOffset) -> Unit,
    ): Modifier
}
