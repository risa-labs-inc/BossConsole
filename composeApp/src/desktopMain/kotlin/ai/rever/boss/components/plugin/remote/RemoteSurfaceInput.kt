package ai.rever.boss.components.plugin.remote

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.ui.sdk.ScrollCoalescer
import ai.rever.boss.ui.sdk.ScrollOffset
import ai.rever.boss.ui.sdk.WidgetEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The two input families a remote surface raises for itself rather than for one of its widgets:
 * unclaimed key presses, and coalesced scrolling.
 *
 * Both were mapped onto the wire by #48 and emitted by nobody, each deferred for a policy decision.
 * The decisions are documented on [toForwardedKey] and in
 * [ScrollCoalescer][ai.rever.boss.ui.sdk.ScrollCoalescer]; this file is where they meet Compose.
 */

/**
 * Forward keys the host did not want to the plugin behind this surface, **without ever consuming one**.
 *
 * ## The policy: the host keymap wins, the plugin gets what is left
 *
 * An out-of-process plugin must not be able to swallow `Cmd+W` and trap the user in a panel. Three
 * things make that impossible here, and only the second is code in this function:
 *
 * 1. **The host keymap runs strictly upstream, at the AWT layer.**
 *    [AWTKeyboardInterceptor][ai.rever.boss.window.AWTKeyboardInterceptor] installs a
 *    `KeyEventDispatcher` on the `KeyboardFocusManager`, which sees every key *before* AWT routes it
 *    to the focused component — i.e. before Compose exists as far as the event is concerned. When a
 *    binding matches and its action dispatches, the interceptor calls `event.consume()` and returns
 *    `true`, and the event is never delivered onward. So a key that reaches a Compose modifier is, by
 *    construction, one the host keymap already declined; `false // Let event propagate normally` at
 *    the end of that dispatcher is the exact point this tap sits after. Nothing needed to change there
 *    — and deliberately so, because the interceptor cannot answer "which remote surface has focus"
 *    (it routes by AWT window, and remote surfaces are not placed in a window yet), while Compose's
 *    own focus system answers it for free.
 *
 * 2. **The tap re-checks the live keymap anyway.** The interceptor has early exits that skip matching
 *    entirely — an unregistered focused window returns before it consults the keymap — and this
 *    modifier would happily forward a `Cmd+T` that arrived down one of those paths. Consulting
 *    [KeymapMatcher] makes "the host keymap wins" a property of the forwarding rule itself rather than
 *    an emergent property of dispatch order, and it is what makes the rule testable without driving
 *    AWT.
 *
 *    **It is not the same code path, and the two have already drifted — see #52.**
 *    `AWTKeyboardInterceptor.findMatchingBinding` takes a [KeymapMatcher] and then ignores it,
 *    reimplementing the match over AWT key codes *without* normalizing the binding's key name, while
 *    `KeymapMatcher.keyMatches` normalizes both sides. So they disagree today, on shipped bindings:
 *    `Cmd+DirectionLeft` and friends (`PANEL_NAVIGATE_*`), `Ctrl+Space` (`QUICK_SWITCHER_OPEN`), and
 *    anything a user binds as `Return`, `Escape`, `-`, `=` or `/`.
 *
 *    The asymmetry matters. Interceptor-claims-but-matcher-does-not is harmless: guarantee 1 already
 *    consumed the key, so the forward never happens. The reverse — matcher claims, interceptor does
 *    not — declines a key the host then fails to dispatch, and it is dead to both sides. That is the
 *    `Shift+/` failure below reached by a different route, and those bindings are *already* dead
 *    app-wide because of the interceptor's own bug; this tap only stops them falling through to a
 *    plugin. The fix is one change — make `findMatchingBinding` use its `matcher` parameter — and it
 *    repairs the host-side bug at the same time, which is why it is #52 and not a patch here.
 *
 *    A second, narrower case with the same shape: the interceptor consumes only when `dispatchAction`
 *    returns `true`, so a binding for a `plugin.*` action whose plugin is disabled matches, declines,
 *    and is not consumed — and is then refused here. "Would actually dispatch" is the best question
 *    this side of the boundary can ask, not a complete one; only trying it answers it.
 *
 * 3. **This handler always returns `false`.** It is a tap, not a handler: the key continues to
 *    whatever the host has above the surface exactly as if the plugin were not there. A plugin can
 *    *observe* what is left over; it can never claim it. Even a plugin process that hangs cannot
 *    delay a host shortcut, because the shortcut never entered this path.
 *
 * ## What gets forwarded, and what makes the surface eligible at all
 *
 * Keys the *focused widget* did not take, because `onKeyEvent` fires on the way up from the focus
 * target: typing into a remote text field produces `TextChange`, not a `Key` per character, and only
 * what the field ignores bubbles this far. That is the same "host first, then the specific thing, then
 * the surface" ordering one level down.
 *
 * **The surface is deliberately not a focus target of its own.** An earlier revision ended this chain
 * in `.focusable()`, which made a surface with no interactive content a Tab stop that began streaming
 * every unclaimed key-down to a separate OS process — with no plugin identity, no capability model and
 * nothing visible in the UI. Reviewers were unanimous that that is the wrong default to ship, and the
 * directions are not symmetric: granting it later behind a declared `wants_keys` on `UIRegistration` is
 * additive, whereas revoking it after a plugin has shipped against it is a negotiation. So a surface
 * receives keys only once something inside it holds focus — which is every surface built for
 * interaction, and none of the ones with no business seeing keystrokes.
 *
 * ## Cost
 *
 * A held key auto-repeats at ~25-30/s and every one of those runs a [KeymapMatcher.match]. The matcher
 * is at least built once per keymap rather than once per keystroke, and reading the settings during
 * composition also means a rebind takes effect without the surface being re-created — but to be honest
 * about what that bought: `KeymapMatcher` has no `init`, so hoisting it saves one wrapper allocation.
 * The real cost is inside `match()` — a filtered candidate list built once or twice per call, plus
 * `Key.toString()` and two name normalizations per candidate — and it is still paid per key-down.
 * Fixing it means precomputing the per-context binding lists in the matcher, which also speeds up the
 * interceptor's far hotter path; that is part of #52 rather than a change to make from here.
 *
 * @param onEvent the surface's event sink. Key events are tagged with an **empty node id** — they
 *   reach the surface precisely *because* no node claimed them, so attributing one would be a guess.
 *   Same convention as lifecycle, per `EmittedEvent`.
 * @param hostKeymap the keymap to check against; the live user settings in production, injected in
 *   tests so an assertion about a shortcut does not depend on whoever's `~/.boss/keymap-settings.json`
 *   the suite happens to run beside.
 * @param context which bindings count as the host's. [ShortcutContext.GLOBAL] is right for every
 *   surface today and is what the interceptor derives for one, but the interceptor resolves context
 *   *per window* — so once remote surfaces are placed, a panel inside a browser or terminal window will
 *   have the interceptor matching in `BROWSER`/`TERMINAL` while this still says `GLOBAL`. A parameter
 *   now so that change has a seam rather than a silent mismatch to find later.
 */
@Composable
internal fun Modifier.forwardUnclaimedKeys(
    onEvent: (nodeId: String, event: WidgetEvent) -> Unit,
    hostKeymap: KeymapSettings = KeymapSettingsManager.currentSettings.collectAsState().value,
    context: ShortcutContext = ShortcutContext.GLOBAL,
): Modifier {
    val matcher = remember(hostKeymap) { KeymapMatcher(hostKeymap) }
    return this
        .onKeyEvent { keyEvent ->
            keyEvent.toForwardedKey(matcher, context)?.let { forwarded -> onEvent("", forwarded) }
            // Never true. See point 3 above — this is the anti-trap guarantee, and it is one word.
            false
        }
}

/**
 * The `Key` event a plugin should see for this key press, or `null` if it must not be forwarded.
 *
 * Three refusals:
 *
 * - **Anything that is not a key *down*.** `KeyEvent` on the wire has a key code and modifiers and no
 *   up/down discriminator, so forwarding both edges would deliver every press twice with no way for a
 *   plugin to tell them apart. Down is the edge that means "the user pressed this".
 * - **A modifier pressed on its own.** Holding Shift to type a capital letter would otherwise deliver a
 *   bare `VK_SHIFT` ahead of the letter, and a plugin listening for keys would see two events for one
 *   keystroke. The modifier is not lost — it arrives as a flag on the key it modifies, which is the
 *   only form the wire type can express. Same set the AWT interceptor skips, for the same reason.
 * - **Anything the host would actually act on**, matched in [context] — [ShortcutContext.GLOBAL] for
 *   every surface today, which is what the AWT interceptor derives for one and which also covers
 *   `WORKSPACE` bindings.
 *
 *   "Would act on", not "has a binding for", and the difference is a bug that review caught: the
 *   interceptor returns *before* it consults the keymap unless Meta, Ctrl or Alt is down, so a binding
 *   with no system modifier is never dispatched. The shipped preset has one — `Shift+/` opens the
 *   shortcut sheet — and refusing it here made `?` on a focused remote surface do nothing at all,
 *   claimed by neither side. [KeymapMatcher.hasSystemModifier] is the interceptor's own test for that
 *   and is now shared rather than reimplemented, so the two cannot drift.
 *
 *   Not exact any more, in the safe direction: the interceptor now declines chords it has a
 *   binding for but cannot act on - Cmd+3 in a two-tab panel, tab stepping in a single-tab panel,
 *   panel navigation in a single-panel window. This test still refuses to forward those, so such
 *   a chord reaches nobody. Nothing places a remote surface yet, and closing it means teaching
 *   this the gates dispatchAction applies; see #52.
 *
 *   Not consulted: `PluginShortcutRegistryImpl`'s defaults, which the interceptor checks after the
 *   keymap. They sit behind the same system-modifier early exit, so anything they could claim is
 *   already refused above by having a modifier — with one narrow exception, a plugin default arriving
 *   down the unregistered-window path, where the interceptor would not have dispatched it either.
 *
 * `Key.nativeKeyCode` rather than the packed `Key.keyCode`: the proto field is an `int32` and the
 * documented meaning is a platform key code, which on this host is the AWT `VK_` constant. The packed
 * Compose value would neither fit nor mean anything to a plugin.
 */
internal fun ComposeKeyEvent.toForwardedKey(
    hostKeymap: KeymapMatcher,
    context: ShortcutContext = ShortcutContext.GLOBAL,
): WidgetEvent.Key? {
    // Cheap tests first so the keymap lookup — the only costly one — is skipped for key-ups and for the
    // modifier presses that bracket every chord.
    if (type != KeyEventType.KeyDown || key.nativeKeyCode in MODIFIER_ONLY_KEYS) return null
    val claimedByHost =
        hostKeymap.match(this, context)?.let(hostKeymap::hasSystemModifier) == true
    return if (claimedByHost) {
        null
    } else {
        WidgetEvent.Key(
            keyCode = key.nativeKeyCode,
            ctrl = isCtrlPressed,
            alt = isAltPressed,
            shift = isShiftPressed,
            meta = isMetaPressed,
        )
    }
}

/**
 * Keys that only ever qualify another key.
 *
 * A superset of `AWTKeyboardInterceptor.isModifierOnlyKey`, which lacks `VK_ALT_GRAPH`. Kept as its own
 * list rather than shared with the interceptor because the two answer different questions — the
 * interceptor asks "can this be a shortcut", this asks "is this an event a plugin wants" — and the
 * answers only mostly coincide.
 */
private val MODIFIER_ONLY_KEYS =
    setOf(
        AwtKeyEvent.VK_SHIFT,
        AwtKeyEvent.VK_CONTROL,
        AwtKeyEvent.VK_ALT,
        AwtKeyEvent.VK_ALT_GRAPH,
        AwtKeyEvent.VK_META,
        AwtKeyEvent.VK_CAPS_LOCK,
        AwtKeyEvent.VK_NUM_LOCK,
        AwtKeyEvent.VK_SCROLL_LOCK,
    )

/**
 * Report [scrollState]'s movement to the plugin as coalesced deltas.
 *
 * The whole policy is in [ScrollCoalescer] — one event per window, and the resting position always
 * delivered — so that it is testable without a UI toolkit and so the Rust renderer has one spec to
 * mirror. This is only the wiring: a `snapshotFlow` over the offset, which already emits at most once
 * per frame, feeding the coalescer.
 *
 * Keyed on the node id so a tree update that replaces the node restarts the reporter against its new
 * state instead of publishing another node's offsets, and scoped to the composition so a closed
 * surface leaves nothing running.
 */
@Composable
internal fun ReportScrollPosition(
    nodeId: String,
    scrollState: ScrollState,
    onEvent: (nodeId: String, event: WidgetEvent) -> Unit,
) {
    // rememberUpdatedState so the long-lived effect always calls the current sink. Harmless today —
    // every caller passes a lambda capturing only its component — but it is the classic stale-capture
    // shape, and the effect outlives many recompositions.
    val sink by rememberUpdatedState(onEvent)
    LaunchedEffect(nodeId, scrollState) {
        ScrollCoalescer
            .coalesce(snapshotFlow { ScrollOffset(x = 0f, y = scrollState.value.toFloat()) })
            .collect { scroll -> sink(nodeId, scroll) }
    }
}
