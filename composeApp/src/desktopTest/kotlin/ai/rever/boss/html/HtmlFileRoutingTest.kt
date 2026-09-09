package ai.rever.boss.html

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HtmlFileRoutingTest {
    @Test
    fun `smart routing queues both HTML extensions without creating a tab first`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val state = SplitViewState(TabRegistry(), "html-routing-test")
            try {
                state.openFileInActivePanel("/tmp/report.html", "report.html")
                state.openFileInActivePanel("/tmp/REPORT.HTM", "REPORT.HTM")
                val requests =
                    state.htmlFileOpens.events
                        .take(2)
                        .toList()
                assertEquals(listOf("report.html", "REPORT.HTM"), requests.map { it.fileName })
                assertTrue(
                    state
                        .getPanel("main")!!
                        .tabsComponent.tabsState.value.tabs
                        .isEmpty(),
                )
            } finally {
                state.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `HTML search hits bypass the choice queue`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val state = SplitViewState(TabRegistry(), "html-position-test")
            try {
                state.openFileInActivePanel("/tmp/report.html", "report.html", line = 12)
                runCurrent()
                assertFalse(state.htmlFileOpens.hasPending)
            } finally {
                state.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `only HTML is ambiguous and existing browser formats keep their routing`() {
        for (name in listOf("index.html", "index.htm", "INDEX.HTML", "INDEX.HTM")) {
            assertTrue(SplitViewState.isHtmlFile(name))
            assertFalse(SplitViewState.shouldOpenInBrowser(name))
        }
        for (name in listOf("notes.md", "html", "report.html.txt", "image.svg", "report.pdf")) {
            assertFalse(SplitViewState.isHtmlFile(name))
        }
        assertTrue(SplitViewState.shouldOpenInBrowser("image.svg"))
        assertTrue(SplitViewState.shouldOpenInBrowser("report.pdf"))
    }
}
