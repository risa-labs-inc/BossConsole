package ai.rever.boss.search

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.topofmind.TopOfMindStateHolder
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BossConsole#606: Global Search matched an open tab only against its title, and hardcoded
 * `url = null` / `filePath = null` on every [SearchResult.TabResult] regardless of the tab's real
 * type - so a browser tab whose title never mentions its domain, or an editor tab found only by
 * its path, could not be found at all, and a result that did match carried no URL/path for the
 * dialog to show.
 *
 * [TopOfMindStateHolder] is a singleton, like the sources [GlobalSearchNewSourcesTest] covers, so
 * each test restores it to empty afterward. The holder is a SNAPSHOT: [GlobalSearchService] now
 * validates every entry against the live [SplitViewStateRegistry] before returning it, so each
 * test also opens the tab for real in a registered window - an entry written to the holder alone
 * is precisely the phantom that validation removes.
 */
class GlobalSearchTabsTest {
    private class StubComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private companion object {
        const val WINDOW = "window-under-test"
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(CodeEditorTabType) { config, ctx -> StubComponent(ctx, config, CodeEditorTabType) }
            registerTabType(FluckTabType) { config, ctx -> StubComponent(ctx, config, FluckTabType) }
            registerTabType(TerminalTabType) { config, ctx -> StubComponent(ctx, config, TerminalTabType) }
        }

    private lateinit var windowState: SplitViewState

    @BeforeTest
    fun setUp() {
        TopOfMindStateHolder.updateActiveTabs(emptyList())
        windowState = SplitViewState(tabRegistry, WINDOW)
        // findTabLocation only walks RUNNING workspaces; a fresh state has none until the first
        // is entered, which is what preserveCurrentState marks on a window with nothing prior.
        windowState.preserveCurrentState("w1", "Workspace")
        SplitViewStateRegistry.register(WINDOW, windowState)
    }

    @AfterTest
    fun tearDown() {
        SplitViewStateRegistry.unregister(WINDOW)
        TopOfMindStateHolder.updateActiveTabs(emptyList())
        TabUpdateRegistry.clear()
    }

    private fun searchFor(query: String): List<SearchResult.TabResult> =
        runBlocking {
            GlobalSearchService.search(query, WINDOW, emptyList())
        }.filterIsInstance<SearchResult.TabResult>()

    /**
     * Open [tabs] for real and publish the holder snapshot, the way `TabCollector.refreshGlobalState`
     * leaves things. The liveness check drops any holder entry with no live location, so a test
     * that writes the holder without opening the tab would be asserting on a phantom.
     */
    private fun openTabs(tabs: List<ActiveTab>) {
        val panel = windowState.getPanel("main")!!.tabsComponent
        for (tab in tabs) {
            check(panel.addTab(tab.tabInfo) >= 0) { "no factory for ${tab.tabInfo.typeId}" }
        }
        TopOfMindStateHolder.updateActiveTabs(tabs)
    }

    private fun fluckTab(
        id: String,
        title: String,
        url: String,
    ) = ActiveTab(
        tabInfo =
            FluckTabInfo(
                id = id,
                typeId = TabTypeId("fluck"),
                _title = title,
                url = url,
                _currentUrl = url,
            ),
        workspaceId = "w1",
        workspaceName = "Workspace",
        panelId = "p1",
        windowId = WINDOW,
    )

    private fun editorTab(
        id: String,
        title: String,
        filePath: String,
    ) = ActiveTab(
        tabInfo = EditorTabInfo(id = id, title = title, filePath = filePath),
        workspaceId = "w1",
        workspaceName = "Workspace",
        panelId = "p1",
        windowId = WINDOW,
    )

    @Test
    fun `a browser tab is found by its URL even when the title never mentions it`() {
        openTabs(
            listOf(fluckTab(id = "t1", title = "Pull requests", url = "https://github.com/risa-labs-inc/BossConsole")),
        )

        val hits = searchFor("github")

        assertEquals(listOf("t1"), hits.map { it.tabId })
    }

    @Test
    fun `an editor tab is found by its file path even when the title never mentions it`() {
        openTabs(
            listOf(editorTab(id = "t1", title = "Untitled", filePath = "/project/src/AuthService.kt")),
        )

        val hits = searchFor("AuthService")

        assertEquals(listOf("t1"), hits.map { it.tabId })
    }

    @Test
    fun `a matched browser tab carries its URL, not null`() {
        openTabs(
            listOf(fluckTab(id = "t1", title = "GitHub", url = "https://github.com/risa-labs-inc/BossConsole")),
        )

        val hit = searchFor("github").single()

        assertEquals("https://github.com/risa-labs-inc/BossConsole", hit.url)
        assertEquals(null, hit.filePath)
    }

    @Test
    fun `a matched editor tab carries its file path, not null`() {
        openTabs(
            listOf(editorTab(id = "t1", title = "AuthService.kt", filePath = "/project/src/AuthService.kt")),
        )

        val hit = searchFor("AuthService").single()

        assertEquals("/project/src/AuthService.kt", hit.filePath)
        assertEquals(null, hit.url)
    }

    @Test
    fun `an editor tab with no file path yet still matches on title, and reports no path`() {
        // A new, unsaved editor tab: EditorTabInfo.filePath defaults to blank. Surfacing "" as if
        // it were a real path would be worse than surfacing null.
        openTabs(
            listOf(editorTab(id = "t1", title = "Untitled-1", filePath = "")),
        )

        val hit = searchFor("Untitled-1").single()

        assertEquals(null, hit.url)
        assertEquals(null, hit.filePath)
    }

    @Test
    fun `a short query does not match every tab by a URL fuzzy subsequence`() {
        // Same reasoning searchRecentPages already relies on: FuzzyMatcher accepts any in-order
        // subsequence, so an untreated URL would match almost any short query by accident.
        openTabs(
            listOf(fluckTab(id = "t1", title = "Dashboard", url = "https://example.com/a/b/c/d/e/f/g/h")),
        )

        assertTrue(searchFor("abc").isEmpty(), "scattered letters from the URL are not a hit")
    }

    @Test
    fun `no open tabs contributes nothing rather than failing`() {
        assertTrue(searchFor("anything").isEmpty())
    }

    @Test
    fun `title score wins when both title and URL match`() {
        openTabs(
            listOf(fluckTab(id = "t1", title = "GitHub", url = "https://github.com/risa-labs-inc/BossConsole")),
        )

        val hit = searchFor("github").single()
        val titleMatch = requireNotNull(FuzzyMatcher.match("github", "GitHub", "github"))
        assertEquals(titleMatch.score + 30, hit.score)
        assertEquals(titleMatch.matchRanges, hit.matchRanges)
    }

    @Test
    fun `terminal tabs still match only by title without location metadata`() {
        openTabs(
            listOf(
                ActiveTab(
                    TerminalTabInfo(id = "terminal", title = "Terminal", workingDirectory = "/unique-project"),
                    "w1",
                    "Workspace",
                    "p1",
                    WINDOW,
                ),
            ),
        )

        val hit = searchFor("Terminal").single()
        assertEquals(null, hit.url)
        assertEquals(null, hit.filePath)
        assertTrue(searchFor("unique-project").isEmpty())
    }

    @Test
    fun `URL search uses current navigation and ignores case while preserving destination`() {
        val tab = fluckTab(id = "t1", title = "Welcome", url = "https://old.example")
        (tab.tabInfo as FluckTabInfo).navigateToPage("Welcome", "https://github.com/org/issues/606")
        openTabs(listOf(tab))

        val hit = searchFor(" GITHUB.COM ").single()
        assertEquals("https://github.com/org/issues/606", hit.url)
        assertEquals("t1", hit.tabId)
        assertEquals(WINDOW, hit.windowId)
        assertEquals("Workspace", hit.workspaceName)
        assertEquals("p1", hit.panelId)
        assertTrue(hit.matchRanges.isEmpty())
        assertTrue(searchFor("old.example").isEmpty())
    }

    @Test
    fun `absolute paths reject scattered subsequences but accept case insensitive directories`() {
        openTabs(
            listOf(editorTab(id = "t1", title = "Main.kt", filePath = "/alpha/beta/gamma/Main.kt")),
        )

        assertTrue(searchFor("abg").isEmpty())
        assertEquals("t1", searchFor("BETA").single().tabId)
    }

    @Test
    fun `a tab closed after the snapshot was taken is not returned`() {
        // b16: the holder is a snapshot - closing the tab does not rewrite it - so the entry
        // still matches the query. The liveness check against the live window is what keeps the
        // dead tab from coming back as an actionable result.
        openTabs(listOf(editorTab(id = "t1", title = "AuthService.kt", filePath = "/project/AuthService.kt")))

        val panel = windowState.getPanel("main")!!.tabsComponent
        val index =
            panel.tabsState.value.tabs
                .indexOfFirst { it.id == "t1" }
        panel.removeTab(index)

        assertTrue(
            searchFor("AuthService").isEmpty(),
            "a tab closed since the snapshot must not be returned as actionable",
        )
    }

    @Test
    fun `a holder entry whose window is gone is not returned`() {
        // The holder is only ever written from registered windows, so an entry pointing at an
        // unregistered one is stale by definition: its window closed since the snapshot.
        TopOfMindStateHolder.updateActiveTabs(
            listOf(editorTab(id = "t1", title = "AuthService.kt", filePath = "/project/AuthService.kt")),
        )

        assertTrue(searchFor("AuthService").isEmpty())
    }
}
