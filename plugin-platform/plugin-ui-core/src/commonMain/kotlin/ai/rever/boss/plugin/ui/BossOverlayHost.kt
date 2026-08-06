package ai.rever.boss.plugin.ui

import androidx.compose.runtime.Composable

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
 * jar - that is what makes them reachable at all there, and it is why `minApiVersion` rather than
 * `minBossVersion` is the gate a plugin should declare (see ApiClassLoader's own doc: brand-new types
 * ship via the jar, member additions to host-compiled types do not). On that path the api jar's
 * bodies really do execute, with nothing having injected a renderer, so they must degrade cleanly:
 * `BossDialog` falls back to a plain Compose `Dialog`, which is the pre-fix behaviour rather than a
 * crash. Their layout constants differ from the host's only because that package predates the
 * design-system tokens.
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
     */
    @Volatile
    var modalRenderer: (
        @Composable (
            onDismissRequest: () -> Unit,
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
