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
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package ai.rever.boss.fullscreen

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window

private val logger = BossLogger.forComponent("LinuxInputCapture")

/**
 * Linux: the pointer is confined on X11, and the keyboard is not grabbed at all.
 *
 * ### The pointer
 *
 * [Robot.mouseMove] rather than the `CGWarpMouseCursorPosition` equivalent macOS uses, because X11
 * has no permission gate on synthesised input - the objection that rules Robot out on macOS simply
 * does not apply here, and a Robot needs no native bindings.
 *
 * Under **Wayland** it does not work: the protocol gives an ordinary client no way to place the
 * pointer, and `XWarpPointer` through XWayland moves the cursor only within the compatibility
 * surface. That is reported as [CaptureLimitation.WAYLAND_NO_GRAB] rather than as a failure,
 * because it is the permanent and correct answer there rather than something that went wrong.
 *
 * ### The keyboard
 *
 * **Not grabbed, and this is the one platform where that is true.** A working X11 grab needs
 * `XGrabKeyboard` against *this application's own X window id*, and the only route to that from the
 * JVM is a reflective walk into `sun.awt.X11.XBaseWindow`, which is encapsulated on every JDK BOSS
 * ships against. The alternative - grabbing to the root window on a display connection of our own -
 * compiles, runs, and redirects every keystroke to a connection with no windows, which takes the
 * keyboard away from BOSS as thoroughly as from the OS.
 *
 * So this reports [CaptureLimitation.KEYBOARD_NOT_GRABBED] and the session tells the user their OS
 * shortcuts still work. Half a grab that silently drops keys would be worse than none.
 */
object LinuxInputCapture : InputCapture {
    private val robot: Robot? by lazy {
        try {
            Robot()
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Robot unavailable, no pointer confinement", error = e)
            null
        }
    }

    private val confiner =
        PointerConfiner { x, y ->
            val r = robot ?: return@PointerConfiner false
            try {
                r.mouseMove(x.toInt(), y.toInt())
                true
            } catch (e: Throwable) {
                false
            }
        }

    /**
     * Whether this session is Wayland.
     *
     * `XDG_SESSION_TYPE` is the value display managers set; `WAYLAND_DISPLAY` is checked too because
     * a session started outside a display manager often has only that.
     */
    val isWayland: Boolean
        get() =
            System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true ||
                !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()

    override fun confinePointer(
        window: Window,
        bounds: Rectangle,
    ): Boolean {
        if (isWayland) return false
        return confiner.start(bounds)
    }

    override fun releasePointer() = confiner.stop()

    override fun grabKeyboard(window: Window): Boolean = false

    override fun releaseKeyboard() = Unit
}
