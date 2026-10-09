package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.CleanupRunner
import ai.rever.boss.utils.logging.LogCategory

/** Attempt every frame release in native ownership order; the caller retains a failed frame. */
internal fun closeBrowserPopOutResources(
    handleId: String,
    detachView: () -> Unit,
    disableAlwaysOnTop: () -> Unit,
    disposeWindow: () -> Unit,
): Boolean {
    val cleanup = CleanupRunner("BrowserHandleImpl", mapOf("handleId" to handleId), category = LogCategory.BROWSER)
    cleanup.run("detach browser pop-out view", detachView)
    cleanup.run("disable browser pop-out always-on-top", disableAlwaysOnTop)
    return cleanup.run("dispose browser pop-out window", disposeWindow)
}
