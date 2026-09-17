package ai.rever.boss.tabfullscreen

import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullscreenBrowserWindowTest {
    private val screen = Rectangle(0, 0, 1728, 1117)

    @Test
    fun `matching screen bounds are fullscreen`() {
        assertTrue(fillsScreen(Rectangle(0, 0, 1728, 1117), screen))
    }

    @Test
    fun `native fullscreen bounds larger than the display are accepted`() {
        assertTrue(fillsScreen(Rectangle(0, 0, 1728, 1118), screen))
    }

    @Test
    fun `maximized window leaving menu bar space is not fullscreen`() {
        assertFalse(fillsScreen(Rectangle(0, 25, 1728, 1092), screen))
    }

    @Test
    fun `partial screen window is not fullscreen`() {
        assertFalse(fillsScreen(Rectangle(200, 100, 1200, 800), screen))
    }

    @Test
    fun `full-size window offset from the display is not fullscreen`() {
        assertFalse(fillsScreen(Rectangle(1, 0, 1728, 1117), screen))
    }

    @Test
    fun `compose fallback requires signal and fullscreen geometry`() {
        assertTrue(
            shouldUseComposeFullscreenOverlay(
                composeSignalActive = true,
                isShowing = true,
                isMaximized = false,
                windowBounds = screen,
                screenBounds = screen,
            ),
        )
        assertFalse(
            shouldUseComposeFullscreenOverlay(
                composeSignalActive = false,
                isShowing = true,
                isMaximized = false,
                windowBounds = screen,
                screenBounds = screen,
            ),
        )
        assertFalse(
            shouldUseComposeFullscreenOverlay(
                composeSignalActive = true,
                isShowing = true,
                isMaximized = true,
                windowBounds = screen,
                screenBounds = screen,
            ),
        )
        assertFalse(
            shouldUseComposeFullscreenOverlay(
                composeSignalActive = true,
                isShowing = true,
                isMaximized = false,
                windowBounds = Rectangle(200, 100, 1200, 800),
                screenBounds = screen,
            ),
        )
    }

    @Test
    fun `delayed fullscreen work is rejected after a newer lifecycle starts`() {
        assertTrue(
            isCurrentFullscreenLifecycle(
                expectedEpoch = 4,
                currentEpoch = 4,
                isInFullscreenMode = true,
            ),
        )
        assertFalse(
            isCurrentFullscreenLifecycle(
                expectedEpoch = 4,
                currentEpoch = 5,
                isInFullscreenMode = true,
            ),
        )
        assertFalse(
            isCurrentFullscreenLifecycle(
                expectedEpoch = 4,
                currentEpoch = 4,
                isInFullscreenMode = false,
            ),
        )
    }

    @Test
    fun `old cleanup cannot clear a newer fullscreen tab state`() {
        assertTrue(
            shouldRestoreFullscreenTabState(
                cleanupEpoch = 5,
                currentEpoch = 5,
                isInFullscreenMode = false,
            ),
        )
        assertFalse(
            shouldRestoreFullscreenTabState(
                cleanupEpoch = 5,
                currentEpoch = 6,
                isInFullscreenMode = true,
            ),
        )
    }

    @Test
    fun `fullscreen request decisions preserve branch ordering`() {
        assertEquals(
            FullscreenRequestDecision.IGNORE_DUPLICATE,
            fullscreenRequestDecision(
                frameActive = true,
                isInFullscreenMode = true,
                isSameBrowser = true,
                isBrowserClosed = true,
            ),
        )
        assertEquals(
            FullscreenRequestDecision.REJECT_CLOSED,
            fullscreenRequestDecision(
                frameActive = false,
                isInFullscreenMode = false,
                isSameBrowser = false,
                isBrowserClosed = true,
            ),
        )
        assertEquals(
            FullscreenRequestDecision.REJECT_COMPETING,
            fullscreenRequestDecision(
                frameActive = true,
                isInFullscreenMode = true,
                isSameBrowser = false,
                isBrowserClosed = false,
            ),
        )
        assertEquals(
            FullscreenRequestDecision.BEGIN,
            fullscreenRequestDecision(
                frameActive = false,
                isInFullscreenMode = false,
                isSameBrowser = false,
                isBrowserClosed = false,
            ),
        )
    }

    @Test
    fun `same browser re-entry resumes a session with an exit pending`() {
        assertTrue(
            FullscreenRequestDecision.shouldResumeAfterDuplicateRequest(
                decision = FullscreenRequestDecision.IGNORE_DUPLICATE,
                isExiting = true,
            ),
        )
        assertFalse(
            FullscreenRequestDecision.shouldResumeAfterDuplicateRequest(
                decision = FullscreenRequestDecision.IGNORE_DUPLICATE,
                isExiting = false,
            ),
        )
        assertFalse(
            FullscreenRequestDecision.shouldResumeAfterDuplicateRequest(
                decision = FullscreenRequestDecision.BEGIN,
                isExiting = true,
            ),
        )
    }

    @Test
    fun `exit callback gate suppresses duplicates until a new session begins`() {
        val gate = FullscreenExitCallbackGate<String>()
        var notifications = 0

        assertTrue(gate.notifyOnce("browser-a") { notifications++ })
        assertFalse(gate.notifyOnce("browser-a") { notifications++ })
        gate.begin("browser-a")
        assertTrue(gate.notifyOnce("browser-a") { notifications++ })

        assertEquals(2, notifications)
    }

    @Test
    fun `each competing fullscreen attempt receives one exit callback`() {
        val gate = FullscreenExitCallbackGate<String>()
        var notifications = 0

        repeat(2) {
            val decision =
                fullscreenRequestDecision(
                    frameActive = true,
                    isInFullscreenMode = true,
                    isSameBrowser = false,
                    isBrowserClosed = false,
                )
            assertEquals(FullscreenRequestDecision.REJECT_COMPETING, decision)
            gate.begin("browser-b")
            assertTrue(gate.notifyOnce("browser-b") { notifications++ })
            assertFalse(gate.notifyOnce("browser-b") { notifications++ })
        }

        assertEquals(2, notifications)
    }

    @Test
    fun `stale competing fallback cannot notify a newer attempt`() {
        val gate = FullscreenExitCallbackGate<String>()
        var notifications = 0

        val staleAttempt = gate.begin("browser-b")
        val currentAttempt = gate.begin("browser-b")

        assertFalse(gate.notifyOnce("browser-b", staleAttempt) { notifications++ })
        assertTrue(gate.notifyOnce("browser-b", currentAttempt) { notifications++ })
        assertEquals(1, notifications)
    }

    @Test
    fun `expected attempt notifies when its weak entry is missing`() {
        val gate = FullscreenExitCallbackGate<String>()
        var notifications = 0

        assertTrue(gate.notifyOnce("browser-b", 42L) { notifications++ })
        assertFalse(gate.notifyOnce("browser-b", 42L) { notifications++ })
        assertEquals(1, notifications)
    }

    @Test
    fun `video fullscreen confirmation distinguishes early exit from ignored toggle`() {
        assertEquals(
            VideoFullscreenConfirmationDecision.CONFIRMED,
            VideoFullscreenConfirmationDecision.decide(
                trackingAvailable = true,
                stateAvailable = true,
                isFullscreen = true,
                entryObserved = true,
                geometryFullscreen = false,
            ),
        )
        assertEquals(
            VideoFullscreenConfirmationDecision.EXITED_EARLY,
            VideoFullscreenConfirmationDecision.decide(
                trackingAvailable = true,
                stateAvailable = true,
                isFullscreen = false,
                entryObserved = true,
                geometryFullscreen = false,
            ),
        )
        assertEquals(
            VideoFullscreenConfirmationDecision.USE_OVERLAY,
            VideoFullscreenConfirmationDecision.decide(
                trackingAvailable = true,
                stateAvailable = false,
                isFullscreen = false,
                entryObserved = false,
                geometryFullscreen = false,
            ),
        )
    }

    @Test
    fun `tracked native fullscreen gets a longer confirmation window`() {
        assertEquals(1_500, VideoFullscreenConfirmationDecision.confirmationDelayMs(trackingAvailable = true))
        assertEquals(600, VideoFullscreenConfirmationDecision.confirmationDelayMs(trackingAvailable = false))
    }

    @Test
    fun `accepted focus request is retried until ownership is observed`() {
        assertEquals(
            BrowserFocusRecoveryDecision.RETRY,
            browserFocusRecoveryDecision(
                frameFocused = true,
                requestAccepted = true,
                focusWithinView = false,
                attempt = 0,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `focus recovery stays bounded while the frame remains focused`() {
        assertEquals(
            BrowserFocusRecoveryDecision.RETRY,
            browserFocusRecoveryDecision(
                frameFocused = true,
                requestAccepted = false,
                focusWithinView = false,
                attempt = 2,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `delivered focus wins regardless of request acceptance`() {
        assertEquals(
            BrowserFocusRecoveryDecision.COMPLETE,
            browserFocusRecoveryDecision(
                frameFocused = true,
                requestAccepted = false,
                focusWithinView = true,
                attempt = 3,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `rendering descendant counts as browser view focus`() {
        val browserView = JPanel()
        val renderingSurface = JButton()
        browserView.add(renderingSurface)

        assertTrue(isFocusWithin(browserView, renderingSurface))
        assertFalse(isFocusWithin(browserView, JPanel()))
    }

    @Test
    fun `focus recovery exhausts after bounded attempts`() {
        assertEquals(
            BrowserFocusRecoveryDecision.EXHAUSTED,
            browserFocusRecoveryDecision(
                frameFocused = true,
                requestAccepted = false,
                focusWithinView = false,
                attempt = 3,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `focus loss aborts pending recovery`() {
        assertEquals(
            BrowserFocusRecoveryDecision.ABORT,
            browserFocusRecoveryDecision(
                frameFocused = false,
                requestAccepted = true,
                focusWithinView = false,
                attempt = 0,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `stale lifecycle cannot resume focus recovery`() {
        assertFalse(
            isCurrentFullscreenLifecycle(
                expectedEpoch = 7,
                currentEpoch = 8,
                isInFullscreenMode = true,
            ),
        )
    }
}
