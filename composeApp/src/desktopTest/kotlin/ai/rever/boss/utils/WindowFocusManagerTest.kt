package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowFocusManagerTest {
    @Test
    fun `native macOS fullscreen events map to authoritative state`() {
        assertEquals(true, nativeMacOSFullscreenStateForEvent("windowEnteringFullScreen"))
        assertEquals(true, nativeMacOSFullscreenStateForEvent("windowEnteredFullScreen"))
        assertNull(nativeMacOSFullscreenStateForEvent("windowExitingFullScreen"))
        assertEquals(false, nativeMacOSFullscreenStateForEvent("windowExitedFullScreen"))
        assertNull(nativeMacOSFullscreenStateForEvent("toString"))
        assertTrue(isNativeMacOSFullscreenExitStarting("windowExitingFullScreen"))
        assertFalse(isNativeMacOSFullscreenExitStarting("windowExitedFullScreen"))
    }

    @Test
    fun `native tracking is authoritative and does not leak across windows`() {
        assertTrue(
            hasFullscreenSignal(
                nativeStateAvailable = false,
                nativeFullscreen = false,
                composeFullscreen = true,
            ),
        )
        assertTrue(
            hasFullscreenSignal(
                nativeStateAvailable = true,
                nativeFullscreen = true,
                composeFullscreen = false,
            ),
        )
        assertFalse(
            hasFullscreenSignal(
                nativeStateAvailable = true,
                nativeFullscreen = false,
                composeFullscreen = true,
            ),
        )
        assertFalse(
            hasFullscreenSignal(
                nativeStateAvailable = false,
                nativeFullscreen = true,
                composeFullscreen = false,
            ),
        )
        assertFalse(
            hasFullscreenSignal(
                nativeStateAvailable = false,
                nativeFullscreen = false,
                composeFullscreen = false,
            ),
        )
    }

    @Test
    fun `fullscreen exit notifications require a real transition`() {
        assertFalse(shouldNotifyComposeFullscreenExit(wasComposeFullscreen = false, isNativeFullscreen = false))
        assertFalse(shouldNotifyComposeFullscreenExit(wasComposeFullscreen = true, isNativeFullscreen = true))
        assertTrue(shouldNotifyComposeFullscreenExit(wasComposeFullscreen = true, isNativeFullscreen = false))
        assertFalse(
            shouldNotifyNativeFullscreenExit(
                hadNativeState = false,
                wasNativeFullscreen = false,
                composeFullscreen = false,
            ),
        )
        assertTrue(
            shouldNotifyNativeFullscreenExit(
                hadNativeState = false,
                wasNativeFullscreen = false,
                composeFullscreen = true,
            ),
        )
        assertTrue(
            shouldNotifyNativeFullscreenExit(
                hadNativeState = true,
                wasNativeFullscreen = true,
                composeFullscreen = false,
            ),
        )
        assertFalse(
            shouldNotifyNativeFullscreenExit(
                hadNativeState = true,
                wasNativeFullscreen = false,
                composeFullscreen = true,
            ),
        )
        assertFalse(
            shouldNotifyNativeFullscreenExit(
                hadNativeState = true,
                wasNativeFullscreen = true,
                composeFullscreen = true,
                exitAlreadyNotified = true,
            ),
        )
    }

    @Test
    fun `fullscreen exit listener stops firing after removal`() {
        val notifier = FullscreenExitNotifier()
        val exitedWindows = mutableListOf<String>()
        val listener: (String) -> Unit = exitedWindows::add

        notifier.notifyExit("before-registration")
        notifier.add(listener)
        notifier.notifyExit("window-a")
        notifier.remove(listener)
        notifier.notifyExit("after-removal")

        assertEquals(listOf("window-a"), exitedWindows)
    }

    @Test
    fun `failing fullscreen exit listener does not skip remaining listeners`() {
        val notifier = FullscreenExitNotifier()
        val exitedWindows = mutableListOf<String>()

        notifier.add { error("listener failure") }
        notifier.add(exitedWindows::add)
        notifier.notifyExit("window-a")

        assertEquals(listOf("window-a"), exitedWindows)
    }

    @Test
    fun `registration snapshots an already focused window`() {
        val tracker = AwtWindowFocusTracker()

        tracker.snapshotRegistration("window-a", isFocused = false)
        assertFalse(tracker.isFocused("window-a"))

        tracker.snapshotRegistration("window-a", isFocused = true)
        assertTrue(tracker.isFocused("window-a"))
    }

    @Test
    fun `late loss from previous window does not clear newer focus gain`() {
        val tracker = AwtWindowFocusTracker()
        val windowAListener = tracker.createListener("window-a")
        val windowBListener = tracker.createListener("window-b")

        windowAListener.windowGainedFocus(null)
        windowBListener.windowGainedFocus(null)
        windowAListener.windowLostFocus(null)

        assertFalse(tracker.isFocused("window-a"))
        assertTrue(tracker.isFocused("window-b"))
    }

    @Test
    fun `losing the focused window clears the snapshot`() {
        val tracker = AwtWindowFocusTracker()
        val listener = tracker.createListener("window-a")

        listener.windowGainedFocus(null)
        listener.windowLostFocus(null)

        assertFalse(tracker.isFocused("window-a"))
    }

    @Test
    fun `actionable window resolution prefers the last focused window`() {
        assertEquals(
            "window-a",
            resolveActionableWindowIdFrom(
                lastFocusedWindowId = "window-a",
                focusFlowWindowId = "window-b",
                registeredWindowIds = listOf("window-c"),
            ),
        )
        assertEquals(
            "window-b",
            resolveActionableWindowIdFrom(
                lastFocusedWindowId = null,
                focusFlowWindowId = "window-b",
                registeredWindowIds = listOf("window-c"),
            ),
        )
    }

    @Test
    fun `a registered window stays actionable when the caller holds OS focus`() {
        // An MCP-driven or CLI deep link runs while the caller's own window has
        // OS focus, so no focus-gained event has fired for BOSS and the focus
        // flow is null. A registered window is still a usable target.
        assertEquals(
            "window-c",
            resolveActionableWindowIdFrom(
                lastFocusedWindowId = null,
                focusFlowWindowId = null,
                registeredWindowIds = listOf("window-c", "window-d"),
            ),
        )
    }

    @Test
    fun `no registered window resolves to null`() {
        assertNull(
            resolveActionableWindowIdFrom(
                lastFocusedWindowId = null,
                focusFlowWindowId = null,
                registeredWindowIds = emptyList(),
            ),
        )
    }

    @Test
    fun `unregister clears only the matching focused window`() {
        val tracker = AwtWindowFocusTracker()
        val listener = tracker.createListener("window-b")

        listener.windowGainedFocus(null)
        tracker.onUnregistered("window-a")
        assertTrue(tracker.isFocused("window-b"))

        tracker.onUnregistered("window-b")
        assertFalse(tracker.isFocused("window-b"))
    }

    @Test
    fun `an iconified window is recognised, a merely hidden one is not`() {
        // The bug was testing isVisible instead. A minimized window IS visible; it is iconified,
        // so the old guard never fired and toFront ran against a window still in the taskbar.
        assertTrue(isIconified(java.awt.Frame.ICONIFIED))
        assertTrue(isIconified(java.awt.Frame.ICONIFIED or java.awt.Frame.MAXIMIZED_BOTH))
        assertFalse(isIconified(java.awt.Frame.NORMAL))
        assertFalse(isIconified(java.awt.Frame.MAXIMIZED_BOTH))
    }

    @Test
    fun `restoring a minimized window keeps it maximized if it was`() {
        // Assigning Frame.NORMAL would clear every bit, so a window the user had maximized would
        // come back small. Only the ICONIFIED bit may be touched.
        val wasMaximized = java.awt.Frame.ICONIFIED or java.awt.Frame.MAXIMIZED_BOTH

        assertEquals(java.awt.Frame.MAXIMIZED_BOTH, deiconified(wasMaximized))
        assertEquals(java.awt.Frame.NORMAL, deiconified(java.awt.Frame.ICONIFIED))
    }

    @Test
    fun `restoring a window that is not minimized changes nothing`() {
        assertEquals(java.awt.Frame.NORMAL, deiconified(java.awt.Frame.NORMAL))
        assertEquals(java.awt.Frame.MAXIMIZED_BOTH, deiconified(java.awt.Frame.MAXIMIZED_BOTH))
    }
}
