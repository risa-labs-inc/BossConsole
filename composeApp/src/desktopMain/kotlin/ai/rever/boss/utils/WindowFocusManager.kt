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
 * Captures AWT focus lifecycle events on the EDT and exposes a volatile
 * snapshot that JxBrowser callback threads can safely read.
 */
internal class AwtWindowFocusTracker {
    @Volatile
    private var focusedWindowId: String? = null

    fun snapshotRegistration(windowId: String, isFocused: Boolean) {
        if (isFocused) {
            focusedWindowId = windowId
        }
    }

    fun createListener(
        windowId: String,
        onFocusGained: () -> Unit = {}
    ): WindowAdapter = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent?) {
            focusedWindowId = windowId
            onFocusGained()
        }

        override fun windowLostFocus(e: WindowEvent?) {
            if (focusedWindowId == windowId) {
                focusedWindowId = null
            }
        }
    }

    fun onUnregistered(windowId: String) {
        if (focusedWindowId == windowId) {
            focusedWindowId = null
        }
    }

    fun isFocused(windowId: String): Boolean = focusedWindowId == windowId
}

/**
 * WindowFocusManager - Handles multi-window focus tracking
 *
 * Tracks all application windows and their focus state to ensure
 * external events (deep links, file opens) are handled by the focused window only.
 */
actual object WindowFocusManager {
    private val windows = ConcurrentHashMap<String, Window>()
    // Listener installation and removal are EDT-confined.
    private val windowListeners = mutableMapOf<String, WindowAdapter>()
    private val awtFocusTracker = AwtWindowFocusTracker()
    private var focusedWindowId: String? = null
    private var mainWindow: Window? = null  // Kept for backward compatibility

    // StateFlow to observe focus changes (for elegant focus restoration)
    private val _focusedWindowFlow = MutableStateFlow<String?>(null)
    actual val focusedWindowFlow: StateFlow<String?> = _focusedWindowFlow.asStateFlow()

    /**
     * Register an application window with focus tracking.
     *
     * @param windowId Unique identifier for the window
     * @param window The AWT window instance
     */
    fun registerWindow(windowId: String, window: Window) {
        windows[windowId] = window

        // First window becomes the main window (backward compatibility).
        if (mainWindow == null) {
            mainWindow = window
            focusedWindowId = windowId
        }

        // Registration runs on the EDT. Snapshot an already-focused window in
        // case its focus-gained event happened before the listener was attached.
        awtFocusTracker.snapshotRegistration(windowId, window.isFocused)

        val listener = awtFocusTracker.createListener(windowId) {
            focusedWindowId = windowId
            _focusedWindowFlow.value = windowId
        }

        windowListeners[windowId] = listener
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
     * Unregister a window when it closes.
     *
     * @param windowId The window ID to unregister
     */
    fun unregisterWindow(windowId: String) {
        windowListeners.remove(windowId)?.let { listener ->
            windows[windowId]?.removeWindowFocusListener(listener)
        }

        windows.remove(windowId)
        awtFocusTracker.onUnregistered(windowId)
        if (focusedWindowId == windowId) {
            // Preserve the existing last-focused flow contract for external
            // actions; another window will publish itself when it gains focus.
            focusedWindowId = null
            _focusedWindowFlow.value = null
        }
    }

    /**
     * Returns the current AWT focus snapshot maintained by EDT focus events.
     * The volatile snapshot is safe to read from JxBrowser callback threads.
     */
    actual fun isWindowFocused(windowId: String): Boolean =
        awtFocusTracker.isFocused(windowId)

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
