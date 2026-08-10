package ai.rever.boss.app

import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.DpSize
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two guards on [FocusModeQuickActions], and the identity its sign-out hint carries.
 *
 * Both guards are one line and nothing else in the build would notice either going missing - but on
 * the heavyweight path this composable opens a non-focusable always-on-top AWT window, and the JVM
 * has no portable click-through. One composed while the top bar is showing is a dead click region
 * over live content; one left up while BOSS is in the background is a dead click region over
 * whatever the user switched to. `ToastOverlayTest` pins the same two lines in the toast overlay for
 * the same reasons.
 */
class FocusModeQuickActionsTest {
    @get:Rule
    val rule = createComposeRule()

    private val previousRenderer = OverlayConfig.heavyweightCorner
    private val previousUseHeavyweight = OverlayConfig.useHeavyweightPopups

    @After
    fun restore() {
        // OverlayConfig is a process-global registry; leaving a fake in it would leak into any
        // other test that routes an overlay.
        OverlayConfig.heavyweightCorner = previousRenderer
        OverlayConfig.useHeavyweightPopups = previousUseHeavyweight
    }

    private class FakeWindowInfo(
        override val isWindowFocused: Boolean,
    ) : WindowInfo

    /**
     * Mount the cluster and report whether the heavyweight renderer was asked for a window.
     *
     * Presence, not a count: a tally would count COMPOSITIONS, so any extra recomposition would
     * break an equality assertion without anything being wrong.
     */
    private fun windowRequestedFor(
        visible: Boolean,
        focused: Boolean = true,
        heavyweight: Boolean = true,
    ): Boolean {
        var requested = false
        OverlayConfig.useHeavyweightPopups = heavyweight
        OverlayConfig.heavyweightCorner = { _, _, _, _ ->
            // Recorded, not composed: composing a real Window needs a display.
            requested = true
        }
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides heavyweight,
                LocalWindowInfo provides FakeWindowInfo(focused),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FocusModeQuickActions(
                        visible = visible,
                        inset = DpSize.Zero,
                        onShowSettings = {},
                        onShowSearch = {},
                        onSignOut = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        return requested
    }

    @Test
    fun `nothing is composed while the top bar is showing`() {
        assertFalse(windowRequestedFor(visible = false))
        rule.onAllNodesWithTag(FOCUS_QUICK_ACTIONS_TAG).assertCountEquals(0)
    }

    @Test
    fun `the cluster appears once the top bar is hidden`() {
        assertTrue(windowRequestedFor(visible = true))
    }

    @Test
    fun `no overlay window is opened while the parent window is unfocused`() {
        assertFalse(
            windowRequestedFor(visible = true, focused = false),
            "the overlay is always-on-top over every other application, so one held open while " +
                "BOSS is in the background is a dead click region in whatever the user switched to",
        )
    }

    @Test
    fun `an unfocused window still draws the cluster in place`() {
        // Falling back rather than vanishing: the cluster is part of the chrome while the top bar
        // is cleared, and disappearing whenever the window loses focus would read as a bug.
        windowRequestedFor(visible = true, focused = false)

        rule.onAllNodesWithTag(FOCUS_QUICK_ACTIONS_TAG).assertCountEquals(1)
    }

    @Test
    fun `the sign-out hint names the signed-in account`() {
        // The cluster is icon-only, so the hint is the only place the address the top bar prints
        // next to this button still appears - and which account is about to be signed out is worth
        // confirming before the click, not after.
        assertEquals("Sign out - operator@example.com", signOutHint("operator@example.com"))
    }

    @Test
    fun `the sign-out hint falls back when there is no account`() {
        assertEquals("Sign out of your account", signOutHint(null))
        // Blank is not an identity. An empty string reaching the hint would render "Sign out - "
        // and read as a truncation bug rather than as a signed-out state.
        assertEquals("Sign out of your account", signOutHint("   "))
    }
}
