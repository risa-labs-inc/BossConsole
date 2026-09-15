package ai.rever.boss.services

import ai.rever.boss.components.events.URLOpenEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExternalUrlWindowTargetTest {
    @Test
    fun `focuses and routes an external URL to the same actionable window`() =
        runTest {
            val focusedWindowIds = mutableListOf<String>()
            val emittedEvents = mutableListOf<URLOpenEvent>()
            val resolveWindowId: () -> String? = { "window-b" }
            val focusWindow: (String) -> Boolean = { windowId -> focusedWindowIds.add(windowId) }
            val openUrl: suspend (String, String, String) -> Unit = { url, title, sourceWindowId ->
                emittedEvents += URLOpenEvent(url, title, sourceWindowId)
            }

            val route =
                prepareExternalUrlRoute(
                    url = "https://example.com/path",
                    title = "example.com",
                    resolveWindowId = resolveWindowId,
                    focusWindow = focusWindow,
                    openUrl = openUrl,
                )

            assertNotNull(route)
            assertEquals("window-b", route.targetWindowId)
            route.emit()
            assertEquals(listOf("window-b"), focusedWindowIds)
            assertEquals(
                listOf(URLOpenEvent("https://example.com/path", "example.com", "window-b")),
                emittedEvents,
            )
        }

    @Test
    fun `does not create a route when the target window cannot be focused`() {
        val focusedWindowIds = mutableListOf<String>()
        val resolveWindowId: () -> String? = { "stale-window" }
        val focusWindow: (String) -> Boolean = { windowId ->
            focusedWindowIds += windowId
            false
        }
        val openUrl: suspend (String, String, String) -> Unit = { _, _, _ ->
            error("A route must not emit when focus fails")
        }

        val route =
            prepareExternalUrlRoute(
                url = "https://example.com",
                title = "example.com",
                resolveWindowId = resolveWindowId,
                focusWindow = focusWindow,
                openUrl = openUrl,
            )

        assertNull(route)
        assertEquals(listOf("stale-window"), focusedWindowIds)
    }

    @Test
    fun `does not focus a window when no URL target is available`() {
        val focusedWindowIds = mutableListOf<String>()
        val resolveWindowId: () -> String? = { null }
        val focusWindow: (String) -> Boolean = { windowId -> focusedWindowIds.add(windowId) }
        val openUrl: suspend (String, String, String) -> Unit = { _, _, _ ->
            error("A route must not emit without a target window")
        }

        val route =
            prepareExternalUrlRoute(
                url = "https://example.com",
                title = "example.com",
                resolveWindowId = resolveWindowId,
                focusWindow = focusWindow,
                openUrl = openUrl,
            )

        assertNull(route)
        assertEquals(emptyList(), focusedWindowIds)
    }
}
