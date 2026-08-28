package ai.rever.boss.plugin.browser

import ai.rever.boss.config.SwipeNavSettingsManager
import ai.rever.boss.config.SwipeNavStyle
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * The in-page two-finger swipe detector, as JavaScript.
 *
 * A macOS trackpad swipe cannot be observed from the JVM side of this app. Under the default
 * `HARDWARE_ACCELERATED` rendering mode the browser is a native surface layered over the window
 * rather than a component in the Compose scene, so Compose never sees the wheel (the same fact
 * [shouldAllowPinch] documents at length). Chromium's own overscroll history navigation is already
 * switched on in `BrowserServiceImpl` and does nothing for a trackpad in EITHER rendering mode -
 * measured, see the note at that call site; it is a touchscreen feature. What is left is the
 * renderer: it sees every wheel event, because that is how pages scroll.
 *
 * So the gesture is detected in the page and reported back through [BrowserSwipeNavBridge]. That
 * placement is not only a workaround - it is also where the question "would this scroll have gone
 * to the page?" can actually be answered, which is what separates a swipe over a carousel from a
 * swipe that means "go back".
 *
 * The script is a resource rather than a Kotlin string (unlike [BrowserInteractionScript]) so the
 * gesture logic can be run and exercised directly. See `swipe-nav.test.js`.
 */
internal object BrowserSwipeNavScript {
    private val logger = BossLogger.forComponent("BrowserSwipeNavScript")

    /** Property the bridge is published on. Matched by the script's `window.__bossSwipeNav`. */
    const val BRIDGE_PROPERTY: String = "__bossSwipeNav"

    /** Property the host pushes navigability onto. Matched by the script. */
    const val STATE_PROPERTY: String = "__bossSwipeNavState"

    /** Property the host pushes the presentation onto. Matched by the script. */
    const val STYLE_PROPERTY: String = "__bossSwipeNavStyle"

    /** Lazily-loaded gesture script (cached for the process lifetime). */
    val source: String by lazy { loadResource("/browser/swipe-nav.js") }

    /**
     * Whether to inject at all.
     *
     * macOS only, because the gesture being restored is a macOS one: a two-finger horizontal
     * trackpad swipe means back/forward there and nothing in particular on Windows or Linux, where
     * it is an ordinary horizontal scroll that users expect to scroll.
     */
    fun isEnabled(): Boolean = swipeNavEnabled(SystemUtils.isMacOS, SwipeNavSettingsManager.current())

    /**
     * Statement that sets the direction availability the script gates on.
     *
     * Pushed by the host, never pulled by the page. A synchronous call from the page's JS thread
     * into a bridge method that then reaches into `browser.navigation()` is a deadlock waiting for
     * a slow renderer, and the host already recomputes both flags on every navigation for the
     * toolbar - so the value is free and the push is one statement.
     *
     * Both inputs are booleans, so there is nothing here a page could inject into.
     */
    fun stateUpdate(
        canGoBack: Boolean,
        canGoForward: Boolean,
    ): String = "window.$STATE_PROPERTY = { back: $canGoBack, forward: $canGoForward };"

    /**
     * Statement that sets the presentation.
     *
     * Pushed on every navigation AND whenever the setting changes, because a page already open
     * would otherwise keep drawing a chevron the user has just switched away from until they
     * happened to navigate. The value is an enum's own name, so there is nothing to escape.
     */
    fun styleUpdate(style: SwipeNavStyle): String = "window.$STYLE_PROPERTY = '${style.settingValue}';"

    private fun loadResource(path: String): String =
        try {
            BrowserSwipeNavScript::class.java.getResourceAsStream(path)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: run {
                logger.error(LogCategory.BROWSER, "Swipe navigation resource missing: $path")
                ""
            }
        } catch (
            // Reading a classpath resource fails in more ways than are worth enumerating, and the
            // answer is the same for all of them: no script, logged, and a browser that behaves
            // exactly as it did before this feature existed.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(LogCategory.BROWSER, "Failed to load swipe navigation resource $path", error = e)
            ""
        }
}

/**
 * Pure part of [BrowserSwipeNavScript.isEnabled], split out so the platform gate is pinned by a
 * test rather than by whatever platform CI happens to run on.
 */
internal fun swipeNavEnabled(
    isMac: Boolean,
    style: SwipeNavStyle,
): Boolean = isMac && style != SwipeNavStyle.OFF
