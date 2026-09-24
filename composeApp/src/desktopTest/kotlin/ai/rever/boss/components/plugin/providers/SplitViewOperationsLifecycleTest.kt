package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SplitViewOperationsLifecycleTest {
    @Test
    fun `dispose stops later panel events`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state = SplitViewState(TabRegistry(), "split-provider-state")
            val operations = SplitViewOperationsImpl(state, "split-provider-window", dispatcher)
            val panel = PanelId("lifecycle-panel", 1)
            val before =
                async {
                    PanelEventBus.panelPromoteToTabEvents.first {
                        it.sourceWindowId == "split-provider-window"
                    }
                }
            yield()

            operations.openPanelAsTab(panel)
            advanceUntilIdle()

            assertEquals(panel, before.await().panelId)

            operations.dispose()
            val after =
                async {
                    withTimeoutOrNull(1) {
                        PanelEventBus.panelPromoteToTabEvents.first {
                            it.sourceWindowId == "split-provider-window"
                        }
                    }
                }
            yield()

            operations.openPanelAsTab(panel)
            advanceUntilIdle()

            assertNull(after.await())
        }
}
