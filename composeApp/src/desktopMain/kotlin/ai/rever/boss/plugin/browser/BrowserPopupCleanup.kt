package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.CleanupRunner
import ai.rever.boss.utils.logging.LogCategory
import java.util.concurrent.atomic.AtomicBoolean

/** Shared by native close, the window close button, and partially constructed popup failure. */
internal class BrowserPopupCleanup(
    private val unsubscribe: () -> List<() -> Unit>,
    private val detachView: () -> Unit,
    private val disposeWindow: () -> Unit,
    private val closeBrowser: () -> Unit,
) {
    private val closing = AtomicBoolean(false)
    private var pendingSubscriptions: List<() -> Unit>? = null
    private var viewDetached = false
    private var windowDisposed = false
    private var browserClosed = false

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        val cleanup = CleanupRunner("BrowserPopupWindow", category = LogCategory.BROWSER)
        try {
            releaseSubscriptions(cleanup)
            // Keep failed releases retryable, while successful releases stay once-only.
            // Each attempt preserves view-before-window-before-native ownership ordering.
            if (!viewDetached) viewDetached = cleanup.run("detach popup browser view", detachView)
            if (!windowDisposed) windowDisposed = cleanup.run("dispose popup window", disposeWindow)
            if (!browserClosed) browserClosed = cleanup.run("close popup browser", closeBrowser)
        } finally {
            closing.set(false)
        }
    }

    private fun releaseSubscriptions(cleanup: CleanupRunner) {
        if (pendingSubscriptions == null) {
            cleanup.run("capture popup subscriptions") { pendingSubscriptions = unsubscribe() }
        }
        val pending = pendingSubscriptions ?: return
        pendingSubscriptions = pending.filterNot { release -> cleanup.run("unsubscribe popup event", release) }
    }
}
