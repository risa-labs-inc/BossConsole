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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalLinkOpenRequestTest {
    @Test
    fun `plugin requests reach the chooser bus with their originating window and terminal`() =
        runTest {
            for ((window, terminal, url) in listOf(
                Triple("window-a", "terminal-a", "https://example.com"),
                Triple("window-b", "terminal-b", "file:/src/Foo.kt:42:7"),
                Triple("window-sidebar", ai.rever.boss.plugin.api.SIDEBAR_TERMINAL_ID, "https://example.com/sidebar"),
            )) {
                val operations =
                    SplitViewOperationsImpl(
                        splitViewState = SplitViewState(TabRegistry(), window),
                        windowId = window,
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    )
                try {
                    assertTrue(operations.supportsOpenTerminalLink)
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

    @Test
    fun `requests only accept web pages and absolute local file references`() {
        for (url in listOf(
            "javascript:alert(1)",
            "boss://terminal?command=ls",
            "jar:file:/tmp/x.jar",
            "file:src/Foo.kt:12",
            "file://server/share/Foo.kt",
            "file:////server/share/Foo.kt",
            "file:%5C%5Cserver%5Cshare%5CFoo.kt",
            "file:/%5Cserver/share/Foo.kt",
        )) {
            assertFalse(isSupportedTerminalLinkRequest(url), url)
        }
        for (url in listOf(
            "https://example.com",
            "http://localhost:3000",
            "file:/src/a #1?.kt:12",
            "file:C:/src/Foo.kt:12",
        )) {
            assertTrue(isSupportedTerminalLinkRequest(url), url)
        }
    }
}
