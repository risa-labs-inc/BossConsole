package ai.rever.boss.sharing

import ai.rever.boss.utils.WindowFocusManager
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyTyped
import java.awt.Window
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One key, one exact browser/window, only during the already-authorized native dispatch. */
internal object AppBrowserKeyDispatch {
    private data class Permit(
        val window: Window,
        val event: KeyPressed,
        val current: () -> Boolean,
        val consumed: CountDownLatch = CountDownLatch(1),
    )

    private val pending = ConcurrentHashMap<Browser, Permit>()
    private val gated = Collections.synchronizedMap(WeakHashMap<Browser, Boolean>())

    fun register(browser: Browser) {
        gated[browser] = true
    }

    fun press(
        browser: Browser,
        window: Window,
        event: KeyPressed,
        current: () -> Boolean,
    ) {
        dispatch(browser, window, event, current) { browser.dispatch(event) }
    }

    fun type(
        browser: Browser,
        window: Window,
        event: KeyTyped,
        current: () -> Boolean,
    ) {
        val pressed =
            KeyPressed
                .newBuilder(event.keyCode())
                .keyChar(event.keyChar())
                .keyModifiers(event.keyModifiers())
                .build()
        dispatch(browser, window, pressed, current) { browser.dispatch(event) }
    }

    private fun dispatch(
        browser: Browser,
        window: Window,
        event: KeyPressed,
        current: () -> Boolean,
        send: () -> Unit,
    ) {
        if (gated[browser] != true) {
            send()
            return
        }
        val permit = Permit(window, event, current)
        check(pending.putIfAbsent(browser, permit) == null)
        try {
            send()
            // Chromium acknowledges dispatch before its callback runs on another thread.
            // Keep only this key's permit until consumption; never leave a reusable background gate.
            permit.consumed.await(250, TimeUnit.MILLISECONDS)
        } finally {
            pending.remove(browser, permit)
        }
    }

    /** Callback-thread gate. It cannot route host shortcuts or authorize a later/background key. */
    fun consume(
        browser: Browser,
        event: KeyPressed,
        ownerWindowId: String?,
    ): Boolean {
        val permit = pending[browser] ?: return false
        val owner = ownerWindowId?.let(WindowFocusManager::getWindow)
        val matches =
            owner === permit.window && permit.current() &&
                event.keyCode() == permit.event.keyCode() && sameModifiers(event, permit.event)
        val accepted = matches && pending.remove(browser, permit)
        if (accepted) permit.consumed.countDown()
        return accepted
    }

    private fun sameModifiers(
        first: KeyPressed,
        second: KeyPressed,
    ): Boolean {
        val a = first.keyModifiers()
        val b = second.keyModifiers()
        return a.isAltDown == b.isAltDown && a.isControlDown == b.isControlDown &&
            a.isMetaDown == b.isMetaDown && a.isShiftDown == b.isShiftDown
    }
}
