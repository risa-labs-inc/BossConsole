package ai.rever.boss.components.events

import ai.rever.boss.plugin.api.PanelId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `SplitViewOperations.openPanelAsTab` reaches the window's BossDraggableComponent through
 * [PanelEventBus.promotePanelToTab], so the bus carries the two properties the handler relies
 * on: it is addressed to ONE window, and it never replays.
 */
class PanelPromoteToTabEventTest {
    private val panel = PanelId("codebase", 1)

    @Test
    fun `only the addressed window sees the promote`() =
        runTest {
            val mine = "window-addressed"
            val theirs = "window-bystander"

            val received =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        PanelEventBus.panelPromoteToTabEvents.first { it.sourceWindowId == mine }
                    }
                }
            val bystander =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        PanelEventBus.panelPromoteToTabEvents.first { it.sourceWindowId == theirs }
                    }
                }
            yield()

            PanelEventBus.promotePanelToTab(panel, sourceWindowId = mine)

            assertEquals(panel, assertNotNull(received.await()).panelId)
            assertNull(bystander.await(), "a promote in one window must not open a tab in another")
        }

    /**
     * Unlike `panelOpenEvents`, this flow has no replay - and must not gain one. Replay there
     * covers a startup race for a panel that should end up open; a replayed promote would give
     * every window opened afterwards its own copy of that tab, over the one cached component.
     */
    @Test
    fun `a window that opens later does not replay someone else's promote`() =
        runTest {
            val window = "window-late-collector"

            PanelEventBus.promotePanelToTab(panel, sourceWindowId = window)
            yield()

            val late =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        PanelEventBus.panelPromoteToTabEvents.first { it.sourceWindowId == window }
                    }
                }

            assertNull(late.await(), "the promote was already delivered - a late window must not repeat it")
        }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
