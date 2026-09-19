package ai.rever.boss.search

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.topofmind.TopOfMindStateHolder
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
 * each test restores it to empty afterward.
 */
class GlobalSearchTabsTest {
    private companion object {
        const val WINDOW = "window-under-test"
    }

    @BeforeTest
    fun setUp() {
        TopOfMindStateHolder.updateActiveTabs(emptyList())
    }

    @AfterTest
    fun tearDown() {
        TopOfMindStateHolder.updateActiveTabs(emptyList())
    }

    private fun searchFor(query: String): List<SearchResult.TabResult> =
        runBlocking {
            GlobalSearchService.search(query, WINDOW, emptyList())
        }.filterIsInstance<SearchResult.TabResult>()

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
        TopOfMindStateHolder.updateActiveTabs(
            listOf(fluckTab(id = "t1", title = "Pull requests", url = "https://github.com/risa-labs-inc/BossConsole")),
        )

        val hits = searchFor("github")

        assertEquals(listOf("t1"), hits.map { it.tabId })
    }

    @Test
    fun `an editor tab is found by its file path even when the title never mentions it`() {
        TopOfMindStateHolder.updateActiveTabs(
            listOf(editorTab(id = "t1", title = "Untitled", filePath = "/project/src/AuthService.kt")),
        )

        val hits = searchFor("AuthService")

        assertEquals(listOf("t1"), hits.map { it.tabId })
    }

    @Test
    fun `a matched browser tab carries its URL, not null`() {
        TopOfMindStateHolder.updateActiveTabs(
            listOf(fluckTab(id = "t1", title = "GitHub", url = "https://github.com/risa-labs-inc/BossConsole")),
        )

        val hit = searchFor("github").single()

        assertEquals("https://github.com/risa-labs-inc/BossConsole", hit.url)
        assertEquals(null, hit.filePath)
    }

    @Test
    fun `a matched editor tab carries its file path, not null`() {
        TopOfMindStateHolder.updateActiveTabs(
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
        TopOfMindStateHolder.updateActiveTabs(
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
        TopOfMindStateHolder.updateActiveTabs(
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
        TopOfMindStateHolder.updateActiveTabs(
            listOf(fluckTab(id = "t1", title = "GitHub", url = "https://github.com/risa-labs-inc/BossConsole")),
        )

        val hit = searchFor("github").single()
        val titleMatch = requireNotNull(FuzzyMatcher.match("github", "GitHub", "github"))
        assertEquals(titleMatch.score + 30, hit.score)
        assertEquals(titleMatch.matchRanges, hit.matchRanges)
    }

    @Test
    fun `terminal tabs still match only by title without location metadata`() {
        TopOfMindStateHolder.updateActiveTabs(
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
        val initialInfo = FluckTabInfo(id = "t1", typeId = TabTypeId("fluck"), _title = "Welcome", url = "https://old.example")
        val updatedInfo = initialInfo.updateNavigation("Welcome", "https://github.com/org/issues/606")
        val tab = ActiveTab(
            windowId = WINDOW,
            workspaceName = "Workspace",
            panelId = "p1",
            tabInfo = updatedInfo,
        )
        TopOfMindStateHolder.updateActiveTabs(listOf(tab))

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
        TopOfMindStateHolder.updateActiveTabs(
            listOf(editorTab(id = "t1", title = "Main.kt", filePath = "/alpha/beta/gamma/Main.kt")),
        )

        assertTrue(searchFor("abg").isEmpty())
        assertEquals("t1", searchFor("BETA").single().tabId)
    }
}
