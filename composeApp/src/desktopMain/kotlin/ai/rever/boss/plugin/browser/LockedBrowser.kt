package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.devtools.DevTools
import com.teamdev.jxbrowser.event.Subscription
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.navigation.Navigation
import com.teamdev.jxbrowser.search.FindOptions
import com.teamdev.jxbrowser.search.FindResult
import com.teamdev.jxbrowser.search.TextFinder
import com.teamdev.jxbrowser.zoom.Zoom
import com.teamdev.jxbrowser.zoom.ZoomLevel
import java.util.Optional
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Consumer
import kotlin.concurrent.read

/**
 * Thread-safe wrapper for JxBrowser operations.
 *
 * All browser access is protected by a read lock to prevent
 * "closed object" exceptions during disposal (Issue #255).
 *
 * The lock is acquired automatically on every method call,
 * ensuring no race conditions between browser operations
 * and disposal on the IO thread.
 *
 * Usage:
 * ```
 * val lockedBrowser = LockedBrowser(browser, browserLock)
 * val url = lockedBrowser.url()  // Automatically acquires read lock
 * lockedBrowser.navigation().goBack()  // Lock handled internally
 * ```
 */
class LockedBrowser(
    private val browser: Browser,
    private val lock: ReentrantReadWriteLock
) {
    fun url(): String = lock.read { browser.url() }

    /**
     * Safe URL accessor that returns empty string if browser is closed.
     * Use this for Compose remember dependencies to prevent ObjectClosedException.
     *
     * Note: The isClosed check is an optimization to avoid exceptions in the common case.
     * The try-catch is the real protection against the race condition where disposal
     * could complete between the check and the url() call.
     */
    fun urlOrEmpty(): String = try {
        lock.read {
            if (browser.isClosed) "" else browser.url()
        }
    } catch (e: Exception) {
        ""
    }

    fun title(): String = lock.read { browser.title() }

    val isClosed: Boolean
        get() = lock.read { browser.isClosed }

    fun navigation(): LockedNavigation = LockedNavigation(browser.navigation(), lock)

    fun mainFrame(): Optional<Frame> = lock.read { browser.mainFrame() }

    fun devTools(): LockedDevTools = LockedDevTools(browser.devTools(), lock)

    fun zoom(): LockedZoom = LockedZoom(browser.zoom(), lock)

    fun textFinder(): LockedTextFinder = LockedTextFinder(browser.textFinder(), lock)

    /**
     * Access raw browser for operations that can't be wrapped.
     * Use this for event registration (browser.on()) and callback registration (browser.set())
     * since these are typically done once during setup and don't need per-call locking.
     * WARNING: For regular browser operations, use wrapper methods instead.
     */
    fun unsafe(): Browser = browser
}

/**
 * Thread-safe wrapper for Navigation operations.
 */
class LockedNavigation(
    private val navigation: Navigation,
    private val lock: ReentrantReadWriteLock
) {
    fun loadUrl(url: String) = lock.read { navigation.loadUrl(url) }

    fun canGoBack(): Boolean = lock.read { navigation.canGoBack() }

    fun canGoForward(): Boolean = lock.read { navigation.canGoForward() }

    fun goBack() = lock.read { navigation.goBack() }

    fun goForward() = lock.read { navigation.goForward() }

    fun reload() = lock.read { navigation.reload() }

    fun stop() = lock.read { navigation.stop() }
}

/**
 * Thread-safe wrapper for DevTools operations.
 */
class LockedDevTools(
    private val devTools: DevTools,
    private val lock: ReentrantReadWriteLock
) {
    fun show() = lock.read { devTools.show() }
}

/**
 * Thread-safe wrapper for Zoom operations.
 */
class LockedZoom(
    private val zoom: Zoom,
    private val lock: ReentrantReadWriteLock
) {
    fun level(): ZoomLevel = lock.read { zoom.level() }

    fun level(newLevel: ZoomLevel) = lock.read { zoom.level(newLevel) }
}

/**
 * Thread-safe wrapper for TextFinder operations (find-in-page).
 */
class LockedTextFinder(
    private val textFinder: TextFinder,
    private val lock: ReentrantReadWriteLock
) {
    fun find(text: String, callback: Consumer<FindResult>) = lock.read {
        textFinder.find(text, callback)
    }

    fun find(text: String, options: FindOptions, callback: Consumer<FindResult>) = lock.read {
        textFinder.find(text, options, callback)
    }

    fun stopFindingAndClearSelection() = lock.read {
        textFinder.stopFindingAndClearSelection()
    }
}
