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
@file:Suppress("TooGenericExceptionCaught", "ReturnCount", "FunctionNaming")

package ai.rever.boss.fullscreen

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.awt.Rectangle
import java.awt.Window

private val logger = BossLogger.forComponent("MacInputCapture")

/**
 * CoreGraphics, for moving the pointer.
 *
 * `CGWarpMouseCursorPosition` and not [java.awt.Robot], which is the obvious answer and the wrong
 * one: Robot *synthesises input events*, so since macOS 10.14 it needs the Accessibility
 * permission, with a System Settings trip and a relaunch. A warp is not an event - it repositions
 * the cursor - and needs no permission at all. Choosing between them is the whole reason captured
 * full screen can be entered without a prompt.
 *
 * Also not `CGAssociateMouseAndMouseCursorPosition(false)`, which is a true relative-motion lock
 * of the kind a game or a VM console wants. It detaches the visible cursor from the mouse, and
 * BOSS underneath is a UI whose buttons you still have to see and click.
 */
private interface CoreGraphicsPointer : Library {
    companion object {
        val INSTANCE: CoreGraphicsPointer? =
            try {
                Native.load("CoreGraphics", CoreGraphicsPointer::class.java)
            } catch (e: Throwable) {
                logger.debug(LogCategory.SYSTEM, "CoreGraphics unavailable", mapOf("error" to (e.message ?: "unknown")))
                null
            }
    }

    /**
     * Move the cursor to a screen point, without generating a mouse event.
     *
     * CGPoint is two CGFloats, which JNA passes as a pair of doubles by value on both arm64 and
     * x86_64 for this signature.
     */
    @Suppress("ktlint:standard:function-naming") // JNA maps by native symbol name
    fun CGWarpMouseCursorPosition(
        x: Double,
        y: Double,
    ): Int
}

/**
 * `NSApplicationPresentationOptions`, the no-permission way to take the OS shortcuts on macOS.
 *
 * Setting `HideDock | HideMenuBar | DisableAppleMenu | DisableProcessSwitching` is documented kiosk
 * mode. It hides the Dock and the menu bar and, the part this feature is really for, stops Cmd+Tab
 * switching away from BOSS. It needs no entitlement and raises no prompt.
 *
 * ### Two things were measured before this was written, and both changed the design
 *
 * **Touching Objective-C before AWT is up deadlocks the process.** A probe that called
 * `[NSApplication sharedApplication]` on the main thread and only then created a `JFrame` hung at
 * AppKit initialisation and had to be killed. AWT brings NSApp up itself; asking for it first races
 * that. Ordering the other way round - window on screen, *then* the bridge - ran clean, set 58 and
 * restored 0. Everything here is therefore reached only from a user action on a window that is
 * already visible, which is exactly the ordering that was verified. Do not move any of it to
 * startup.
 *
 * **This is why captured full screen does not use AppKit full screen.** The same probe's
 * `com.apple.eawt.Application.requestToggleFullScreen` call blocked, twice, and never returned - so
 * the mode covers the display by sizing the window instead (see `CapturedFullScreenController`).
 * That turned out to be the better shape anyway: with the Dock and menu bar hidden by these
 * options there is nothing left for AppKit full screen to contribute, there is no Space transition
 * to animate through on the way in or out, and the presentation options never have to be reconciled
 * with a fullscreen state machine that owns them too.
 *
 * The traffic lights stay visible, which is deliberate. BOSS already draws its content under a
 * transparent title bar, hiding the buttons needs `NSWindow.standardWindowButton:` and so a
 * reflective walk into `sun.lwawt.macosx` internals, and they are a way out of the mode that costs
 * nothing to leave in place. Parallels keeps one too.
 */
private object MacPresentationOptions {
    const val AUTO_HIDE_DOCK = 1L shl 0
    const val HIDE_DOCK = 1L shl 1
    const val AUTO_HIDE_MENU_BAR = 1L shl 2
    const val HIDE_MENU_BAR = 1L shl 3
    const val DISABLE_APPLE_MENU = 1L shl 4
    const val DISABLE_PROCESS_SWITCHING = 1L shl 5

    /** The set this mode applies. Verified to set and restore cleanly from a live AWT app. */
    const val KIOSK = HIDE_DOCK or HIDE_MENU_BAR or DISABLE_APPLE_MENU or DISABLE_PROCESS_SWITCHING

    /**
     * The bits that must be cleared before [KIOSK] is OR-ed onto whatever AppKit currently has.
     *
     * Apple documents the invalid combinations as pairings, not as anything to do with full screen:
     * `AutoHideDock` with `HideDock`, `AutoHideMenuBar` with `HideMenuBar`, and `HideMenuBar`
     * without `HideDock`. [KIOSK] satisfies the third on its own; these two are what a naive OR
     * could reintroduce, since a window that has just entered AppKit full screen may well be
     * sitting on `AutoHideDock`. An invalid set raises an Objective-C exception, which is not
     * something a JVM reliably survives - so this is avoided by construction rather than caught.
     */
    const val AUTO_HIDE_MASK = AUTO_HIDE_DOCK or AUTO_HIDE_MENU_BAR

    const val DEFAULT = 0L

    private val objc: NativeLibrary? =
        try {
            NativeLibrary.getInstance("objc")
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "libobjc unavailable, no keyboard grab", error = e)
            null
        }

    private val msgSend: Function? = objc?.getFunction("objc_msgSend")

    private fun sel(name: String): Pointer? = objc?.getFunction("sel_registerName")?.invokePointer(arrayOf<Any>(name))

    private fun cls(name: String): Pointer? = objc?.getFunction("objc_getClass")?.invokePointer(arrayOf<Any>(name))

    /**
     * The shared NSApplication.
     *
     * Resolved lazily on each use rather than cached in an initialiser, so that the "AWT first"
     * ordering above is a property of the call site and not of when this object happened to load.
     */
    private fun nsApp(): Pointer? {
        val send = msgSend ?: return null
        val appClass = cls("NSApplication") ?: return null
        val shared = sel("sharedApplication") ?: return null
        return send.invokePointer(arrayOf<Any>(appClass, shared))
    }

    /** Whatever AppKit currently has, or null if the bridge is unavailable. */
    fun current(): Long? {
        val send = msgSend ?: return null
        val app = nsApp() ?: return null
        val getter = sel("presentationOptions") ?: return null
        return try {
            send.invokeLong(arrayOf<Any>(app, getter))
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "presentationOptions read failed", error = e)
            null
        }
    }

    fun apply(options: Long): Boolean {
        val send = msgSend ?: return false
        val app = nsApp() ?: return false
        val setter = sel("setPresentationOptions:") ?: return false
        return try {
            send.invokeVoid(arrayOf<Any>(app, setter, NativeLong(options)))
            true
        } catch (e: Throwable) {
            // An invalid combination raises an Objective-C exception, which JNA surfaces here for
            // the documented sets and cannot always survive. KIOSK is the only set this passes and
            // it was measured; anything else reaching this is a bug worth the log line.
            logger.warn(LogCategory.SYSTEM, "setPresentationOptions failed", mapOf("options" to options), error = e)
            false
        }
    }
}

/**
 * macOS: pointer confined by warping, OS shortcuts taken by kiosk presentation options.
 *
 * See [MacPresentationOptions] for why neither AppKit full screen nor `java.awt.Robot` is used.
 */
object MacInputCapture : InputCapture {
    private val confiner =
        PointerConfiner { x, y ->
            CoreGraphicsPointer.INSTANCE?.CGWarpMouseCursorPosition(x, y) != null
        }

    override fun confinePointer(
        window: Window,
        bounds: Rectangle,
    ): Boolean {
        if (CoreGraphicsPointer.INSTANCE == null) return false
        return confiner.start(bounds)
    }

    override fun releasePointer() = confiner.stop()

    override fun grabKeyboard(window: Window): Boolean {
        // Added to what AppKit already has rather than replacing it, because by this point the
        // window is in real full screen and AppKit owns bits of its own there. The AutoHide bits
        // are cleared first so the result cannot be one of the documented invalid combinations.
        val base =
            (MacPresentationOptions.current() ?: MacPresentationOptions.DEFAULT) and
                MacPresentationOptions.AUTO_HIDE_MASK.inv()
        return MacPresentationOptions.apply(base or MacPresentationOptions.KIOSK)
    }

    override fun releaseKeyboard() {
        MacPresentationOptions.apply(MacPresentationOptions.DEFAULT)
    }
}
