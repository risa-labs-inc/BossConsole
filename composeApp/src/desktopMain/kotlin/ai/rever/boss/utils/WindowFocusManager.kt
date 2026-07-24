package ai.rever.boss.utils

import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WindowFocusManager - Handles multi-window focus tracking
 *
 * Tracks all application windows and their focus state to ensure
 * external events (deep links, file opens) are handled by the focused window only.
 */
actual object WindowFocusManager {
    private val windows = ConcurrentHashMap<String, Window>()
    private val windowListeners = mutableMapOf<String, WindowAdapter>()
    private var focusedWindowId: String? = null
    private var mainWindow: Window? = null  // Kept for backward compatibility

    // StateFlow to observe focus changes (for elegant focus restoration)
    private val _focusedWindowFlow = MutableStateFlow<String?>(null)
    actual val focusedWindowFlow: StateFlow<String?> = _focusedWindowFlow.asStateFlow()

    /**
     * Register an application window with focus tracking
     *
     * @param windowId Unique identifier for the window
     * @param window The AWT window instance
     */
    fun registerWindow(windowId: String, window: Window) {
        windows[windowId] = window

        // First window becomes the main window (backward compatibility)
        if (mainWindow == null) {
            mainWindow = window
            focusedWindowId = windowId
        }

        // Create focus listener to track window focus changes
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) {
                focusedWindowId = windowId
                _focusedWindowFlow.value = windowId  // Emit to Flow for observers
            }

            override fun windowLostFocus(e: WindowEvent?) {
                // Focus tracking handled by windowGainedFocus
            }
        }

        // Store listener reference for cleanup
        windowListeners[windowId] = listener

        // Add listener to window
        window.addWindowFocusListener(listener)
    }

    /**
     * Register the main application window (backward compatibility)
     *
     * @param window The AWT window instance
     */
    fun registerWindow(window: Window) {
        // Generate a default ID for backward compatibility
        val windowId = "window-${System.identityHashCode(window)}"
        registerWindow(windowId, window)
    }

    /**
     * Get a registered window by ID.
     *
     * @param windowId The window ID
     * @return The AWT Window, or null if not registered
     */
    fun getWindow(windowId: String): Window? = windows[windowId]

    /**
     * Unregister a window when it closes
     *
     * @param windowId The window ID to unregister
     */
    fun unregisterWindow(windowId: String) {
        // Remove the focus listener to prevent memory leak
        windowListeners.remove(windowId)?.let { listener ->
            windows[windowId]?.removeWindowFocusListener(listener)
        }

        windows.remove(windowId)
        if (focusedWindowId == windowId) {
            // If the focused window closed, clear focus (another window will gain focus via listener)
            focusedWindowId = null
            _focusedWindowFlow.value = null  // Keep flow in sync
        }
    }

    /**
     * Returns the AWT focus state of the registered window rather than the last
     * window observed by the process-wide focus listener.
     */
    actual fun isWindowFocused(windowId: String): Boolean {
        return windows[windowId]?.isFocused == true
    }

    /**
     * Best-effort window id for actions that need "the" active window but may run
     * before a real OS focus-gained event has fired for it — e.g. a deep link
     * dispatched by an MCP tool while the caller's own window (not BOSS) has OS
     * focus. Prefers [focusedWindowId] (set at registration and on every focus
     * gain) over [focusedWindowFlow] (only ever set inside the focus-gained
     * listener, so it can lag or stay null even once a window is plainly
     * available), falling back to any registered window. Returns null only if no
     * window is registered at all.
     */
    fun resolveActionableWindowId(): String? =
        focusedWindowId ?: focusedWindowFlow.value ?: windows.keys.firstOrNull()

    /**
     * Bring a specific window to front by its ID
     *
     * @param windowId The ID of the window to focus
     * @return true if the window was found and focused, false otherwise
     */
    actual fun focusWindow(windowId: String): Boolean {
        val window = windows[windowId]
        return if (window != null) {
            SwingUtilities.invokeLater {
                // Make window visible if minimized
                if (!window.isVisible) {
                    window.isVisible = true
                }

                // Bring to front
                window.toFront()

                // Request focus
                window.requestFocus()
            }
            true
        } else {
            false
        }
    }

    /**
     * Bring the first registered window to front (backward compatibility)
     */
    actual fun bringToFront() {
        mainWindow?.let { window ->
            SwingUtilities.invokeLater {
                // Make window visible if minimized
                if (!window.isVisible) {
                    window.isVisible = true
                }

                // Bring to front
                window.toFront()

                // Request focus
                window.requestFocus()
            }
        }
    }
}
