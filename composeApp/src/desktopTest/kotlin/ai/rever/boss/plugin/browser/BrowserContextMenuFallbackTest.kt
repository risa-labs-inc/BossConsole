package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.callback.BrowserCallback
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.callback.Advisable
import com.teamdev.jxbrowser.callback.Callback
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserContextMenuFallbackTest {
    private class RecordingAdvisable : Advisable<BrowserCallback> {
        val callbacks = mutableMapOf<Class<out Callback>, BrowserCallback>()

        @Suppress("UNCHECKED_CAST")
        override fun <C : BrowserCallback> set(
            type: Class<C>,
            callback: C,
        ): C? = callbacks.put(type, callback) as C?

        @Suppress("UNCHECKED_CAST")
        override fun <C : BrowserCallback> get(type: Class<C>): Optional<C> = Optional.ofNullable(callbacks[type] as C?)

        @Suppress("UNCHECKED_CAST")
        override fun <C : BrowserCallback> remove(type: Class<C>): C? = callbacks.remove(type) as C?
    }

    @Test
    fun `fallback claims the context menu before the view can install its default`() {
        val target = RecordingAdvisable()

        BrowserContextMenuFallback.registerOn(target)

        assertTrue(target.get(ShowContextMenuCallback::class.java).isPresent)
    }

    @Test
    fun `fallback does not replace a richer context menu`() {
        val target = RecordingAdvisable()
        val rich = ShowContextMenuCallback { _, action -> action.close() }
        target.set(ShowContextMenuCallback::class.java, rich)

        BrowserContextMenuFallback.registerOn(target)

        assertSame(rich, target.get(ShowContextMenuCallback::class.java).orElse(null))
    }

    @Test
    fun `fallback always answers Chromium's menu request`() {
        val target = RecordingAdvisable()
        BrowserContextMenuFallback.registerOn(target)
        val callback = target.get(ShowContextMenuCallback::class.java).orElseThrow()
        val params =
            Proxy.newProxyInstance(
                ShowContextMenuCallback.Params::class.java.classLoader,
                arrayOf(ShowContextMenuCallback.Params::class.java),
            ) { _, _, _ -> null } as ShowContextMenuCallback.Params
        val action = ShowContextMenuCallback.Action { }

        callback.on(params, action)

        assertTrue(action.isClosed)
    }
}
