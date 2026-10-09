package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.CleanupRunner
import ai.rever.boss.utils.logging.LogCategory
import java.util.concurrent.atomic.AtomicBoolean

/** Own local teardown once, retaining view-before-native ordering after any local failure. */
internal class BrowserHandleCleanup(
    private val disposed: AtomicBoolean,
    private val handleId: String,
) {
    private val closingPopOut = AtomicBoolean(false)

    fun run(
        teardown: (CleanupRunner) -> Unit,
        detachView: () -> Unit,
        requestNativeClose: () -> Unit,
        closePopOut: () -> Unit = {},
    ) {
        val ownsTeardown = disposed.compareAndSet(false, true)
        val cleanup = CleanupRunner("BrowserHandleImpl", mapOf("handleId" to handleId), category = LogCategory.BROWSER)
        // Repeated disposal must retry a pop-out whose EDT submission or frame disposal failed.
        // A native/window callback can synchronously reenter disposal while this close is running.
        if (closingPopOut.compareAndSet(false, true)) {
            try {
                cleanup.run("close browser pop-out", closePopOut)
            } finally {
                closingPopOut.set(false)
            }
        }
        if (!ownsTeardown) return
        try {
            cleanup.run("release local browser resources") { teardown(cleanup) }
        } finally {
            // This only requests the existing owned-executor drain. It does not close native
            // state under a running renderer call, even if a local cleanup step failed.
            finishLocalBrowserDisposal(
                detachView = { cleanup.run("detach browser view", detachView) },
                requestNativeClose = { cleanup.run("request native browser disposal", requestNativeClose) },
            )
        }
    }
}
