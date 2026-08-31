package ai.rever.boss.fullscreen

import ai.rever.boss.utils.SystemUtils
import java.awt.Rectangle
import java.awt.Window

/**
 * The platform's half of a captured full-screen session: confining the pointer to one display and
 * taking the OS shortcuts the window would otherwise lose.
 *
 * **Every method answers whether it actually worked, and none of them throws.** A partial grab is a
 * real outcome on every platform here - Wayland has no pointer grab for an ordinary client, and
 * BOSS deliberately does not ask for the macOS Accessibility permission that a total keyboard grab
 * would need - so "it did not happen" has to be reportable rather than exceptional. The controller
 * turns a false into a [CaptureLimitation] the user is told about; a throw would instead leave the
 * session half-entered with nothing on screen saying so.
 */
interface InputCapture {
    /**
     * Confine the pointer to [bounds] (screen coordinates of the display [window] is covering).
     *
     * @return true if the pointer is now genuinely confined.
     */
    fun confinePointer(
        window: Window,
        bounds: Rectangle,
    ): Boolean

    /** Undo [confinePointer]. Safe to call when nothing is confined. */
    fun releasePointer()

    /**
     * Take the OS-level shortcuts - Cmd+Tab, Alt+Tab, the Win key, the menu bar and Dock.
     *
     * @return true if the grab is in force.
     */
    fun grabKeyboard(window: Window): Boolean

    /** Undo [grabKeyboard]. Safe to call when nothing is grabbed. */
    fun releaseKeyboard()

    companion object {
        /**
         * The implementation for this machine.
         *
         * The no-op is the default rather than an error: an unsupported platform must degrade to an
         * ordinary maximised window that the user can leave normally, never to a window that has
         * announced a grab it does not have.
         */
        fun forCurrentPlatform(): InputCapture =
            when {
                SystemUtils.isMacOS -> MacInputCapture
                SystemUtils.isWindows -> WindowsInputCapture
                SystemUtils.isLinux -> LinuxInputCapture
                else -> NoopInputCapture
            }
    }
}

/** Grabs nothing and says so. See [InputCapture.forCurrentPlatform]. */
object NoopInputCapture : InputCapture {
    override fun confinePointer(
        window: Window,
        bounds: Rectangle,
    ): Boolean = false

    override fun releasePointer() = Unit

    override fun grabKeyboard(window: Window): Boolean = false

    override fun releaseKeyboard() = Unit
}
