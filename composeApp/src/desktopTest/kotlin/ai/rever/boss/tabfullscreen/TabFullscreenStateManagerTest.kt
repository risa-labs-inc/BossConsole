package ai.rever.boss.tabfullscreen

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contract of [TabFullscreenStateManager] - the handshake that restores JxBrowser
 * rendering after a tab leaves fullscreen.
 *
 * JxBrowser allows only one active rendering surface per browser instance, so once the
 * fullscreen window closes, the BrowserViewState the tab was rendering into is dead: only
 * building a fresh one re-establishes the rendering pipeline. The handshake is that
 * [TabFullscreenStateManager.enterFullscreen] marks the tab that owns the fullscreen window,
 * [TabFullscreenStateManager.exitFullscreen] clears that mark and flags exactly that tab
 * under [TabFullscreenStateManager.needsViewStateRecreation], and
 * [TabFullscreenStateManager.clearRecreationSignal] silences the flag once FluckTabComponent
 * has rebuilt the view state.
 *
 * These tests pin the invariants that keep the handshake precise and idempotent: the
 * fullscreen mark tracks a single tab (never a stack), the recreation signal names exactly
 * the tab that was fullscreen when exit fired (never a displaced tab, never a phantom when
 * nothing was fullscreen), and a cleared signal stays cleared.
 */
class TabFullscreenStateManagerTest {
    @BeforeTest
    fun resetSingletonState() {
        // The manager is a process-wide singleton with no reset hook, so drive it back to
        // pristine state through the public API alone: exitFullscreen clears the mark (and
        // flags whatever was still fullscreen for recreation), then clearRecreationSignal
        // drops that flag. After both calls both flows are null again.
        TabFullscreenStateManager.exitFullscreen()
        TabFullscreenStateManager.clearRecreationSignal()
    }

    @AfterTest
    fun clearSingletonState() {
        TabFullscreenStateManager.exitFullscreen()
        TabFullscreenStateManager.clearRecreationSignal()
    }

    @Test
    fun `entering fullscreen marks that tab and only that tab`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")

        assertEquals("tab-a", TabFullscreenStateManager.fullscreenTabId.value)
        assertTrue(TabFullscreenStateManager.isTabInFullscreen("tab-a"), "the entering tab must be fullscreen")
        assertFalse(TabFullscreenStateManager.isTabInFullscreen("tab-b"), "no other tab may report fullscreen")
        // Entering hands the existing surface to the fullscreen window rather than killing
        // it, so it must not request a BrowserViewState rebuild.
        assertEquals<String?>(null, TabFullscreenStateManager.needsViewStateRecreation.value)
    }

    @Test
    fun `a second tab entering fullscreen replaces the first rather than accumulating`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")
        TabFullscreenStateManager.enterFullscreen("tab-b")

        assertEquals("tab-b", TabFullscreenStateManager.fullscreenTabId.value)
        assertFalse(TabFullscreenStateManager.isTabInFullscreen("tab-a"), "the displaced tab must lose the mark")
        assertTrue(TabFullscreenStateManager.isTabInFullscreen("tab-b"), "the newest tab must own the mark")
    }

    @Test
    fun `exiting fullscreen clears the fullscreen mark`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")

        TabFullscreenStateManager.exitFullscreen()

        assertEquals<String?>(null, TabFullscreenStateManager.fullscreenTabId.value)
        assertFalse(TabFullscreenStateManager.isTabInFullscreen("tab-a"), "the exited tab must no longer be fullscreen")
    }

    @Test
    fun `exit flags view-state recreation for exactly the tab that was fullscreen`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")

        TabFullscreenStateManager.exitFullscreen()

        // The signal names the one tab whose rendering surface just died, so FluckTab
        // rebuilds a single BrowserViewState rather than every browser it hosts.
        assertEquals("tab-a", TabFullscreenStateManager.needsViewStateRecreation.value)
        // The mark itself is gone: fullscreen is over, only the rebuild request remains.
        assertEquals<String?>(null, TabFullscreenStateManager.fullscreenTabId.value)
    }

    @Test
    fun `clearRecreationSignal silences the request after the tab has recreated`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")
        TabFullscreenStateManager.exitFullscreen()

        TabFullscreenStateManager.clearRecreationSignal()

        assertEquals<String?>(null, TabFullscreenStateManager.needsViewStateRecreation.value)
    }

    @Test
    fun `exiting when nothing was fullscreen requests no recreation`() {
        // No fullscreen window was open, so no rendering surface died: exit must not ask
        // for a gratuitous BrowserViewState rebuild.
        TabFullscreenStateManager.exitFullscreen()

        assertEquals<String?>(null, TabFullscreenStateManager.needsViewStateRecreation.value)
        assertEquals<String?>(null, TabFullscreenStateManager.fullscreenTabId.value)
    }

    @Test
    fun `a second exit preserves an unconsumed recreation signal`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")
        TabFullscreenStateManager.exitFullscreen()

        TabFullscreenStateManager.exitFullscreen()

        assertEquals("tab-a", TabFullscreenStateManager.needsViewStateRecreation.value)
    }

    @Test
    fun `replacing a fullscreen tab leaves no stale recreation signal for the displaced tab`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")
        TabFullscreenStateManager.enterFullscreen("tab-b")

        TabFullscreenStateManager.exitFullscreen()

        // Only the tab that actually owned the fullscreen surface is flagged for
        // recreation; tab-a, displaced before the exit, must not be rebuilt.
        assertEquals("tab-b", TabFullscreenStateManager.needsViewStateRecreation.value)
    }

    @Test
    fun `a double exit does not resurrect a cleared signal`() {
        TabFullscreenStateManager.enterFullscreen("tab-a")
        TabFullscreenStateManager.exitFullscreen()
        TabFullscreenStateManager.clearRecreationSignal()

        TabFullscreenStateManager.exitFullscreen()

        // Nothing was fullscreen at the second exit, so the cleared signal must stay
        // cleared: FluckTab must not see a second rebuild request for the same tab.
        assertEquals<String?>(null, TabFullscreenStateManager.needsViewStateRecreation.value)
        assertEquals<String?>(null, TabFullscreenStateManager.fullscreenTabId.value)
    }
}
