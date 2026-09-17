package ai.rever.boss.search

import ai.rever.boss.components.dialogs.SpotlightDialogState
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.plugin.browser.ActiveBrowserRegistry
import ai.rever.boss.plugin.browser.BrowserHandle
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchCommandTargetsTest {
    private val available = SearchCommandTargets(8, hasClosedTabs = true, hasActiveBrowser = true)

    @Test
    fun `browser eligibility excludes other windows sidebar surfaces background panes and dead handles`() {
        val window = "spotlight-target-window"
        val otherWindow = "spotlight-other-window"
        var live = true
        val browser =
            Proxy.newProxyInstance(
                BrowserHandle::class.java.classLoader,
                arrayOf(BrowserHandle::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getId" -> "spotlight-target-browser"
                    "isValid" -> live
                    else -> error("Unexpected browser call: ${method.name}")
                }
            } as BrowserHandle
        var token: Any? = null
        try {
            token = ActiveBrowserRegistry.register(browser, window, inMainPanel = true, panelActive = true)
            assertTrue(ActiveBrowserRegistry.hasActiveMainPanelBrowser(window))
            assertFalse(ActiveBrowserRegistry.hasActiveMainPanelBrowser(otherWindow))

            token = ActiveBrowserRegistry.register(browser, window, inMainPanel = false, panelActive = true)
            assertFalse(ActiveBrowserRegistry.hasActiveMainPanelBrowser(window))
            token = ActiveBrowserRegistry.register(browser, window, inMainPanel = true, panelActive = false)
            assertFalse(ActiveBrowserRegistry.hasActiveMainPanelBrowser(window))
            token = ActiveBrowserRegistry.register(browser, window, inMainPanel = true, panelActive = true)
            live = false
            // No republish: selection must see liveness now, not an earlier menu-state snapshot.
            assertFalse(ActiveBrowserRegistry.hasActiveMainPanelBrowser(window))
        } finally {
            ActiveBrowserRegistry.unregister(browser.id, token)
        }
        assertFalse(ActiveBrowserRegistry.hasActiveMainPanelBrowser(window))
    }

    @Test
    fun `every browser command refuses a missing active browser before invoking anything`() {
        val commands =
            listOf(
                KeymapActions.BROWSER_RELOAD,
                KeymapActions.BROWSER_ZOOM_RESET,
                KeymapActions.BROWSER_ZOOM_IN,
                KeymapActions.BROWSER_ZOOM_OUT,
                KeymapActions.BROWSER_FIND,
                KeymapActions.BROWSER_BACK,
                KeymapActions.BROWSER_FORWARD,
                KeymapActions.BROWSER_DEVTOOLS,
            )
        commands.forEach { command ->
            assertRejected(command, available.copy(hasActiveBrowser = false))
            assertDispatched(command, available)
        }
    }

    @Test
    fun `tab stepping refuses zero or one tab without emitting step or commit`() {
        val commands =
            listOf(
                KeymapActions.TAB_NEXT,
                KeymapActions.TAB_PREVIOUS,
                KeymapActions.TAB_NEXT_POSITIONAL,
                KeymapActions.TAB_PREVIOUS_POSITIONAL,
            )
        commands.forEach { command ->
            for (count in 0..1) {
                assertRejected(command, available.copy(activePanelTabCount = count))
            }
            assertDispatched(command, available.copy(activePanelTabCount = 2))
        }
    }

    @Test
    fun `all eight tab indices check the current pane count and last requires a tab`() {
        KeymapActions.TAB_SELECT_BY_INDEX.forEachIndexed { index, command ->
            assertRejected(command, available.copy(activePanelTabCount = index))
            assertDispatched(command, available.copy(activePanelTabCount = index + 1))
        }
        assertRejected(KeymapActions.TAB_SELECT_LAST, available.copy(activePanelTabCount = 0))
        assertDispatched(KeymapActions.TAB_SELECT_LAST, available.copy(activePanelTabCount = 1))
    }

    @Test
    fun `close and split refuse an empty pane`() {
        listOf(KeymapActions.TAB_CLOSE, KeymapActions.PANEL_SPLIT_VERTICAL, KeymapActions.PANEL_SPLIT_HORIZONTAL)
            .forEach { command ->
                assertRejected(command, available.copy(activePanelTabCount = 0))
                assertDispatched(command, available.copy(activePanelTabCount = 1))
            }
    }

    @Test
    fun `reopen checks current history and commands without tab targets remain usable`() {
        assertDispatched(KeymapActions.TAB_REOPEN_CLOSED, available)
        assertRejected(KeymapActions.TAB_REOPEN_CLOSED, available.copy(hasClosedTabs = false))
        val emptyWindow = SearchCommandTargets(0, hasClosedTabs = false, hasActiveBrowser = false)
        listOf(KeymapActions.TAB_NEW, KeymapActions.WINDOW_NEW, KeymapActions.SETTINGS_OPEN)
            .forEach { command -> assertDispatched(command, emptyWindow) }
    }

    @Test
    fun `dialog displays rejection in only the invoking session and retains the query`() {
        val first = SpotlightDialogState().apply { updateQuery("browser.find") }
        val second = SpotlightDialogState().apply { updateQuery("other window") }
        var invoked = false
        first.selectCommand(KeymapActions.BROWSER_FIND) { command ->
            SearchCommandDispatcher.dispatch(command, "first-window", available.copy(hasActiveBrowser = false)) {
                invoked = true
            }
        }
        assertTrue(!invoked)
        assertEquals("browser.find", first.query)
        assertEquals("Select a browser tab in this pane first.", first.commandRejection)
        assertNull(second.commandRejection)
        assertNull(SpotlightDialogState().commandRejection)

        first.updateQuery("settings")
        assertNull(first.commandRejection)
        first.selectCommand("stale.command") { SearchCommandDispatchOutcome.Rejected("Command is unavailable.") }
        first.selectCommand(KeymapActions.SETTINGS_OPEN) { SearchCommandDispatchOutcome.Dispatched }
        assertNull(first.commandRejection)
    }

    @Test
    fun `a missing command callback explains unavailability without dismissing the dialog`() {
        val state = SpotlightDialogState()
        state.selectCommand(KeymapActions.BROWSER_FIND, null)
        assertEquals("Commands are not available in this window.", state.commandRejection)
    }

    private fun assertRejected(
        command: String,
        targets: SearchCommandTargets,
    ) {
        val invocations = mutableListOf<SearchCommandInvocation>()
        val outcome = SearchCommandDispatcher.dispatch(command, "window-a", targets, invocations::add)
        assertIs<SearchCommandDispatchOutcome.Rejected>(outcome, command)
        assertTrue(outcome.reason.isNotBlank(), command)
        assertTrue(invocations.isEmpty(), command)
    }

    private fun assertDispatched(
        command: String,
        targets: SearchCommandTargets,
    ) {
        val invocations = mutableListOf<SearchCommandInvocation>()
        val outcome = SearchCommandDispatcher.dispatch(command, "window-a", targets, invocations::add)
        assertEquals(SearchCommandDispatchOutcome.Dispatched, outcome, command)
        assertTrue(invocations.isNotEmpty(), command)
    }
}
