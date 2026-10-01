package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.TerminalLinkEventBus
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalLinkOpenRequestTest {
    @Test
    fun `plugin requests reach the chooser bus with their originating window and terminal`() =
        runTest {
            for ((window, terminal, url) in listOf(
                Triple("window-a", "terminal-a", "https://example.com"),
                Triple("window-b", "terminal-b", "file:/src/Foo.kt:42:7"),
            )) {
                val operations =
                    SplitViewOperationsImpl(
                        splitViewState = SplitViewState(TabRegistry(), window),
                        windowId = window,
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    )
                try {
                    val request =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            TerminalLinkEventBus.linkClickEvents.first()
                        }
                    operations.openTerminalLink(url, terminal)
                    val event = request.await()
                    assertEquals(url, event.url)
                    assertEquals(terminal, event.sourceTerminalId)
                    assertEquals(window, event.sourceWindowId)
                } finally {
                    operations.dispose()
                }
            }
        }
}
