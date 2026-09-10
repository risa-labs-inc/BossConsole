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
    /**
     * Reports (via [diagnostics]) that [name] was written more than once, for the four fields below
     * that are documented "WRITE-ONCE at startup, before any composition" while being plain public
     * `var`s today - so nothing stops a plugin's `BossOverlayHost.useHeavyweightOverlays = false`
     * from silently reinstating the occluded-dialog bug this file exists to fix, or a stray
     * `modalRenderer = null` from taking every heavyweight dialog in the app back to lightweight.
     * Not a new trust boundary - the plugin model is already in-process and cooperative, so this
     * stops an accident, not an attacker - and a custom setter keeps the exact same JVM descriptor
     * (getter/setter signatures are unchanged), so this needs no coordinated host and api release.
     *
     * [openHeavyweightPopups] is deliberately NOT guarded this way: its own KDoc documents it as a
     * running `++`/`--` counter maintained across the lifetime of every heavyweight popup, not a
     * startup registration, and a write-once guard would freeze it at whatever the first popup left
     * it at.
     */
    private fun reportDuplicateWrite(name: String) {
        diagnostics?.invoke(
            "Ignored a write to BossOverlayHost.$name after the host's own startup injection - " +
                "a later registration tried to overwrite a write-once registry field.",
        )
    }

    /** True when modals must escape into heavyweight windows (HARDWARE_ACCELERATED browser). */
    @Volatile
    var useHeavyweightOverlays: Boolean = false
        set(value) {
            // No nullable "unwritten" sentinel for a Boolean, so this needs its own written flag
            // rather than the null-check the two renderers and diagnostics below use.
            // An explicit false registers OFF_SCREEN mode and must lock just like true.
            // Startup injection is serialized; volatile visibility does not make this check atomic.
            if (useHeavyweightOverlaysWritten) {
                reportDuplicateWrite("useHeavyweightOverlays")
                return
            }
            useHeavyweightOverlaysWritten = true
            field = value
        }

    @Volatile
    private var useHeavyweightOverlaysWritten = false

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
        set(value) {
            if (field != null) {
                reportDuplicateWrite("modalRenderer")
                return
            }
            field = value
        }

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
            // In AWT LOGICAL UNITS (dp), not pixels - the host places overlay content in dp.
            anchorInWindow: IntRect,
            anchoring: BossPopupAnchoring,
            offset: IntOffset,
            focusable: Boolean,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null
        set(value) {
            if (field != null) {
                reportDuplicateWrite("popupRenderer")
                return
            }
            field = value
        }

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
     *
     * **HOST-OWNED. Plugins must not write this.** It is public only because the host's popup
     * renderer lives in a different module; a lost decrement from anywhere leaves every host modal
     * unable to dismiss on focus loss.
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
        set(value) {
            if (field != null) {
                reportDuplicateWrite("diagnostics")
                return
            }
            field = value
        }

    /** Reported at most once per process; a per-frame warning would drown the log. */
    @Volatile
    private var reportedMissingRenderer = false

    /** Reported at most once per process, for the same reason as the modal one. */
    @Volatile
    private var reportedMissingPopupRenderer = false

    /** Reported at most once per process, for the same reason as the two renderer ones. */
    @Volatile
    private var reportedUnmeasuredAnchor = false

    /**
     * The [reportMissingModalRenderer] counterpart for popups.
     *
     * A separate function rather than a parameter on that one: this surface is pinned by the
     * binary-compatibility validator in two repos, so changing an existing descriptor costs a
     * coordinated host and api release while adding one is free. Added now for exactly that reason -
     * a popup rendering behind the browser would otherwise produce nothing in the log at all.
     */
    fun reportMissingPopupRenderer() {
        if (reportedMissingPopupRenderer) return
        reportedMissingPopupRenderer = true
        diagnostics?.invoke(
            "Heavyweight overlays are enabled but no popup renderer is registered - menus and " +
                "dropdowns will render behind the browser surface.",
        )
    }

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

    /**
     * Report that an anchored popup never measured, at most once per process.
     *
     * A [BossPopup] with `BossPopupAnchoring.AnchorBounds` waits for `onGloballyPositioned` before it
     * renders anything, so that its overlay window can be placed under the anchoring control. If that
     * callback never fires - an ancestor that measures but never places its subtree, or a subtree
     * composed off-screen - the popup renders nothing, forever, with nothing in the log to say why.
     * A `LaunchedEffect` in `BossPopup` calls this after a short grace period on that path, turning
     * "my dropdown doesn't open" into one diagnosable line. See [reportMissingPopupRenderer], whose
     * once-per-process shape this follows for the same reason.
     *
     * This is a generic once-per-process signal, not a per-call-site diagnosis: a slow first
     * anchor can consume the warning before a later broken popup appears.
     *
     * Host-only addition. The API-repo copy must not call this member without a minimum-host
     * version gate: older hosts resolve their own copy first and do not provide this descriptor.
     * The API copy deliberately omits both this member and its call for now.
     *
     * A separate function rather than a parameter on an existing one: this surface is pinned by the
     * binary-compatibility validator in two repos, so adding a descriptor is free while changing one
     * costs a coordinated host and api release.
     */
    fun reportUnmeasuredAnchor() {
        if (reportedUnmeasuredAnchor) return
        reportedUnmeasuredAnchor = true
        diagnostics?.invoke(
            "A heavyweight popup anchored to its calling layout never received a position - its " +
                "anchor was composed but never placed, so the popup cannot open. Anchor it to the " +
                "cursor, or ensure the anchoring layout is actually placed on screen.",
        )
    }
}
