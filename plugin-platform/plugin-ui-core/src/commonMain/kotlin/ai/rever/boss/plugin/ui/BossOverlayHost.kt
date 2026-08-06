package ai.rever.boss.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.DialogProperties

/**
 * Where a modal surface goes when the browser is GPU-composited.
 *
 * Under JxBrowser's HARDWARE_ACCELERATED rendering mode the browser view is **not a node in the
 * Compose scene**: Chromium attaches its own native child window to the AWT window handle, and
 * that foreign surface composites *above* everything Compose paints. An ordinary Compose `Dialog`
 * therefore renders BEHIND the page it belongs to. Escaping it means putting the dialog in a
 * separate always-on-top OS window.
 *
 * The window code is platform-specific and lives in the host (`HeavyweightModal`), so it is
 * INJECTED here rather than depended on. This object is the single registry that both the host's
 * own dialogs and dynamic plugins route through, which is why it sits in plugin-ui-core: plugins
 * compile against this package and resolve it, at runtime, to the host's copy.
 *
 * Every field is WRITE-ONCE at startup, before any composition, which is why plain `@Volatile`
 * vars are enough. Composables read them directly and they are NOT snapshot state - flipping one
 * at runtime would not recompose anything already on screen.
 *
 * **This file exists twice, and the SIGNATURES must stay identical.** On a host that has this class
 * compiled in, plugin-ui-core is the copy that runs: `ai.rever.boss.plugin.ui.` is in
 * `PluginClassLoader.defaultSharedPackages`, so a plugin resolves it parent-first from the host, and
 * the host's copy is the one the startup injection writes to.
 *
 * The `boss-plugin-api` copy is NOT dead code, which is worth stating because it looks like it. On an
 * OLDER host these types are absent, and `ApiClassLoader` then serves them out of the installed api
 * jar - that is what makes them reachable at all there (see ApiClassLoader's own doc: brand-new types
 * ship via the jar, member additions to host-compiled types do not). On that path the api jar's
 * bodies really do execute, with nothing having injected a renderer, so they must degrade cleanly:
 * `BossDialog` falls back to a plain Compose `Dialog`. Their layout constants differ from the host's
 * only because that package predates the design-system tokens.
 *
 * **Which gate a plugin should declare, stated once so the two answers stop competing:**
 * `minApiVersion: 1.0.72` is what makes the symbols RESOLVE, and it is the minimum a plugin needs to
 * install and run. It does not promise the dialog is in front of the browser - on a host without
 * these types compiled in, the api jar's fallback is the pre-fix, occluded dialog. A plugin that
 * merely wants to compile and behave no worse than before needs only `minApiVersion`. A plugin whose
 * feature DEPENDS on the dialog actually clearing the browser surface must additionally gate on the
 * `minBossVersion` of the release that carries the host's copy.
 *
 * Signatures are the part that must match, for the reason `BossTheme` documents about overloads
 * versus defaulted parameters: one that differs links at build time and is missing at runtime, which
 * the binary-compatibility validator rejects as a whole-plugin failure.
 */
object BossOverlayHost {
    /** True when modals must escape into heavyweight windows (HARDWARE_ACCELERATED browser). */
    @Volatile
    var useHeavyweightOverlays: Boolean = false

    /**
     * Platform-injected modal renderer: shows [content] in a separate always-on-top window
     * covering the parent window. Null until injected; callers fall back to a Compose `Dialog`.
     *
     * Takes the caller's `DialogProperties` because the renderer is the only thing that can honour
     * some of them. `dismissOnBackPress` maps to Escape, and Escape is handled by the window itself
     * rather than by anything inside it - a renderer that never saw the properties silently made
     * every heavyweight dialog Escape-dismissable regardless. Passed at the boundary rather than
     * added later on purpose: this signature is pinned by the binary-compatibility validator in two
     * repos at once, so widening it after release costs a coordinated host and api release.
     */
    @Volatile
    var modalRenderer: (
        @Composable (
            properties: DialogProperties,
            onDismissRequest: () -> Unit,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null

    /**
     * Platform-injected POPUP renderer: shows [content] in a separate always-on-top window anchored
     * near [offset]. Null until injected; callers fall back to a Compose `Popup`.
     *
     * Separate from [modalRenderer] because a popup is not a modal, and the differences are the whole
     * reason a plugin cannot fake one with `BossDialog`: it is anchored rather than centered, it draws
     * no scrim, and with `focusable = false` it does not take focus - which is what a URL-bar
     * suggestion list needs, since the text field must keep focus while the user types.
     *
     * Signature matches the host's own popup renderer so the same window implementation serves the
     * host's context menus and a plugin's, rather than two that can drift.
     */
    @Volatile
    var popupRenderer: (
        @Composable (
            onDismissRequest: () -> Unit,
            anchorInWindow: IntRect,
            anchoring: BossPopupAnchoring,
            offset: IntOffset,
            focusable: Boolean,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null

    /**
     * How many heavyweight POPUP windows are currently open.
     *
     * Lets a heavyweight modal tell "the user clicked away" from "a child overlay of mine took
     * focus": both are separate always-on-top windows, so a dropdown opening inside a modal fires
     * the modal's `windowLostFocus` and would otherwise dismiss the dialog the dropdown belongs
     * to. Maintained by the host's popup renderer and read by the host's modal renderer; it lives
     * here so there is one counter rather than one per module.
     *
     * **UI-thread only.** `++`/`--` on a plain Int are not atomic, and a lost decrement would leave a
     * modal permanently unable to dismiss on focus loss. Every writer is a Compose
     * `DisposableEffect` on the UI thread, so the contract holds by construction; `@Volatile` is here
     * for safe publication to readers, not to make the arithmetic safe. Do not write it from a
     * background thread, and do not "fix" it to `AtomicInteger` casually - that changes the
     * descriptor and needs a coordinated host and api release.
     */
    @Volatile
    var openHeavyweightPopups: Int = 0

    /**
     * Optional sink for "this overlay degraded" messages, wired by the host to its logger.
     *
     * This module deliberately depends on nothing but Compose, so it cannot log on its own. The
     * one condition worth reporting is a null [modalRenderer] while [useHeavyweightOverlays] is
     * true: the dialog silently falls back to lightweight and is drawn behind the page, which is
     * the exact bug this file exists to fix, with nothing on screen to say so. That happens if a
     * plugin ever links its own copy of this class instead of the host's.
     */
    @Volatile
    var diagnostics: ((String) -> Unit)? = null

    /** Reported at most once per process; a per-frame warning would drown the log. */
    @Volatile
    private var reportedMissingRenderer = false

    /**
     * Report the null-renderer condition described on [diagnostics], at most once per process.
     *
     * Public rather than `internal` on purpose. Kotlin mangles an internal member's JVM name with the
     * MODULE name, so the two copies of this file would emit `reportMissingModalRenderer$...` under
     * two different suffixes - a gratuitous descriptor difference in the one file whose contract is
     * that its signatures match. Nothing outside the routing composables should call this.
     */
    fun reportMissingModalRenderer() {
        if (reportedMissingRenderer) return
        reportedMissingRenderer = true
        diagnostics?.invoke(
            "Heavyweight overlays are enabled but no modal renderer is registered - dialogs will " +
                "render behind the browser surface. The BossOverlayHost being read is probably not " +
                "the host's copy.",
        )
    }
}
