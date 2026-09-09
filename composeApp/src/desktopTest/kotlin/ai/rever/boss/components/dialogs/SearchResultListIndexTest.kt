package ai.rever.boss.components.dialogs

import ai.rever.boss.search.SearchCategory
import ai.rever.boss.search.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [listItemIndexFor], which converts a selection's RESULT index into the `LazyColumn` item
 * index the auto-scroll needs.
 *
 * The two are not the same number once sections are drawn: `SearchResultsList` emits a header
 * before each section and a spacer after it, so they drift by two per section above the selection.
 * Getting it wrong is not a crash - the list scrolls to a plausible-looking wrong place, which is
 * the sort of thing that gets filed as "search feels broken" and never traced.
 *
 * Worth its own test because it is correct only while `getFilteredResults` groups by category
 * ordinal: `distinctBy` counts a category once, which holds for a grouped list and fails for an
 * interleaved one. That is two invariants in two files, and this is the one that would not survive
 * the other being changed alone.
 */
class SearchResultListIndexTest {
    private fun tool(label: String) = SearchResult.ToolResult(panelId = label, label = label, score = 1)

    private fun setting(label: String) =
        SearchResult.SettingResult(
            section = "THEME",
            pluginPageId = null,
            panelId = null,
            group = null,
            label = label,
            breadcrumb = "Appearance",
            highlightable = true,
            score = 1,
        )

    private fun page(url: String) = SearchResult.PageResult(url = url, title = url, score = 1)

    @Test
    fun `without sections a result index is already the item index`() {
        // The single-category view emits nothing but rows.
        val results = listOf(tool("a"), tool("b"), tool("c"))

        assertEquals(2, listItemIndexFor(2, results, showSections = false))
    }

    @Test
    fun `the first row of the first section sits after its header`() {
        val results = listOf(tool("a"), tool("b"))

        assertEquals(1, listItemIndexFor(0, results, showSections = true))
    }

    @Test
    fun `rows inside one section advance one for one`() {
        val results = listOf(tool("a"), tool("b"), tool("c"))

        assertEquals(1, listItemIndexFor(0, results, showSections = true))
        assertEquals(2, listItemIndexFor(1, results, showSections = true))
        assertEquals(3, listItemIndexFor(2, results, showSections = true))
    }

    @Test
    fun `crossing into a second section skips that section's spacer and header`() {
        // Layout: [header][tool a][tool b][spacer][header][setting x]
        //          0       1       2       3       4       5
        val results = listOf(tool("a"), tool("b"), setting("x"))

        assertEquals(1, listItemIndexFor(0, results, showSections = true))
        assertEquals(2, listItemIndexFor(1, results, showSections = true))
        assertEquals(5, listItemIndexFor(2, results, showSections = true), "first row of section two")
    }

    @Test
    fun `a third section accumulates two more items again`() {
        // [header][a][spacer][header][x][spacer][header][p]
        //  0       1  2       3       4  5       6       7
        val results = listOf(tool("a"), setting("x"), page("p"))

        assertEquals(1, listItemIndexFor(0, results, showSections = true))
        assertEquals(4, listItemIndexFor(1, results, showSections = true))
        assertEquals(7, listItemIndexFor(2, results, showSections = true))
    }

    @Test
    fun `the categories it counts are the ones the list draws headers for`() {
        // Sanity on the coupling: the results have to arrive grouped, which is what
        // getFilteredResults guarantees by sorting on ordinal. TOOLS precedes SETTINGS precedes
        // PAGES, so this list is in the order the walk expects.
        val results = listOf(tool("a"), setting("x"), page("p"))

        assertEquals(
            listOf(SearchCategory.TOOLS, SearchCategory.SETTINGS, SearchCategory.PAGES),
            results.map { it.category },
        )
    }
}
