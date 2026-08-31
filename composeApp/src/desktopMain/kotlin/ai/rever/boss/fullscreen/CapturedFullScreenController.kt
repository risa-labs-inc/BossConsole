/*
 * detekt: this file catches Throwable and returns early a lot, both deliberately.
 *
 * Every call here crosses into a native library through JNA, where the failure modes are
 * UnsatisfiedLinkError, NoClassDefFoundError and Error subtypes as well as Exception - so catching
 * anything narrower would let exactly the interesting failures escape into a UI event handler. And
 * the contract of InputCapture is that a grab either happens or is reported as not having happened;
 * a throw crossing this boundary would leave a session half-entered, which is the one outcome the
 * whole design exists to prevent. The early returns are that contract: each one is a distinct
 * reason the platform cannot do this, answered where it is discovered.
 */
@file:Suppress("TooGenericExceptionCaught")

package ai.rever.boss.fullscreen

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean

private val logger = BossLogger.forComponent("CapturedFullScreen")

/**
 * Owns the one captured full-screen session: takes the pointer and the keyboard, and - the part
 * that actually matters - gives them back on every path out.
 *
 * **Geometry is not here.** Covering the display is a Compose `WindowState` change and belongs to
 * `BossWindow`, which is the only place that state is in scope. This object does the input grabs
 * and their lifetime, so the two halves can be reasoned about, and tested, apart.
 *
 * ## Giving the input back is the whole job
 *
 * Everything else in the app fails by not doing something. This fails by leaving a user unable to
 * reach their other applications, which they cannot report a bug about without first getting out.
 * So release is idempotent, it runs from every exit there is, and any failure to grab at all leaves
 * the session un-entered rather than half-entered:
 *
 * - the user's own two shortcuts, and the hardwired Escape hold
 * - the window losing focus to something outside BOSS
 * - the window being disposed
 * - a JVM shutdown hook, for a quit or a crash that still unwinds
 * - a native call that returned false or threw
 */
object CapturedFullScreenController {
    private var capture: InputCapture = NoopInputCapture
    private var attachedWindow: Window? = null
    private var focusGuard: WindowAdapter? = null
    private val shutdownHookInstalled = AtomicBoolean(false)

    /**
     * The bounds the window should cover, i.e. the full display it is currently on.
     *
     * The display is chosen from the window rather than from the primary screen, so entering on a
     * second monitor captures that monitor. Insets are deliberately NOT subtracted: the menu bar and
     * the Dock are hidden by the grab, so the window is meant to cover where they were.
     */
    fun displayBoundsOf(window: Window): Rectangle = window.graphicsConfiguration?.bounds ?: Rectangle(0, 0, 0, 0)

    /**
     * Take the input for [windowId].
     *
     * @return the session as it actually is, including anything this platform could not do. The
     *   caller publishes it and shows the limitations; it is never an exception, because a partial
     *   grab is an ordinary outcome on at least one supported platform.
     */
    fun enter(
        windowId: String,
        window: Window,
    ): CapturedFullScreen {
        if (CapturedFullScreenState.current.value.active) {
            // Two captured windows is not a state that can exist - the grab is display-wide and
            // keyboard-wide - so a second request means something already went wrong. Take the
            // existing session down rather than stacking a grab that could not be released in order.
            logger.warn(LogCategory.UI, "Capture requested while one is active, releasing the first")
            exit()
        }

        capture = InputCapture.forCurrentPlatform()
        installShutdownHook()

        val limitations = mutableSetOf<CaptureLimitation>()
        val bounds = displayBoundsOf(window)

        val pointerConfined =
            runCatching { capture.confinePointer(window, bounds) }
                .onFailure { logger.warn(LogCategory.UI, "Pointer confinement threw", error = it) }
                .getOrDefault(false)
        if (!pointerConfined) {
            limitations +=
                if (isWaylandSession()) {
                    CaptureLimitation.WAYLAND_NO_GRAB
                } else {
                    CaptureLimitation.POINTER_NOT_CONFINED
                }
        }

        val keyboardGrabbed =
            runCatching { capture.grabKeyboard(window) }
                .onFailure { logger.warn(LogCategory.UI, "Keyboard grab threw", error = it) }
                .getOrDefault(false)
        if (!keyboardGrabbed) limitations += CaptureLimitation.KEYBOARD_NOT_GRABBED

        attachFocusGuard(window)

        val session =
            CapturedFullScreen(
                windowId = windowId,
                pointerConfined = pointerConfined,
                keyboardGrabbed = keyboardGrabbed,
                limitations = limitations,
            )
        CapturedFullScreenState.set(session)
        logger.info(
            LogCategory.UI,
            "Captured full screen entered",
            mapOf(
                "windowId" to windowId,
                "pointerConfined" to pointerConfined,
                "keyboardGrabbed" to keyboardGrabbed,
                "limitations" to limitations.joinToString(",").ifEmpty { "none" },
            ),
        )
        return session
    }

    /**
     * Release the pointer but keep the session and the keyboard grab.
     *
     * This is `view.pointer_release`, and it is what makes a captured window usable on a multi
     * monitor desk: reach the other screen, come back, and the mode is still on. Re-confining is
     * [reconfinePointer], driven by a click back inside the window.
     */
    fun releasePointer() {
        if (!CapturedFullScreenState.current.value.active) return
        runCatching { capture.releasePointer() }
            .onFailure { logger.warn(LogCategory.UI, "Pointer release threw", error = it) }
        CapturedFullScreenState.setPointerConfined(false)
    }

    /** Confine the pointer again after [releasePointer], if a session is still running. */
    fun reconfinePointer(window: Window) {
        val session = CapturedFullScreenState.current.value
        if (!session.active || session.pointerConfined) return
        val ok =
            runCatching { capture.confinePointer(window, displayBoundsOf(window)) }
                .onFailure { logger.warn(LogCategory.UI, "Pointer re-confine threw", error = it) }
                .getOrDefault(false)
        CapturedFullScreenState.setPointerConfined(ok)
    }

    /**
     * End the session and give everything back.
     *
     * Idempotent and safe to call when nothing is captured, because several teardown paths can fire
     * for one exit and none of them can be sure it is the first.
     */
    fun exit() {
        detachFocusGuard()
        runCatching { capture.releasePointer() }
            .onFailure { logger.warn(LogCategory.UI, "Pointer release threw on exit", error = it) }
        runCatching { capture.releaseKeyboard() }
            .onFailure { logger.warn(LogCategory.UI, "Keyboard release threw on exit", error = it) }
        capture = NoopInputCapture
        if (CapturedFullScreenState.current.value.active) {
            logger.info(LogCategory.UI, "Captured full screen exited")
        }
        CapturedFullScreenState.clear()
    }

    /** Exit only if [windowId] is the window holding the session. For per-window teardown. */
    fun exitIfCapturing(windowId: String) {
        if (CapturedFullScreenState.current.value.capturing(windowId)) exit()
    }

    /**
     * Drop the session when focus leaves BOSS entirely.
     *
     * **Keyed on the opposite window, not on the fact of losing focus.** Every BOSS dialog, popup
     * and secondary window takes focus from the captured one, and treating that as leaving would
     * end the mode the first time anything opened. `WindowEvent.oppositeWindow` is non-null exactly
     * when focus went to another window of this application, which is the case to ignore.
     */
    private fun attachFocusGuard(window: Window) {
        detachFocusGuard()
        val guard =
            object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    if (e.oppositeWindow == null) {
                        logger.info(LogCategory.UI, "Focus left BOSS, releasing capture")
                        exit()
                    }
                }

                override fun windowClosed(e: WindowEvent) = exit()
            }
        window.addWindowFocusListener(guard)
        window.addWindowListener(guard)
        attachedWindow = window
        focusGuard = guard
    }

    private fun detachFocusGuard() {
        val window = attachedWindow
        val guard = focusGuard
        if (window != null && guard != null) {
            window.removeWindowFocusListener(guard)
            window.removeWindowListener(guard)
        }
        attachedWindow = null
        focusGuard = null
    }

    /**
     * Last resort, for a quit or a crash that still unwinds.
     *
     * Registered on first use rather than at startup, so a session that is never entered adds no
     * hook - and, more importantly, so nothing here touches Objective-C before AWT is up, which
     * `MacInputCapture` documents as a measured deadlock.
     */
    private fun installShutdownHook() {
        if (!shutdownHookInstalled.compareAndSet(false, true)) return
        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    runCatching { capture.releasePointer() }
                    runCatching { capture.releaseKeyboard() }
                }, "boss-capture-release"),
            )
        }.onFailure {
            shutdownHookInstalled.set(false)
            logger.warn(LogCategory.UI, "Could not install capture shutdown hook", error = it)
        }
    }

    private fun isWaylandSession(): Boolean = runCatching { LinuxInputCapture.isWayland }.getOrDefault(false)
}
