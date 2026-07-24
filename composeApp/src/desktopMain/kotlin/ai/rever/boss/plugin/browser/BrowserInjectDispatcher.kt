package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.InjectJsCallback
import com.teamdev.jxbrowser.frame.Frame
import java.util.Collections
import java.util.WeakHashMap

/**
 * Shared owner of a browser's single `InjectJsCallback` slot.
 *
 * JxBrowser allows exactly ONE `InjectJsCallback` per [Browser]; a second
 * `browser.set(InjectJsCallback…)` silently replaces the first. More than one feature
 * can want document-start injection (e.g. the co-browse rrweb recorder on the
 * `feat/cobrowse-tab-sharing` branch), so calling `browser.set` directly makes
 * whichever registers second clobber the other.
 *
 * This dispatcher claims the slot once per browser and fans each document-start event
 * out to every registered injector. **Every** document-start injector must go through
 * [register] instead of `browser.set(InjectJsCallback…)`, or the clobber returns.
 * (No injector is registered on main right now — the former WebAuthn shim was removed
 * when JxBrowser 9.3.0 gained native macOS Touch ID — but the co-browse branch
 * depends on this seam.)
 */
internal object BrowserInjectDispatcher {
    private val logger = BossLogger.forComponent("BrowserInjectDispatcher")

    // Weak keys so entries clear when a Browser is GC'd. Each value is the ordered list
    // of injectors invoked (in registration order) at document-start.
    private val injectors: MutableMap<Browser, MutableList<(Frame) -> Unit>> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * Register a document-start [injector] for [browser]. The first registration for a
     * browser installs the shared [InjectJsCallback]; later ones just append. The
     * injector receives each frame as its context is created (guard on `frame.isMain()`
     * if you only want the top frame). Injector exceptions are swallowed so one can't
     * break the page's JS thread or starve the others.
     */
    fun register(
        browser: Browser,
        injector: (Frame) -> Unit,
    ) {
        synchronized(injectors) {
            val existing = injectors[browser]
            if (existing != null) {
                existing.add(injector)
                return
            }
            injectors[browser] = mutableListOf(injector)
        }
        // First injector for this browser → claim the single callback slot.
        try {
            browser.set(
                InjectJsCallback::class.java,
                InjectJsCallback { params ->
                    val frame = params.frame()
                    val handlers = synchronized(injectors) { injectors[frame.browser()]?.toList() }.orEmpty()
                    for (handler in handlers) {
                        try {
                            handler(frame)
                        } catch (e: Throwable) {
                            logger.debug(
                                LogCategory.BROWSER,
                                "Inject handler failed",
                                mapOf("error" to (e.message ?: "")),
                            )
                        }
                    }
                    InjectJsCallback.Response.proceed()
                },
            )
        } catch (e: Throwable) {
            logger.warn(LogCategory.BROWSER, "Failed to register shared InjectJsCallback", error = e)
        }
    }
}
