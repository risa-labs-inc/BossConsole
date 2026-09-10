package ai.rever.boss.components.plugin.remote

import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.kernel.ui.RemoteUiSurface
import ai.rever.boss.kernel.ui.RemoteUiSurfaceDescriptor
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.kernel.ui.SurfaceRegistration
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.ui.sdk.WidgetEvent
import ai.rever.boss.ui.sdk.WidgetModifier
import ai.rever.boss.ui.sdk.WidgetNode
import ai.rever.boss.ui.sdk.WidgetTree
import ai.rever.boss.ui.sdk.WidgetType
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * `key` and `scroll` emission driven through a real composition, to the surface's outgoing queue.
 *
 * [RemoteSurfaceKeyRoutingTest] pins the routing *rule* and
 * [ScrollCoalescerTest][ai.rever.boss.ui.sdk.ScrollCoalescerTest] pins the throttling *policy*; neither
 * says the renderer is wired to them. These do: a key pressed on a rendered surface, a scroll performed
 * on a rendered surface, and the `UIEvent` that comes out the other side.
 *
 * The third test is the one that matters most for safety. "The plugin cannot trap the user" is not a
 * property of the routing rule — it is the claim that this modifier *never returns true*, and the only
 * way to assert it is to put a host handler above the surface and check the key still gets there.
 */
@OptIn(InternalComposeUiApi::class)
class RemoteSurfaceInputComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a key the host does not claim reaches the plugin with its payload intact`() {
        val registry = RemoteUiSurfaceRegistry()
        // wantsKeys = true: this test is about the routing rule end to end, not about the gate
        // The gate added in front of it - RemoteSurfaceKeyRoutingTest and the wantsKeys-specific
        // tests in this file cover that the gate itself defaults closed and is enforced.
        val surface = registry.accept(PANEL, wantsKeys = true)
        val panel = RemotePanelComponent(PANEL, "Test Panel", PROCESS, registry)
        surface.pushTree(textFieldTree())
        panel.attach()
        assertUnboundInLiveKeymap()

        compose.setContent { panel.Content() }
        // Focus a widget inside the surface, then press a chord it does not want: the key has to travel
        // up out of the focused field to the surface root before anything forwards it, which is the
        // "the focused widget gets first refusal" half of the policy doing its job.
        compose.onNode(hasSetTextAction()).requestFocus()
        compose.onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F7) } }
        }
        compose.waitForIdle()

        val queued = surface.firstEventWhere { it.hasKey() }
        assertEquals(PANEL, queued.surfaceId)
        // Surface-level: no widget claimed it, so there is no node to attribute it to.
        assertEquals("", queued.targetNodeId)
        assertEquals(AwtKeyEvent.VK_F7, queued.key.keyCode)
        assertTrue(queued.key.alt, "the alt modifier must survive the crossing")
        assertTrue(queued.key.shift, "the shift modifier must survive the crossing")
        panel.dispose()
    }

    @Test
    fun `wants_keys defaults to false, and no key reaches a plugin that never declared it`() {
        // The mirror of the test above, with the one thing changed being whether the
        // surface declared wants_keys - not the widget tree, not the key, not the routing rule. Same
        // interactive field, same focus, same unbound chord; the only difference from the passing case
        // is the registration this test does NOT opt into.
        val registry = RemoteUiSurfaceRegistry()
        val surface = registry.accept(PANEL) // wantsKeys defaults to false - the point of this test.
        val panel = RemotePanelComponent(PANEL, "Test Panel", PROCESS, registry)
        surface.pushTree(textFieldTree())
        panel.attach()
        assertUnboundInLiveKeymap()

        compose.setContent { panel.Content() }
        compose.onNode(hasSetTextAction()).requestFocus()
        compose.onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F7) } }
        }
        compose.waitForIdle()

        assertNoKeyReachesThePlugin(surface)
        panel.dispose()
    }

    @Test
    fun `an opted out replacement rejects keys from the still composed opted in renderer`() {
        val registry = RemoteUiSurfaceRegistry()
        val original = registry.accept(PANEL, wantsKeys = true)
        val panel = RemotePanelComponent(PANEL, "Test Panel", PROCESS, registry)
        original.pushTree(textFieldTree())
        panel.attach()
        assertUnboundInLiveKeymap()
        compose.setContent { panel.Content() }
        compose.onNode(hasSetTextAction()).requestFocus()

        // Reclaim a registration that has not opened its stream. No tree or connection callback
        // has refreshed the existing composition, but events now route to the replacement queue.
        val replacement = registry.accept(PANEL)
        compose.onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F7) } }
        }
        compose.waitForIdle()
        assertNoKeyReachesThePlugin(replacement)
        panel.dispose()
    }

    @Test
    fun `a component attached before registration follows an opted in reconnect`() {
        val registry = RemoteUiSurfaceRegistry()
        val panel = RemotePanelComponent(PANEL, "Test Panel", PROCESS, registry)
        panel.attach()
        compose.setContent { panel.Content() }
        val original = registry.accept(PANEL)
        original.pushTree(textFieldTree())
        registry.openStream(PANEL, PROCESS)
        registry.closeStream(original)

        val replacement = registry.accept(PANEL, wantsKeys = true)
        replacement.pushTree(textFieldTree())
        registry.openStream(PANEL, PROCESS)
        compose.waitForIdle()
        assertUnboundInLiveKeymap()
        compose.onNode(hasSetTextAction()).requestFocus()
        compose.onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F7) } }
        }
        compose.waitForIdle()
        assertEquals(AwtKeyEvent.VK_F7, replacement.firstEventWhere { it.hasKey() }.key.keyCode)
        panel.dispose()
    }

    @Test
    fun `a key the focused widget consumes never reaches the plugin`() {
        // "The focused widget gets first refusal" is the property that keeps ordinary typing off the
        // wire — a text field consumes its character keys, so a plugin sees one TextChange per edit
        // rather than a Key per keystroke — and it is the whole reason the tap is `onKeyEvent` (which
        // fires on the way *up* from the focus target) rather than `onPreviewKeyEvent`. Asserted with a
        // child that consumes explicitly, because Compose's key injection cannot synthesise the codepoint
        // a real `BasicTextField` needs in order to treat a press as text: driving a field here would
        // assert nothing, which the mutation check caught before this comment existed.
        val forwarded = mutableListOf<WidgetEvent>()
        val focus = FocusRequester()
        compose.setContent {
            Box(Modifier.forwardUnclaimedKeys({ _, event -> forwarded += event })) {
                Box(
                    Modifier
                        .onKeyEvent { it.key == Key.H }
                        .focusRequester(focus)
                        .focusable(),
                )
            }
        }
        compose.runOnIdle { focus.requestFocus() }

        compose.onRoot().performKeyInput {
            pressKey(Key.H)
            pressKey(Key.J)
        }
        compose.waitForIdle()

        assertEquals(
            listOf(AwtKeyEvent.VK_J),
            forwarded.filterIsInstance<WidgetEvent.Key>().map { it.keyCode },
            "the consumed key must not reach the plugin; the unconsumed one must",
        )
    }

    @Test
    fun `a key the host keymap claims is not forwarded`() {
        // Driven against an injected keymap rather than the renderer's live one, so the assertion does
        // not depend on whatever ~/.boss/keymap-settings.json the suite runs beside.
        val forwarded = mutableListOf<WidgetEvent>()
        val focus = FocusRequester()
        compose.setContent {
            Box(
                Modifier
                    .forwardUnclaimedKeys({ _, event -> forwarded += event }, hostKeymap = keymapBinding("F7"))
                    .focusRequester(focus)
                    .focusable(),
            )
        }
        compose.runOnIdle { focus.requestFocus() }

        compose.onRoot().performKeyInput { withKeyDown(primaryModifier()) { pressKey(Key.F7) } }
        compose.onRoot().performKeyInput { withKeyDown(primaryModifier()) { pressKey(Key.F8) } }
        compose.waitForIdle()

        // F7 is bound and must be swallowed *for the plugin*; F8 is not and must get through. Asserting
        // both in one composition is what rules out "nothing was forwarded because nothing worked".
        assertEquals(
            listOf(AwtKeyEvent.VK_F8),
            forwarded.filterIsInstance<WidgetEvent.Key>().map { it.keyCode },
        )
    }

    @Test
    fun `the surface never consumes a key — a host handler above it still receives one`() {
        // The anti-trap guarantee, and the only place it is observable. If the tap ever returned true, a
        // plugin could hold onto every key that reached its surface and the user would have no way out
        // of the panel. Flip the `false` in forwardUnclaimedKeys and this is the test that goes red.
        val forwarded = mutableListOf<WidgetEvent>()
        var reachedHost = false
        val focus = FocusRequester()
        compose.setContent {
            Box(
                Modifier.onKeyEvent {
                    reachedHost = true
                    false
                },
            ) {
                Box(
                    Modifier
                        .forwardUnclaimedKeys({ _, event -> forwarded += event })
                        .focusRequester(focus)
                        .focusable(),
                )
            }
        }
        compose.runOnIdle { focus.requestFocus() }

        compose.onRoot().performKeyInput { pressKey(Key.F7) }
        compose.waitForIdle()

        assertTrue(forwarded.any { it is WidgetEvent.Key }, "the plugin should have seen the key")
        assertTrue(reachedHost, "the host handler above the surface must still receive the key")
    }

    @Test
    fun `scrolling a rendered scroll node puts a coalesced scroll on the wire`() {
        val registry = RemoteUiSurfaceRegistry()
        val surface = registry.accept(PANEL)
        val panel = RemotePanelComponent(PANEL, "Test Panel", PROCESS, registry)
        surface.pushTree(scrollTree())
        panel.attach()

        compose.setContent { panel.Content() }
        // Scrolls the enclosing scrollable until the row is visible — a real offset change, driven the
        // way a user drives one, rather than by poking a ScrollState the renderer owns.
        compose.onNodeWithText("row-19").performScrollTo()
        compose.waitForIdle()

        val queued = surface.firstEvent()
        assertEquals(PANEL, queued.surfaceId)
        assertEquals(SCROLL, queued.targetNodeId, "a scroll belongs to the node that scrolled")
        assertTrue(queued.hasScroll(), "expected a scroll event, got ${queued.eventCase}")
        assertTrue(queued.scroll.deltaY > 0f, "scrolling down must report a positive delta")
        panel.dispose()
    }

    /**
     * Fail loudly if the environment's keymap claims the chord the forwarding test relies on.
     *
     * `Alt+Shift+F7` is unbound in every shipped preset. A developer who has bound it locally should see
     * an explicit message here rather than a mystifying "no event arrived".
     */
    private fun assertUnboundInLiveKeymap() {
        val live = KeymapSettingsManager.currentSettings.value
        val chord = ComposeKeyEvent(Key.F7, KeyEventType.KeyDown, isAltPressed = true, isShiftPressed = true)
        assertNull(
            KeymapMatcher(live).match(chord, ShortcutContext.GLOBAL),
            "this test needs Alt+Shift+F7 to be unbound; the active keymap claims it",
        )
    }

    /** The platform's "Cmd": `KeymapMatcher` maps a `Cmd` binding onto Meta on macOS and Ctrl elsewhere. */
    private fun primaryModifier(): Key = if (SystemUtils.isMacOS) Key.MetaLeft else Key.CtrlLeft

    private fun keymapBinding(key: String): KeymapSettings {
        val binding = KeyBinding(actionId = "test.action", key = key, modifiers = listOf("Cmd"))
        return KeymapSettings(shortcuts = mapOf(binding.actionId to binding))
    }

    private fun RemoteUiSurfaceRegistry.accept(
        surfaceId: String,
        wantsKeys: Boolean = false,
    ): RemoteUiSurface =
        (
            register(surfaceId, PROCESS, RemoteUiSurfaceDescriptor(wantsKeys = wantsKeys))
                as SurfaceRegistration.Accepted
        ).surface

    /** Bounded, so a regression fails the build instead of hanging it. Mirrors RemoteWidgetRendererComposeTest. */
    private fun RemoteUiSurface.firstEvent(): UIEvent = firstEventWhere { true }

    /**
     * The first queued event matching [predicate].
     *
     * Filtered rather than positional because focusing a widget legitimately queues a `Focus` event
     * first, and a test about keys should not break when an unrelated family starts being emitted.
     */
    private fun RemoteUiSurface.firstEventWhere(predicate: (UIEvent) -> Boolean): UIEvent =
        runBlocking {
            withTimeout(WAIT_TIMEOUT_MS) { events().filter(predicate).take(1).toList() }.single()
        }

    /**
     * Bounded wait proving a Key event never arrives - a much shorter budget than
     * [WAIT_TIMEOUT_MS], since this test asserts an absence rather than waiting out a real event,
     * and a slow negative test is still a real one to keep the suite fast.
     */
    private fun assertNoKeyReachesThePlugin(surface: RemoteUiSurface) {
        runBlocking {
            val arrived =
                withTimeoutOrNull(NEGATIVE_WAIT_TIMEOUT_MS) {
                    surface
                        .events()
                        .filter { it.hasKey() }
                        .take(1)
                        .toList()
                }
            assertNull(arrived, "no Key event should reach a plugin that never declared wants_keys")
        }
    }

    private fun textFieldTree(): WidgetTree =
        WidgetTree(
            rootId = "field",
            nodes = mapOf("field" to WidgetNode("field", WidgetType.TEXT_FIELD, properties = mapOf("value" to ""))),
        )

    /** A short scroll viewport over enough rows that scrolling to the last one has to move the offset. */
    private fun scrollTree(): WidgetTree {
        val rows =
            (0 until ROWS).map { index ->
                WidgetNode("row-$index", WidgetType.TEXT, mapOf("value" to "row-$index"))
            }
        val scroll =
            WidgetNode(
                id = SCROLL,
                type = WidgetType.SCROLL,
                childIds = rows.map { it.id },
                modifier = WidgetModifier(height = VIEWPORT_DP),
            )
        return WidgetTree(rootId = SCROLL, nodes = (rows + scroll).associateBy { it.id })
    }

    private companion object {
        const val PANEL = "panel-input"
        const val PROCESS = "plugin-a"
        const val SCROLL = "scroll-1"
        const val ROWS = 20
        const val VIEWPORT_DP = 40
        const val WAIT_TIMEOUT_MS = 10_000L
        const val NEGATIVE_WAIT_TIMEOUT_MS = 500L
    }
}
