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
    private val closed = AtomicBoolean(false)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val cleanup = CleanupRunner("BrowserPopupWindow", category = LogCategory.BROWSER)
        cleanup.run("release popup subscriptions") {
            unsubscribe().forEach { release -> cleanup.run("unsubscribe popup event", release) }
        }
        cleanup.run("detach popup browser view", detachView)
        cleanup.run("dispose popup window", disposeWindow)
        cleanup.run("close popup browser", closeBrowser)
    }
}
