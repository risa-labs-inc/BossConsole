package ai.rever.boss.components.events

import ai.rever.boss.app.PendingPluginAction
import ai.rever.boss.app.PluginActionApprovalQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam that makes the `boss://plugin` confirmation gate actually ask.
 *
 * The gate's promise is that an externally delivered action is put to the operator. That
 * promise is only kept if the request survives the gap between arriving and a window being
 * there to show it - a gap that is not an edge case but the ordinary cold-start path, where
 * an argv link is processed before `application {}` has built any window at all. These tests
 * pin the retention, the exactly-once claim across windows, and the order a claiming window
 * shows them in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginActionRetentionTest {
    @BeforeTest
    fun clear() {
        PluginActionEventBus.clearForTest()
    }

    @AfterTest
    fun cleanUp() {
        PluginActionEventBus.clearForTest()
    }

    @Test
    fun `a request retained before any window collects is claimed exactly once once one opens`() =
        runTest {
            // Cold start: no window exists, so no collector does either, and the request
            // carries no preferred window id.
            assertTrue(PluginActionEventBus.requestConfirmation("plugin.a", "sync", emptyMap(), sourceWindowId = null))
            assertEquals(1, PluginActionEventBus.pendingCount, "the request must be held, not dropped")

            // The window opens afterwards and starts collecting.
            val claimed = mutableListOf<PluginActionConfirmEvent>()
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { event ->
                    if (shouldClaimPluginAction(event, "window-1", targetWindowOpen = false) &&
                        PluginActionEventBus.claim(event)
                    ) {
                        claimed += event
                    }
                }
            }
            runCurrent()

            assertEquals(1, claimed.size, "the window that opened after the request must still be offered it")
            assertEquals("plugin.a", claimed.single().handlerId)
            assertEquals(0, PluginActionEventBus.pendingCount, "a claimed request must leave the registry")

            // Several re-scan intervals later it has not been offered a second time: a claim
            // removes it, so the periodic rescan cannot re-prompt for the same request.
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 4)
            runCurrent()
            assertEquals(1, claimed.size, "a claimed request must never be offered again")
        }

    @Test
    fun `two windows cannot both claim one request`() =
        runTest {
            val byFirst = mutableListOf<PluginActionConfirmEvent>()
            val bySecond = mutableListOf<PluginActionConfirmEvent>()
            // Neither window is preferred, so both are eligible and both are woken by the
            // same signal - the case a claim has to arbitrate.
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { event ->
                    if (shouldClaimPluginAction(event, "window-1", targetWindowOpen = false) &&
                        PluginActionEventBus.claim(event)
                    ) {
                        byFirst += event
                    }
                }
            }
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { event ->
                    if (shouldClaimPluginAction(event, "window-2", targetWindowOpen = false) &&
                        PluginActionEventBus.claim(event)
                    ) {
                        bySecond += event
                    }
                }
            }
            runCurrent()

            PluginActionEventBus.requestConfirmation("plugin.a", "sync", emptyMap(), sourceWindowId = null)
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 3)
            runCurrent()

            assertEquals(
                1,
                byFirst.size + bySecond.size,
                "exactly one window may show a request; a losing racer must do nothing",
            )
            assertEquals(0, PluginActionEventBus.pendingCount)
        }

    @Test
    fun `two idle windows racing for two requests each take one`() =
        runTest {
            // The production collector checks canClaim before claim, so a window that has just
            // taken one request must leave the next for the other window rather than take both.
            val first = PluginActionApprovalQueue()
            val second = PluginActionApprovalQueue()
            for ((windowId, queue) in listOf("window-1" to first, "window-2" to second)) {
                backgroundScope.launch {
                    PluginActionEventBus.confirmEvents.collect { event ->
                        if (shouldClaimPluginAction(event, windowId, targetWindowOpen = false) &&
                            queue.canClaim &&
                            PluginActionEventBus.claim(event)
                        ) {
                            queue.enqueue(PendingPluginAction(event.handlerId, event.action, event.params))
                        }
                    }
                }
            }
            runCurrent()

            PluginActionEventBus.requestConfirmation("plugin.a", "one", emptyMap(), sourceWindowId = null)
            PluginActionEventBus.requestConfirmation("plugin.a", "two", emptyMap(), sourceWindowId = null)
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 3)
            runCurrent()

            assertEquals(1, first.size, "a window showing a prompt must not take a second request")
            assertEquals(1, second.size, "the other idle window must get the request the first left")
            assertEquals(
                setOf("one", "two"),
                setOf(first.current?.action, second.current?.action),
                "each request is shown exactly once, in one window",
            )
            assertEquals(0, PluginActionEventBus.pendingCount)
        }

    @Test
    fun `the observable count follows retain, claim and clear`() {
        assertEquals(0, PluginActionEventBus.pendingCountFlow.value)
        retain("plugin.a")
        retain("plugin.b")
        assertEquals(2, PluginActionEventBus.pendingCountFlow.value, "a retained request must be counted")

        assertTrue(PluginActionEventBus.claim(PluginActionEventBus.confirmEventsSnapshotForTest().first()))
        assertEquals(1, PluginActionEventBus.pendingCountFlow.value, "a claimed request must stop being counted")

        repeat(PluginActionEventBus.MAX_PENDING) { retain("plugin.flood") }
        assertEquals(
            PluginActionEventBus.MAX_PENDING,
            PluginActionEventBus.pendingCountFlow.value,
            "a refused request must not be counted",
        )

        PluginActionEventBus.clearForTest()
        assertEquals(0, PluginActionEventBus.pendingCountFlow.value)
    }

    @Test
    fun `a confirmed dispatch preserves the per-window FIFO`() =
        runTest {
            val queue = PluginActionApprovalQueue()
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { event ->
                    if (shouldClaimPluginAction(event, "window-1", targetWindowOpen = false) &&
                        queue.canClaim &&
                        PluginActionEventBus.claim(event)
                    ) {
                        queue.enqueue(PendingPluginAction(event.handlerId, event.action, event.params))
                    }
                }
            }
            runCurrent()

            PluginActionEventBus.requestConfirmation("plugin.a", "first", emptyMap(), sourceWindowId = "window-1")
            PluginActionEventBus.requestConfirmation("plugin.b", "second", emptyMap(), sourceWindowId = "window-1")
            PluginActionEventBus.requestConfirmation("plugin.c", "third", emptyMap(), sourceWindowId = "window-1")
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 2)
            runCurrent()

            // One at a time: the window holds only what it is showing; the rest stay retained.
            assertEquals(1, queue.size, "a window holds only the request it is showing")
            assertEquals(2, PluginActionEventBus.pendingCount, "the rest stay on the bus")

            // Answering the shown one lets the window take the next on its next scan, in arrival
            // order, never skipping or reordering: the operator answers the question they were shown.
            for (expected in listOf("first", "second", "third")) {
                val shown = requireNotNull(queue.current)
                assertEquals(expected, shown.action)
                assertTrue(queue.consume(shown))
                advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 2)
                runCurrent()
            }
            assertNull(queue.current)
            assertEquals(0, PluginActionEventBus.pendingCount)
        }

    @Test
    fun `a window takes one request at a time so closing it abandons only the one shown`() =
        runTest {
            // Cold start with a full registry: every request retained before any window collects.
            repeat(PluginActionEventBus.MAX_PENDING) { i ->
                val admitted =
                    PluginActionEventBus.requestConfirmation("plugin.a", "act-$i", emptyMap(), sourceWindowId = null)
                assertTrue(admitted, "the registry must admit up to its capacity")
            }

            val firstWindow = PluginActionApprovalQueue()
            val firstCollector =
                backgroundScope.launch {
                    PluginActionEventBus.confirmEvents.collect { event ->
                        if (shouldClaimPluginAction(event, "window-1", targetWindowOpen = false) &&
                            firstWindow.canClaim &&
                            PluginActionEventBus.claim(event)
                        ) {
                            firstWindow.enqueue(PendingPluginAction(event.handlerId, event.action, event.params))
                        }
                    }
                }
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 3)
            runCurrent()

            // Claiming every eligible request drained all of them into this one window, so closing
            // it abandoned the lot. One at a time, it holds only the prompt it is showing.
            assertEquals(1, firstWindow.size)
            assertEquals(PluginActionEventBus.MAX_PENDING - 1, PluginActionEventBus.pendingCount)

            // Closing the window drops its queue: exactly the one claimed prompt is lost.
            firstCollector.cancel()

            val secondWindow = PluginActionApprovalQueue()
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { event ->
                    if (shouldClaimPluginAction(event, "window-2", targetWindowOpen = false) &&
                        secondWindow.canClaim &&
                        PluginActionEventBus.claim(event)
                    ) {
                        secondWindow.enqueue(PendingPluginAction(event.handlerId, event.action, event.params))
                    }
                }
            }
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 3)
            runCurrent()

            // Everything the first window never showed is still there for the next one, in order.
            assertEquals("act-1", secondWindow.current?.action, "the next window picks up where the first left off")
            assertEquals(PluginActionEventBus.MAX_PENDING - 2, PluginActionEventBus.pendingCount)
        }

    @Test
    fun `a request waits for its own window while that window is open`() =
        runTest {
            val byOther = mutableListOf<PluginActionConfirmEvent>()
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { event ->
                    // The preferred window is open, so this other window must leave it alone
                    // rather than claim it.
                    if (shouldClaimPluginAction(event, "window-2", targetWindowOpen = true) &&
                        PluginActionEventBus.claim(event)
                    ) {
                        byOther += event
                    }
                }
            }
            runCurrent()

            PluginActionEventBus.requestConfirmation("plugin.a", "sync", emptyMap(), sourceWindowId = "window-1")
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 3)
            runCurrent()

            assertEquals(0, byOther.size, "a window that is not the preferred one must not claim")
            assertEquals(1, PluginActionEventBus.pendingCount, "and the request must stay retained for its own window")
        }

    @Test
    fun `a request whose window closed falls to whichever window is left`() =
        runTest {
            PluginActionEventBus.requestConfirmation("plugin.a", "sync", emptyMap(), sourceWindowId = "window-gone")
            val claimed = mutableListOf<PluginActionConfirmEvent>()
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { event ->
                    // targetWindowOpen = false is what WindowFocusManager.isWindowOpen reports
                    // once the preferred window has closed.
                    if (shouldClaimPluginAction(event, "window-2", targetWindowOpen = false) &&
                        PluginActionEventBus.claim(event)
                    ) {
                        claimed += event
                    }
                }
            }
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 2)
            runCurrent()

            assertEquals(1, claimed.size, "a request must never be stranded by the window it named closing")
        }

    @Test
    fun `identical requests are held separately and claimed one at a time`() =
        runTest {
            PluginActionEventBus.requestConfirmation("plugin.a", "sync", emptyMap(), sourceWindowId = null)
            PluginActionEventBus.requestConfirmation("plugin.a", "sync", emptyMap(), sourceWindowId = null)
            assertEquals(2, PluginActionEventBus.pendingCount, "two identical links are two requests, not one")

            val first = PluginActionEventBus.confirmEventsSnapshotForTest().first()
            assertTrue(PluginActionEventBus.claim(first))
            assertFalse(PluginActionEventBus.claim(first), "claiming is once per entry, by identity")
            assertEquals(1, PluginActionEventBus.pendingCount, "the identical sibling must survive its twin's claim")
        }

    @Test
    fun `a full registry refuses rather than reporting a request it discarded`() =
        runTest {
            repeat(PluginActionEventBus.MAX_PENDING) { index ->
                assertTrue(retain("plugin.$index"))
            }
            assertFalse(
                retain("plugin.overflow"),
                "the caller must be able to refuse rather than answer queued for a dropped request",
            )
            assertEquals(PluginActionEventBus.MAX_PENDING, PluginActionEventBus.pendingCount)
        }

    /** One unaddressed request, named only by its handler - the shape most of these tests want. */
    private fun retain(handlerId: String): Boolean =
        PluginActionEventBus.requestConfirmation(handlerId, "sync", emptyMap(), sourceWindowId = null)

    @Test
    fun `routing prefers the named window and never strands a request`() {
        val unaddressed = PluginActionConfirmEvent("plugin.a", "sync", emptyMap(), sourceWindowId = null)
        val addressed = PluginActionConfirmEvent("plugin.a", "sync", emptyMap(), sourceWindowId = "window-1")

        assertTrue(shouldClaimPluginAction(unaddressed, "window-1", targetWindowOpen = false))
        assertTrue(shouldClaimPluginAction(unaddressed, "window-2", targetWindowOpen = true))
        assertTrue(shouldClaimPluginAction(addressed, "window-1", targetWindowOpen = true))
        assertFalse(shouldClaimPluginAction(addressed, "window-2", targetWindowOpen = true))
        assertTrue(shouldClaimPluginAction(addressed, "window-2", targetWindowOpen = false))
    }

    @Test
    fun `an unclaimed request is offered again on the next scan`() =
        runTest {
            PluginActionEventBus.requestConfirmation("plugin.a", "sync", emptyMap(), sourceWindowId = null)
            var offers = 0
            backgroundScope.launch {
                PluginActionEventBus.confirmEvents.collect { offers++ }
            }
            runCurrent()
            val afterFirstScan = offers
            advanceTimeBy(PLUGIN_ACTION_RESCAN_INTERVAL_MS * 2)
            runCurrent()

            assertTrue(
                offers > afterFirstScan,
                "a request nobody claimed must keep being offered, so a window opening later still sees it",
            )
            assertEquals(
                "plugin.a",
                PluginActionEventBus.confirmEventsSnapshotForTest().single().handlerId,
            )
        }
}
