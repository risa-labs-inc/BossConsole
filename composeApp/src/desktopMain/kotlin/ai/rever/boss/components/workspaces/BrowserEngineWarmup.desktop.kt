package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

private val warmupLogger = BossLogger.forComponent("BrowserEngineWarmup")

internal actual fun warmBrowserEngineForTabs() = warmBrowserEngine(FluckEngine::prewarmInBackground)

/**
 * Ask [prewarm] to start the engine boot, forced.
 *
 * Forced because the caller has better information than the default gate does: a layout carrying a
 * browser tab is about to build one. The gate asks whether this machine has ever used the browser,
 * which on a first install is no - so the head start was skipped exactly where the boot is most
 * expensive and least expected.
 *
 * **The side effect is real and accepted.** A forced boot creates the browser profile directory,
 * which is the very thing the unforced gate keys on - so a restored layout with a *background*
 * browser tab the user never selects still satisfies that gate from then on. That is the intended
 * trade: a layout with a browser tab is a browser user, and selecting the tab would boot the engine
 * anyway. It is called out because the argument for not defaulting `force` on leans on this same
 * side effect, and the next reader should see that it was weighed rather than missed.
 *
 * Contained rather than propagated. A pre-warm is an optimisation nobody asked for out loud, and
 * failing to start one must not stop a workspace from being applied; the tab boots the engine
 * itself and reports its own failure through the normal path.
 *
 * [prewarm] is a parameter for one reason: `force = true` IS the fix, and a silent regression to
 * the default would leave every test green while the feature was gone - the same failure class as
 * the bug being fixed, a call site that looks right and does nothing.
 */
internal fun warmBrowserEngine(prewarm: (Boolean) -> Unit) {
    // runCatching, matching main.kt's pre-warm call site: only starting the boot thread happens
    // here, so there is nothing cancellable to swallow, and the failure worth surviving is whatever
    // the engine's own lazy init raises (an Error from a broken Chromium bundle included) rather
    // than any one exception type.
    runCatching { prewarm(true) }
        .onFailure {
            warmupLogger.warn(
                LogCategory.BROWSER,
                "Browser engine pre-warm for a restored browser tab failed to start",
                error = it,
            )
        }
}
