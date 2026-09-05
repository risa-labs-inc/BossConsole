package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.utils.SystemUtils
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * [KeymapMatcher] against bindings that carry [KeyBinding.alternateKeystrokes].
 *
 * `alternateKeystrokes` was modelled on `KeyBinding` from the start, with helpers, serialization
 * and `allKeystrokes`, and was consulted by neither matcher: every alternate the model could
 * express was silently ignored. The AWT path has its own coverage; this pins the Compose-side
 * matcher, which is what the shortcut-test dialog and `getMatchingBindings` read.
 *
 * Events are built with Compose's own `KeyEvent(...)` factory, which is `@InternalComposeUiApi`,
 * following [ai.rever.boss.components.plugin.remote.RemoteSurfaceKeyRoutingTest]. If a Compose
 * upgrade removes the factory this fails to compile, which is the right failure.
 */
@OptIn(InternalComposeUiApi::class)
class KeymapMatcherAlternatesTest {
    private fun event(
        key: Key,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): ComposeKeyEvent =
        ComposeKeyEvent(
            key = key,
            type = KeyEventType.KeyDown,
            // The presets speak in "Cmd", which the matcher maps to Meta on macOS and Ctrl
            // elsewhere. Press whichever this platform's Cmd really is so the assertions below
            // are about alternates rather than about platform mapping.
            isMetaPressed = if (SystemUtils.isMacOS) meta else false,
            isCtrlPressed = if (SystemUtils.isMacOS) ctrl else (meta || ctrl),
            isShiftPressed = shift,
            isAltPressed = alt,
        )

    private fun matcherFor(vararg bindings: KeyBinding): KeymapMatcher {
        // Block body, not an expression: as a one-liner it exceeds detekt's 120 chars, and
        // wrapped it trips ktlint's "fits on the same line". This satisfies both.
        return KeymapMatcher.from(KeymapSettings.fromBindings(bindings.toList()))
    }

    private val zoomIn =
        KeyBinding(
            actionId = KeymapActions.BROWSER_ZOOM_IN,
            key = "Equals",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("Equals", listOf("Cmd", "Shift"))),
            context = ShortcutContext.BROWSER,
        )

    @Test
    fun `the primary keystroke matches`() {
        val match = matcherFor(zoomIn).match(event(Key.Equals, meta = true), ShortcutContext.BROWSER)

        assertEquals(KeymapActions.BROWSER_ZOOM_IN, match?.actionId)
    }

    @Test
    fun `an alternate keystroke matches`() {
        // Cmd+Shift+Equals is what a US keyboard reports for "Cmd+Plus". Before alternates were
        // consulted this returned null and zoom in simply did not respond to it.
        val match = matcherFor(zoomIn).match(event(Key.Equals, meta = true, shift = true), ShortcutContext.BROWSER)

        assertEquals(KeymapActions.BROWSER_ZOOM_IN, match?.actionId)
    }

    @Test
    fun `a chord that is neither primary nor alternate does not match`() {
        // Guards against the fix over-matching by ignoring modifiers: Cmd+Alt+Equals is neither.
        assertNull(matcherFor(zoomIn).match(event(Key.Equals, meta = true, alt = true), ShortcutContext.BROWSER))
        assertNull(matcherFor(zoomIn).match(event(Key.Minus, meta = true), ShortcutContext.BROWSER))
        assertNull(matcherFor(zoomIn).match(event(Key.Equals), ShortcutContext.BROWSER))
    }

    @Test
    fun `a disabled binding matches on neither primary nor alternate`() {
        val disabled = zoomIn.copy(enabled = false)
        val matcher = matcherFor(disabled)

        assertNull(matcher.match(event(Key.Equals, meta = true), ShortcutContext.BROWSER))
        assertNull(matcher.match(event(Key.Equals, meta = true, shift = true), ShortcutContext.BROWSER))
    }

    @Test
    fun `getMatchingBindings reports a binding matched through its alternate`() {
        // What the Shortcuts settings screen's shortcut tester reads.
        val matches =
            matcherFor(zoomIn).matchAll(event(Key.Equals, meta = true, shift = true), ShortcutContext.BROWSER)

        assertEquals(listOf(KeymapActions.BROWSER_ZOOM_IN), matches.map { it.actionId })
    }

    @Test
    fun `the shipped BOSS Default reaches zoom in through Cmd+Shift+Equals`() {
        // End to end against the real preset rather than a hand-built binding, so the wiring
        // between standardBrowserBindings and the matcher is covered too.
        val matcher = KeymapMatcher.from(KeymapPresets.getBOSSDefault())

        val match = matcher.match(event(Key.Equals, meta = true, shift = true), ShortcutContext.BROWSER)

        assertEquals(KeymapActions.BROWSER_ZOOM_IN, match?.actionId)
    }

    @Test
    fun `a dedicated plus key reaches zoom in`() {
        // Compose reports such a key as Key.Plus, which normalized to "Plus" and matched nothing:
        // every preset spells zoom in "Equals". The AWT path folds VK_PLUS onto Equals, so
        // without the same fold here the Shortcuts tester reported "no match" for a chord that
        // really fires - the bracket divergence one key over.
        val matcher = KeymapMatcher.from(KeymapPresets.getBOSSDefault())

        assertEquals(
            KeymapActions.BROWSER_ZOOM_IN,
            matcher.match(event(Key.Plus, meta = true), ShortcutContext.BROWSER)?.actionId,
        )
        assertEquals(
            KeymapActions.BROWSER_ZOOM_IN,
            matcher.match(event(Key.Plus, meta = true, shift = true), ShortcutContext.BROWSER)?.actionId,
        )
    }

    @Test
    fun `folding plus onto equals does not swallow the neighbouring keys`() {
        // Guard against the fold over-reaching: Cmd+Minus is still zoom OUT, and a bare Plus is
        // still nobody's chord.
        val matcher = KeymapMatcher.from(KeymapPresets.getBOSSDefault())

        assertEquals(
            KeymapActions.BROWSER_ZOOM_OUT,
            matcher.match(event(Key.Minus, meta = true), ShortcutContext.BROWSER)?.actionId,
        )
        assertNull(matcher.match(event(Key.Plus), ShortcutContext.BROWSER))
    }

    @Test
    fun `the shipped BOSS Default reaches positional tab stepping through its bracket alternate`() {
        val matcher = KeymapMatcher.from(KeymapPresets.getBOSSDefault())

        assertEquals(
            KeymapActions.TAB_NEXT_POSITIONAL,
            matcher.match(event(Key.RightBracket, meta = true, shift = true), ShortcutContext.GLOBAL)?.actionId,
        )
        assertEquals(
            KeymapActions.TAB_PREVIOUS_POSITIONAL,
            matcher.match(event(Key.LeftBracket, meta = true, shift = true), ShortcutContext.GLOBAL)?.actionId,
        )
    }
}
