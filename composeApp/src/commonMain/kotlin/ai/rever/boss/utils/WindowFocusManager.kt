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
     * Whether [windowId] currently identifies a live, registered window.
     *
     * Distinct from [isWindowFocused] - a window can be open without OS focus. For deciding
     * whether a routed action (e.g. a prompt meant for a specific window) should still wait
     * for that window, or fall back now that it has closed.
     *
     * @param windowId The window ID to check
     * @return true if a window with this ID is currently registered
     */
    fun isWindowOpen(windowId: String): Boolean

    /**
     * StateFlow that emits the ID of the currently focused window.
     * Emits null if no window is focused.
     *
     * Used by FocusRestorationManager for event-driven focus restoration.
     */
    val focusedWindowFlow: StateFlow<String?>

    /**
     * Best-effort window id for actions that need "the" active window but may run before a
     * real OS focus-gained event has fired for it - e.g. a deep link dispatched by an MCP tool
     * while the caller's own window (not BOSS) has OS focus. Returns null only if no window is
     * registered at all.
     *
     * Already the idiom this codebase uses for routing deep links, CLI commands and remote UI
     * placement to the right window; promoted onto this contract so commonMain code that wants
     * the same "which window" answer for the same reason does not need a desktop-only reference.
     */
    fun resolveActionableWindowId(): String?
}
