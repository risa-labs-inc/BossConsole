package ai.rever.boss.app

import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.plugin.sandbox.notification.ToastDuration
import ai.rever.boss.plugin.sandbox.notification.ToastMessage
import ai.rever.boss.plugin.sandbox.notification.ToastType
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the empty check in [ToastOverlay].
 *
 * Without it the overlay is composed for every loaded plugin for the entire session, because
 * `PluginToastHost` composes its padded `Column` unconditionally and `DefaultPlugin.pluginToastState`
 * is never null. Since a non-focusable AWT window still receives mouse events and there is no
 * portable click-through, that is a permanently dead region of the app - and, being always-on-top, of
 * whatever other application is in front. The guard is one line, and nothing else in the build would
 * notice it going missing.
 */
class ToastOverlayTest {
    @get:Rule
    val rule = createComposeRule()

    private val previousRenderer = OverlayConfig.heavyweightCorner
    private val previousUseHeavyweight = OverlayConfig.useHeavyweightPopups

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun restore() {
        // Each harness run built a scope with live auto-dismiss timers; leaving them running leaks
        // into the rest of the module.
        scopes.forEach { it.cancel() }
        // OverlayConfig is a process-global registry; leaving a fake in it would leak into any
        // other test that routes an overlay.
        OverlayConfig.heavyweightCorner = previousRenderer
        OverlayConfig.useHeavyweightPopups = previousUseHeavyweight
    }

    /**
     * Whether the heavyweight renderer is asked for a window at all, for the given toasts and
     * window-focus state.
     *
     * Presence, not a count: `opened++` would tally COMPOSITIONS, so any extra recomposition of
     * ToastOverlay would break an equality assertion without anything being wrong.
     */
    private fun windowRequestedFor(
        messages: List<String>,
        focused: Boolean = true,
    ): Boolean {
        var requested = false
        OverlayConfig.useHeavyweightPopups = true
        OverlayConfig.heavyweightCorner = { _, _, _, _ ->
            // Recorded, not composed: composing a real Window needs a display.
            requested = true
        }
        val scope = CoroutineScope(Job()).also { scopes += it }
        val state = PluginToastState(scope)
        messages.forEach { title ->
            // INDEFINITE so no auto-dismiss timer can race the assertion.
            state.show(
                ToastMessage(
                    type = ToastType.INFO,
                    title = title,
                    message = title,
                    duration = ToastDuration.INDEFINITE,
                ),
            )
        }

        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalWindowInfo provides FakeWindowInfo(focused),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ToastOverlay(toastState = state)
                }
            }
        }
        rule.waitForIdle()
        return requested
    }

    private class FakeWindowInfo(
        override val isWindowFocused: Boolean,
    ) : WindowInfo

    @Test
    fun `no overlay window is opened when there are no toasts`() {
        assertFalse(
            windowRequestedFor(emptyList()),
            "an empty toast host still measures 32x32 from its own padding, so an unguarded " +
                "overlay holds a click-eating always-on-top window open for the whole session",
        )
    }

    @Test
    fun `an overlay window is opened once there is a toast`() {
        assertTrue(windowRequestedFor(listOf("Something happened")))
    }

    @Test
    fun `no overlay window is opened while the parent window is unfocused`() {
        assertFalse(
            windowRequestedFor(listOf("Plugin Disabled"), focused = false),
            "toast lifetime is unbounded (ToastDuration.INDEFINITE, which the host itself raises), " +
                "so an always-on-top click-eating window over ANOTHER application must not outlive " +
                "the user looking at this one",
        )
    }
}
