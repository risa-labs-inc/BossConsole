package ai.rever.boss.fullscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A part of the input grab that a platform could not deliver.
 *
 * Reported rather than swallowed, because every one of these changes what the user is about to
 * experience and there is no way for them to discover it by looking. A window that says it has
 * captured the keyboard and then lets Alt+Tab through is worse than one that says it could not.
 */
enum class CaptureLimitation {
    /** The pointer roams freely: no confinement API on this platform, or the call failed. */
    POINTER_NOT_CONFINED,

    /** OS shortcuts still reach the OS: no keyboard grab on this platform, or the call failed. */
    KEYBOARD_NOT_GRABBED,

    /**
     * Wayland, specifically.
     *
     * Split out from [POINTER_NOT_CONFINED] because it is not a failure to be retried or reported
     * as a fault - the protocol has no equivalent of `XGrabPointer` for an ordinary client, so this
     * is the permanent answer there and the message should say so rather than suggesting something
     * went wrong.
     */
    WAYLAND_NO_GRAB,
}

/**
 * Everything true of the one captured full-screen session, if there is one.
 *
 * **One session, application-wide, not one per window.** Capture takes a whole display and the
 * whole keyboard, so two captured windows is not a state that can exist; modelling it per window
 * would mean writing code to reconcile a second grab that can never be requested. [windowId] says
 * which window holds it.
 *
 * **Deliberately not persisted.** Nothing about a grab should survive a relaunch: someone who
 * quits while stuck - which is the reason they would be quitting - must come back unstuck. This is
 * the one piece of window state in the app that is rebuilt empty every launch on purpose.
 *
 * @property windowId The window holding the session, or null when nothing is captured.
 * @property pointerConfined Whether the pointer is actually confined right now. False while the
 *   user has released it with `view.pointer_release` and still has the keyboard grabbed, which is
 *   the ordinary way to reach something on another monitor without leaving the mode.
 * @property keyboardGrabbed Whether the OS-level keyboard grab is in force.
 * @property limitations What this platform could not do. Empty on a clean grab.
 */
data class CapturedFullScreen(
    val windowId: String? = null,
    val pointerConfined: Boolean = false,
    val keyboardGrabbed: Boolean = false,
    val limitations: Set<CaptureLimitation> = emptySet(),
) {
    /** Whether a session is running at all. */
    val active: Boolean get() = windowId != null

    /**
     * Whether [id] is the window holding the session.
     *
     * Every UI read goes through this rather than through [active], because a second window is a
     * perfectly ordinary window while another one is captured, and reading the global flag would
     * strip its chrome to serve a session it is not part of.
     */
    fun capturing(id: String?): Boolean = id != null && windowId == id
}

/**
 * Whether the window's own chrome - title row, top bar, strips, bottom bar - should be drawn.
 *
 * Captured full screen is the third way a bar can be absent, after the standing
 * `WindowAppearanceSettings` preference and the transient focus-mode clearance, and it is not the
 * same as either. It outranks both: the point of the mode is that the display belongs to the
 * content, so a bar the user has switched on is still not drawn while it runs.
 *
 * **Stated as its own term and not folded into the other two.** `docs/release-notes/v9.4.13.md`
 * records what folding them cost the last time: the top bar can be gone for reasons that are not
 * symmetric, a predicate that reads well collapsed them, and Sign Out ended up rendered nowhere.
 * The three reasons a bar is missing stay three separate conjuncts at every gate.
 */
fun CapturedFullScreen.suppressesChrome(windowId: String?): Boolean = capturing(windowId)

/**
 * The single application-wide capture session.
 *
 * commonMain so the scaffold can read it without an expect/actual for a plain flag; the native
 * grabbing that drives it lives in `desktopMain`'s `CapturedFullScreenController`, which is the
 * only thing that should ever call the mutators here.
 */
object CapturedFullScreenState {
    private val _current = MutableStateFlow(CapturedFullScreen())

    /** The live session. Read this from UI; never write to it outside the controller. */
    val current: StateFlow<CapturedFullScreen> = _current.asStateFlow()

    /** Replace the session wholesale. Called by the controller once a grab has been attempted. */
    fun set(session: CapturedFullScreen) {
        _current.value = session
    }

    /**
     * End the session unconditionally.
     *
     * Idempotent, because it is called from every teardown path there is - focus loss, dispose,
     * the shutdown hook, a native call that threw, and the OS reporting that full screen ended -
     * and several of them can fire for one exit.
     */
    fun clear() {
        _current.value = CapturedFullScreen()
    }

    /** Update the pointer half without disturbing the keyboard half. */
    fun setPointerConfined(confined: Boolean) {
        _current.update { if (it.active) it.copy(pointerConfined = confined) else it }
    }
}
