package ai.rever.boss.search

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.topofmind.TopOfMindStateHolder
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalSearchTabsTest {
    private val previousTabs = TopOfMindStateHolder.activeTabs.value

    @AfterTest
    fun restoreState() {
        TopOfMindStateHolder.updateActiveTabs(previousTabs)
        GlobalSearchService.clearResults()
    }

    private fun search(
        query: String,
        vararg tabs: TabInfo,
    ): List<SearchResult.TabResult> {
        TopOfMindStateHolder.updateActiveTabs(
            tabs.map { ActiveTab(it, "workspace", "Work", "panel", "other-window") },
        )
        return runBlocking { GlobalSearchService.search(query, "search-window") }.filterIsInstance<SearchResult.TabResult>()
    }

    private fun browser() = FluckTabInfo(id = "browser", typeId = TabTypeId("fluck"), _title = "Welcome", url = "https://old.example")

    @Test
    fun `URL-only query uses current navigation and keeps the destination`() {
        val tab = browser()
        tab.navigateToPage("Welcome", "https://github.com/org/issues/606")
        val result = search(" GITHUB.COM ", tab).single()
        assertEquals("https://github.com/org/issues/606", result.url)
        assertEquals("browser", result.tabId)
        assertEquals("other-window", result.windowId)
        assertEquals("panel", result.panelId)
        assertEquals("Welcome", result.title)
        assertTrue(result.matchRanges.isEmpty())
        assertTrue(search("old.example", tab).isEmpty())
        assertEquals(1, search("/issues/606", tab).size)
    }

    @Test
    fun `editor directory query returns path metadata without title highlights`() {
        val tab = EditorTabInfo(id = "editor", title = "Main.kt", filePath = "/workspace/unique-project/src/Main.kt")
        val result = search("unique-project", tab).single()
        assertEquals(tab.filePath, result.filePath)
        assertNull(result.url)
        assertTrue(result.matchRanges.isEmpty())
    }

    @Test
    fun `title hit remains one result when its URL also matches`() {
        val tab = browser()
        tab.navigateToPage("Welcome", "https://example.com/welcome")
        val result = search("welcome", tab).single()
        assertTrue(result.matchRanges.isNotEmpty())
        assertEquals("https://example.com/welcome", result.url)
    }

    @Test
    fun `URL matching rejects scattered letters and empty query`() {
        val tab = browser()
        tab.navigateToPage("Welcome", "https://alpha.example/beta/gamma")
        assertTrue(search("abg", tab).isEmpty())
        assertTrue(search("   ", tab).isEmpty())
    }

    @Test
    fun `absolute paths reject scattered subsequences`() {
        val tab = EditorTabInfo(id = "editor", title = "Main.kt", filePath = "/alpha/beta/gamma/Main.kt")
        assertTrue(search("abg", tab).isEmpty())
    }

    @Test
    fun `empty metadata remains absent on title hits`() {
        val result = search("Main", EditorTabInfo(id = "empty", title = "Main.kt")).single()
        assertNull(result.filePath)
        assertNull(result.url)
    }
}
