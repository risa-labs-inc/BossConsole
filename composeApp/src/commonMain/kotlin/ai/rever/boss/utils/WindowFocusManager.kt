package ai.rever.boss.utils

import kotlinx.coroutines.flow.StateFlow

/**
 * WindowFocusManager - Platform-specific window focus management
 *
 * Tracks which application window currently has focus to ensure
 * external events (deep links, file opens) are handled by the correct window.
 */
expect object WindowFocusManager {
    /**
     * Bring the application window to front and request focus
     */
    fun bringToFront()

    /**
     * Bring a specific window to front by its ID
     *
     * @param windowId The ID of the window to focus
     * @return true if the window was found and focused, false otherwise
     */
    fun focusWindow(windowId: String): Boolean

    /**
     * Check if a specific window is currently focused
     *
     * @param windowId The window ID to check
     * @return true if the window is focused, false otherwise
     */
    fun isWindowFocused(windowId: String): Boolean

    /**
     * StateFlow that emits the ID of the currently focused window.
     * Emits null if no window is focused.
     *
     * Used by FocusRestorationManager for event-driven focus restoration.
     */
    val focusedWindowFlow: StateFlow<String?>
}
