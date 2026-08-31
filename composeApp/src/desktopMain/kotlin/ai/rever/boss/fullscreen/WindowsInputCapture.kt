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
@file:Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount", "FunctionNaming")

package ai.rever.boss.fullscreen

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = BossLogger.forComponent("WindowsInputCapture")

/**
 * `ClipCursor`, which jna-platform's own `User32` does not declare.
 *
 * This is the one platform with a system call for precisely this feature: the cursor is confined by
 * the OS, so there is no polling, no warping and no chance of the pointer being visibly outside the
 * area for a frame. Passing null releases it.
 */
private interface User32Ext : StdCallLibrary {
    companion object {
        val INSTANCE: User32Ext? =
            try {
                Native.load("user32", User32Ext::class.java)
            } catch (e: Throwable) {
                logger.debug(LogCategory.SYSTEM, "user32 unavailable", mapOf("error" to (e.message ?: "unknown")))
                null
            }
    }

    @Suppress("ktlint:standard:function-naming") // JNA maps by native symbol name
    fun ClipCursor(rect: WinDef.RECT?): Boolean
}

/**
 * Windows: `ClipCursor` for the pointer, a low-level keyboard hook for the OS shortcuts.
 *
 * The hook swallows Alt+Tab, both Windows keys, Ctrl+Esc and Alt+Esc. **Ctrl+Alt+Del cannot be
 * intercepted by any user-mode process** - it is handled by the Secure Attention Sequence below
 * anything an application can install - and this deliberately does not try, rather than appearing
 * to and failing.
 */
object WindowsInputCapture : InputCapture {
    private var hook: WinUser.HHOOK? = null
    private var pumpThread: Thread? = null
    private val pumping = AtomicBoolean(false)

    /**
     * Held for as long as the hook is installed.
     *
     * A JNA callback that is only referenced from native code is unreachable to the JVM, so without
     * a strong reference here the collector is free to take it while Windows still holds its
     * address - which crashes the process on the next keystroke rather than at the point of the
     * mistake.
     */
    private var callback: WinUser.LowLevelKeyboardProc? = null

    override fun confinePointer(
        window: Window,
        bounds: Rectangle,
    ): Boolean {
        val user32 = User32Ext.INSTANCE ?: return false
        val rect =
            WinDef.RECT().apply {
                left = bounds.x
                top = bounds.y
                right = bounds.x + bounds.width
                bottom = bounds.y + bounds.height
            }
        return try {
            user32.ClipCursor(rect)
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "ClipCursor failed", error = e)
            false
        }
    }

    override fun releasePointer() {
        try {
            User32Ext.INSTANCE?.ClipCursor(null)
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "ClipCursor release failed", error = e)
        }
    }

    override fun grabKeyboard(window: Window): Boolean {
        if (hook != null) return true

        val proc =
            WinUser.LowLevelKeyboardProc { nCode, wParam, info ->
                if (nCode >= 0 && shouldSwallow(info)) {
                    // Returning non-zero without calling the next hook is what actually eats the
                    // key: the shell never sees it, so Alt+Tab does not raise the switcher.
                    WinDef.LRESULT(1)
                } else {
                    // The struct's own address is what the next hook in the chain expects as lParam.
                    User32.INSTANCE.CallNextHookEx(
                        null,
                        nCode,
                        wParam,
                        WinDef.LPARAM(info?.pointer?.let { Pointer.nativeValue(it) } ?: 0L),
                    )
                }
            }

        val started = CountDownLatch(1)
        val installed = AtomicBoolean(false)
        pumping.set(true)

        // The hook has to be installed from a thread with a message loop, because Windows delivers
        // it by posting to that thread. The EDT has one but is not ours to block, and a hook that
        // stalls the EDT would stall the whole UI on every keystroke.
        val thread =
            Thread({
                try {
                    val module = Kernel32.INSTANCE.GetModuleHandle(null)
                    val installedHook = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, proc, module, 0)
                    hook = installedHook
                    installed.set(installedHook != null)
                    started.countDown()
                    if (installedHook == null) return@Thread
                    pump()
                } catch (e: Throwable) {
                    logger.warn(LogCategory.SYSTEM, "Keyboard hook thread failed", error = e)
                    installed.set(false)
                    started.countDown()
                }
            }, "boss-keyboard-hook")
        thread.isDaemon = true
        pumpThread = thread
        callback = proc
        thread.start()

        // Bounded: the caller is a user action and must not hang if SetWindowsHookEx does.
        started.await(INSTALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!installed.get()) {
            releaseKeyboard()
            return false
        }
        return true
    }

    override fun releaseKeyboard() {
        pumping.set(false)
        hook?.let {
            try {
                User32.INSTANCE.UnhookWindowsHookEx(it)
            } catch (e: Throwable) {
                logger.warn(LogCategory.SYSTEM, "UnhookWindowsHookEx failed", error = e)
            }
        }
        hook = null
        pumpThread = null
        // Only after the hook is gone: while it is installed this reference is the only thing
        // keeping the callback alive for native code.
        callback = null
    }

    /**
     * A message loop that can be asked to stop.
     *
     * `GetMessage` blocks until something arrives, and nothing routinely does on a thread with no
     * windows - so a hook installed with it could not be uninstalled without posting a message to
     * wake it. Peeking and sleeping keeps the loop responsive to [pumping] at a cost of one
     * near-idle thread for as long as the mode is on.
     */
    private fun pump() {
        val msg = WinUser.MSG()
        while (pumping.get()) {
            while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            }
            try {
                Thread.sleep(PUMP_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Whether this key belongs to the OS rather than to BOSS while a session is captured.
     *
     * Reads the live modifier state, because `KBDLLHOOKSTRUCT` carries the key and nothing about
     * what is held with it, and the modifier is the entire difference between a shortcut this mode
     * exists to swallow and a key the app cannot function without.
     */
    private fun shouldSwallow(info: WinUser.KBDLLHOOKSTRUCT?): Boolean {
        val code = info?.vkCode ?: return false
        return swallowsVirtualKey(
            vkCode = code,
            altDown = isDown(VK_MENU),
            ctrlDown = isDown(VK_CONTROL),
        )
    }

    /** High bit of `GetAsyncKeyState` means the key is down right now. */
    private fun isDown(vk: Int): Boolean =
        try {
            (User32.INSTANCE.GetAsyncKeyState(vk).toInt() and 0x8000) != 0
        } catch (e: Throwable) {
            false
        }

    private const val PM_REMOVE = 0x0001
    private const val PUMP_INTERVAL_MS = 5L
    private const val INSTALL_TIMEOUT_MS = 2000L
}

/** Left Windows key. */
internal const val VK_LWIN = 0x5B

/** Right Windows key. */
internal const val VK_RWIN = 0x5C

internal const val VK_TAB = 0x09

internal const val VK_ESCAPE = 0x1B

/** Either Alt key. */
internal const val VK_MENU = 0x12

/** Either Ctrl key. */
internal const val VK_CONTROL = 0x11

/**
 * The virtual keys a captured session takes from the OS.
 *
 * Pure and separate from the hook so the table can be tested off Windows: which keys are taken is a
 * decision, not a system call.
 *
 * - **Both Windows keys, unconditionally.** They do nothing except leave BOSS.
 * - **Tab only with Alt down, Escape only with Ctrl or Alt down.** Alt+Tab, Ctrl+Esc and Alt+Esc are
 *   the three ways to raise the shell's switcher, and they are the reason this hook exists. Bare
 *   Tab and bare Escape are *not* taken, which is the whole point of asking for the modifier: a
 *   first draft of this swallowed both outright and would have broken Tab in the editor and Escape
 *   in every terminal, dialog and vim session in the app - inside a mode whose panic escape is
 *   holding Escape.
 *
 * Ctrl+Alt+Del is absent on purpose: no user-mode hook can intercept the Secure Attention Sequence,
 * and listing it would only suggest otherwise.
 */
internal fun swallowsVirtualKey(
    vkCode: Int,
    altDown: Boolean,
    ctrlDown: Boolean,
): Boolean =
    when (vkCode) {
        VK_LWIN, VK_RWIN -> true
        VK_TAB -> altDown
        VK_ESCAPE -> ctrlDown || altDown
        else -> false
    }
