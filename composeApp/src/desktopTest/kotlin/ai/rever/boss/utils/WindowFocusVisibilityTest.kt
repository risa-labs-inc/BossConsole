package ai.rever.boss.utils

import ai.rever.boss.testsupport.repoRoot
import ai.rever.boss.window.BossWindowState
import ai.rever.boss.window.WindowCloseDisposition
import ai.rever.boss.window.WindowManager
import ai.rever.boss.window.decideWindowCloseDisposition
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import java.awt.Frame
import java.awt.Window
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Executes the real queued focus paths with a peer-free AWT stand-in. Snapshot observation models
 * BossWindow's visible binding; these are not native focus, Dock, or live Compose UI tests.
 */
class WindowFocusVisibilityTest {
    @Test
    fun `focus reveal after final close allows the next close to hide again`() {
        assertHideRevealClose { state -> assertTrue(WindowFocusManager.focusWindow(state.id)) }
    }

    @Test
    fun `external URL bringToFront after final close allows the next close to hide again`() {
        assertHideRevealClose { WindowFocusManager.bringToFront() }
    }

    @Test
    fun `focus repairs managed visibility even when native window is already visible`() {
        withRegisteredWindow { state, native ->
            SwingUtilities.invokeAndWait {
                state.isVisible = false
                native.isVisible = true
            }
            assertTrue(WindowFocusManager.focusWindow(state.id))
            SwingUtilities.invokeAndWait {
                assertTrue(state.isVisible, "Native visibility must not guard managed state repair")
            }
        }
    }

    @Test
    fun `unmanaged native focus still reveals and deiconifies without reviving removed state`() {
        withRegisteredWindow { state, native ->
            SwingUtilities.invokeAndWait {
                state.isVisible = false
                WindowManager.closeWindow(state.id)
            }
            assertTrue(WindowFocusManager.focusWindow(state.id))
            SwingUtilities.invokeAndWait {
                assertTrue(native.isVisible)
                assertEquals(Frame.MAXIMIZED_BOTH, native.extendedState)
                assertFalse(state.isVisible)
            }
        }
    }

    @Test
    fun `external URL and final close remain wired to the tested focus and visibility paths`() {
        fun source(path: String) = File(repoRoot(), "composeApp/src/desktopMain/kotlin/ai/rever/boss/$path").readText()
        val urlHandler = source("services/URLHandlerService.kt")
        val handlerLines =
            urlHandler
                .substringAfter("private fun handleURLInternal(", "")
                .substringBefore("\n    private fun ")
                .lines()
                .map(String::trim)
        val exactRoute =
            urlHandler
                .substringAfter("private fun prepareCurrentExternalUrlRoute(", "")
                .substringBefore("\ninternal class ")
        if (exactRoute.isNotEmpty()) {
            // Exact-window routing must reach the tested focusWindow path, not merely mention it.
            assertTrue("val route = prepareCurrentExternalUrlRoute(url, title)" in handlerLines)
            assertTrue("route.emit()" in handlerLines)
            val routeLines = exactRoute.lines().map(String::trim)
            listOf(
                "prepareExternalUrlRoute(",
                "url = url,",
                "title = title,",
                "resolveWindowId = WindowFocusManager::resolveActionableWindowId,",
                "focusWindow = WindowFocusManager::focusWindow,",
                "openUrl = URLEventBus::openURL,",
            ).forEach { statement ->
                assertTrue(statement in routeLines, "Missing external URL route wiring: $statement")
            }
        } else {
            // The main-branch handler still uses the tested compatibility focus path.
            assertTrue("WindowFocusManager.bringToFront()" in handlerLines)
        }
        assertTrue(source("window/BossWindow.kt").contains("visible = windowState.isVisible"))
        val retainedClose =
            source("main.kt")
                .substringAfter("if (closeDisposition == WindowCloseDisposition.HIDE_AND_RETAIN) {")
                .substringBefore("return@closeWindow")
        assertTrue(retainedClose.contains("windowState.isVisible = false"))
    }

    private fun assertHideRevealClose(reveal: (BossWindowState) -> Unit) {
        withRegisteredWindow { state, native ->
            val visibilityChanges = mutableListOf<Boolean>()
            val observer = SnapshotStateObserver { it() }
            val scope = Any()
            lateinit var onVisibilityChanged: (Any) -> Unit

            fun bindVisibility() {
                observer.observeReads(scope, onVisibilityChanged) {
                    native.isVisible = state.isVisible
                    visibilityChanges += state.isVisible
                }
            }
            // SnapshotStateObserver keys observations by callback identity; reuse one callback.
            onVisibilityChanged = { bindVisibility() }

            fun closeFinalWindow() {
                assertEquals(
                    WindowCloseDisposition.HIDE_AND_RETAIN,
                    decideWindowCloseDisposition(
                        isMacOS = true,
                        closingWindowType = state.windowType,
                        hasOtherMainWindows = false,
                    ),
                )
                state.isVisible = false
                Snapshot.sendApplyNotifications()
            }
            SwingUtilities.invokeAndWait {
                observer.start()
                bindVisibility()
                closeFinalWindow()
                assertFalse(native.isVisible)
            }
            try {
                reveal(state)
                SwingUtilities.invokeAndWait {
                    // Drain the queued reveal, then deliver Compose state invalidations.
                    Snapshot.sendApplyNotifications()
                    assertTrue(native.isVisible)
                    assertEquals(Frame.MAXIMIZED_BOTH, native.extendedState)
                    assertEquals(1, native.frontRequests)
                    assertEquals(1, native.focusRequests)
                    closeFinalWindow()
                    assertFalse(native.isVisible, "Second close must invalidate visibility after focus reveal")
                    assertEquals(listOf(true, false, true, false), visibilityChanges)
                    assertSame(state, WindowManager.getWindow(state.id), "Retain the same live window state")
                }
            } finally {
                SwingUtilities.invokeAndWait {
                    observer.stop()
                    observer.clear()
                }
            }
        }
    }

    private fun withRegisteredWindow(test: (BossWindowState, PeerFreeFrame) -> Unit) {
        // Bypass registration's native fullscreen listener as well as AWT peer construction.
        // Restore both registry fields exactly: bringToFront's compatibility pointer is global.
        val windowsField = WindowFocusManager::class.java.getDeclaredField("windows").apply { isAccessible = true }
        val mainField = WindowFocusManager::class.java.getDeclaredField("mainWindow").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val windows = windowsField.get(WindowFocusManager) as MutableMap<String, Window>
        val previousMain = mainField.get(WindowFocusManager)
        val state = WindowManager.createNewWindow()
        val native = peerFreeFrame()
        SwingUtilities.invokeAndWait {
            windows[state.id] = native
            mainField.set(WindowFocusManager, native)
        }
        try {
            test(state, native)
        } finally {
            SwingUtilities.invokeAndWait {
                windows.remove(state.id)
                mainField.set(WindowFocusManager, previousMain)
                WindowManager.closeWindow(state.id)
            }
        }
    }

    private fun peerFreeFrame(): PeerFreeFrame {
        // Constructor-free allocation permits headless CI and cannot create a native peer.
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return (unsafe.allocateInstance(PeerFreeFrame::class.java) as PeerFreeFrame).apply {
            extendedState = Frame.ICONIFIED or Frame.MAXIMIZED_BOTH
        }
    }

    private class PeerFreeFrame : Frame() {
        private var shown = false
        private var placement = 0
        var frontRequests = 0
        var focusRequests = 0

        override fun isVisible(): Boolean = shown

        override fun setVisible(value: Boolean) {
            shown = value
        }

        override fun getExtendedState(): Int = placement

        override fun setExtendedState(value: Int) {
            placement = value
        }

        override fun toFront() {
            frontRequests++
        }

        override fun requestFocus() {
            focusRequests++
        }
    }
}
