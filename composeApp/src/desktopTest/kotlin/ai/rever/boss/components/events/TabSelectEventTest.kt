package ai.rever.boss.components.events

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Cross-window tab selection reaches the target window through [TabEventBus.tabSelectEvents],
 * so the bus carries the two properties the window's effect relies on: it is addressed to
 * exactly one window (via targetWindowId + panelId + tabId), and it never replays.
 *
 * These tests pin the bus contract itself. The window filter that ships in
 * `BossAppEventBusEffects` (`.filter { it.targetWindowId == windowId }`) is not exercised
 * here: each collector below applies the same predicate on its own, so a broken filter on
 * the effect side would not fail these tests.
 */
class TabSelectEventTest {
    @AfterTest
    fun `reset the process-wide ipcBridge`() {
        // ipcBridge is a singleton: a bridge left behind by another test would make
        // selectTab start calling into it from here.
        TabEventBus.ipcBridge = null
    }

    @Test
    fun `only the addressed window sees the tab selection`() =
        runTest {
            val mine = "window-addressed"
            val theirs = "window-bystander"

            val received =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        TabEventBus.tabSelectEvents.first { it.targetWindowId == mine }
                    }
                }
            val bystander =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        TabEventBus.tabSelectEvents.first { it.targetWindowId == theirs }
                    }
                }
            // Barrier: with replay = 0, an emit before a collector has subscribed is dropped
            // without a trace. yield() lets the test scheduler run both async collectors up
            // to their first suspension - subscribed, waiting in `first` - before the emit.
            yield()

            TabEventBus.selectTab(mine, "panel-1", "tab-42", sourceWindowId = "window-origin")

            val event = assertNotNull(received.await())
            assertEquals("panel-1", event.panelId)
            assertEquals("tab-42", event.tabId)
            assertNull(bystander.await(), "a selection for one window must not select a tab in another")
        }

    /**
     * The flow has no replay - and must not gain one. A replayed selection would re-select the
     * tab in every window opened afterwards, yanking focus on unrelated windows at startup.
     */
    @Test
    fun `a window that opens later does not replay someone else's selection`() =
        runTest {
            val window = "window-late-collector"

            // First prove the selection is delivered to a live window; the no-replay
            // assertion below is only meaningful for an event that actually went somewhere.
            val live =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        TabEventBus.tabSelectEvents.first { it.targetWindowId == window }
                    }
                }
            // Barrier: the live collector must be subscribed before the emit (see test 1).
            yield()

            TabEventBus.selectTab(window, "panel-1", "tab-42", sourceWindowId = "window-origin")
            assertNotNull(live.await(), "the live window must receive its own selection")

            // Only after delivery: a window subscribing later must not see the selection again.
            val late =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        TabEventBus.tabSelectEvents.first { it.targetWindowId == window }
                    }
                }
            // Barrier: the late collector must actually be subscribed when it sees nothing -
            // an unsubscribed collector would time out to null for the wrong reason.
            yield()

            assertNull(late.await(), "the selection was already delivered - a late window must not repeat it")
        }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
