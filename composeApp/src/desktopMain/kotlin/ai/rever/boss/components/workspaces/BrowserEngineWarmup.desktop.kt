package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

private val warmupLogger = BossLogger.forComponent("BrowserEngineWarmup")

/**
 * Forced, because the caller has better information than the default gate does: a layout carrying
 * a browser tab is about to build one. The gate asks whether this machine has ever used the
 * browser, which on a first install is no - so the head start was skipped exactly where the boot
 * is most expensive and least expected.
 *
 * Contained rather than propagated. A pre-warm is an optimisation nobody asked for out loud, and
 * failing to start one must not stop a workspace from being applied; the tab boots the engine
 * itself and reports its own failure through the normal path.
 */
internal actual fun warmBrowserEngineForTabs() {
    // runCatching, matching main.kt's two pre-warm call sites: only starting the boot thread
    // happens here, so there is nothing cancellable to swallow, and the failure worth surviving
    // is whatever the engine's own lazy init raises (an Error from a broken Chromium bundle
    // included) rather than any one exception type.
    runCatching { FluckEngine.prewarmInBackground(force = true) }
        .onFailure {
            warmupLogger.warn(
                LogCategory.BROWSER,
                "Browser engine pre-warm for a restored browser tab failed to start",
                error = it,
            )
        }
}
