package ai.rever.boss.components.dialogs

import ai.rever.boss.search.GlobalSearchService
import ai.rever.boss.search.SearchCategory
import ai.rever.boss.search.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // ==================== SECTION STARTS ====================
    //
    // [sectionStartsFor] is what finally pins the selected row's highlight in the sectioned
    // view: a row is selected iff `start + localIndex == selectedIndex`, and a start read after
    // the drawing walk finished (the old bug: a loop-mutated var captured by the lazy `items`
    // lambda) is the list's total, so no row ever matched. These tests pin the walk itself,
    // its input contract, and the coupling to `getFilteredResults` that the contract relies on.

    private fun grouped(list: List<SearchResult>) = list.groupBy { it.category }

    @Test
    fun `section starts are the first occurrence of each category`() {
        // Layout the list draws: [TOOLS x2][SETTINGS x2][PAGES x1]
        val results = listOf(tool("a"), tool("b"), setting("x"), setting("y"), page("p"))

        assertEquals(
            mapOf(
                SearchCategory.TOOLS to 0,
                SearchCategory.SETTINGS to 2,
                SearchCategory.PAGES to 4,
            ),
            sectionStartsFor(grouped(results)),
        )
    }

    @Test
    fun `each category's start equals its first index in the list`() {
        // The sectioned view's selection math is only right if the start is where the block
        // actually begins - i.e. where getFilteredResults' ordinal sort put it.
        val results = listOf(tool("a"), setting("x"), page("p"), page("q"))

        for ((category, start) in sectionStartsFor(grouped(results))) {
            assertEquals(
                results.indexOfFirst { it.category == category },
                start,
                "start of $category",
            )
        }
    }

    @Test
    fun `section ranges cover every result index exactly once`() {
        // `start + localIndex` must be a bijection onto 0 until results.size: a gap means a row
        // no selection can ever light, an overlap means two rows lighting for one selection.
        val results = listOf(tool("a"), tool("b"), setting("x"), page("p"), page("q"))

        val covered =
            sectionStartsFor(grouped(results))
                .flatMap { (category, start) ->
                    (start until start + results.count { it.category == category }).toList()
                }.sorted()

        assertEquals(results.indices.toList(), covered, "ranges must tile the whole list")
    }

    @Test
    fun `a category with no results gets no start`() {
        // Only TOOLS and PAGES are present; SETTINGS must not appear, and PAGES' start must not
        // be offset by a phantom settings block.
        val results = listOf(tool("a"), page("p"))

        val starts = sectionStartsFor(grouped(results))

        assertEquals(2, starts.size)
        assertFalse(starts.containsKey(SearchCategory.SETTINGS))
        assertEquals(1, starts.getValue(SearchCategory.PAGES))
    }

    @Test
    fun `an empty grouping has no sections`() {
        assertTrue(sectionStartsFor(grouped(emptyList())).isEmpty())
    }

    @Test
    fun `interleaved input is out of contract - the caller groups first`() {
        // The walk is an enum-order walk over each category's COUNT: it only equals the position
        // where a block actually starts when the input is already grouped. getFilteredResults
        // guarantees that (the test below); this pins what the walk does if the guarantee is
        // ever broken, so a drift shows up here as a failing test rather than a silent
        // mis-highlight in the dialog.
        val interleaved = listOf(tool("a"), setting("x"), tool("b"))

        assertEquals(
            mapOf(SearchCategory.TOOLS to 0, SearchCategory.SETTINGS to 2),
            sectionStartsFor(grouped(interleaved)),
        )
        // ...and note that is NOT where the rows actually sit in an interleaved list:
        assertEquals(1, interleaved.indexOfFirst { it.category == SearchCategory.SETTINGS })
    }

    @Test
    fun `getFilteredResults output satisfies the walk's contract`() {
        // The two-file coupling, pinned at the real producer: SearchResultsList draws
        // getFilteredResults' output as sections, and the starts are the rows' true positions
        // only if that output is grouped by category ordinal. If the sort ever stops grouping,
        // this test - and only this test - fails.
        val interleaved =
            listOf(page("p1"), tool("a"), setting("x"), page("p2"), tool("b"), setting("y"))
        val filtered = GlobalSearchService.getFilteredResults(interleaved, SearchCategory.ALL)

        for ((category, start) in sectionStartsFor(grouped(filtered))) {
            assertEquals(
                filtered.indexOfFirst { it.category == category },
                start,
                "start of $category",
            )
        }
    }

    @Test
    fun `section starts and listItemIndexFor agree for every row`() {
        // Both encode the same layout (a header and a spacer per section). If they disagree,
        // either the highlight or the auto-scroll lands on the wrong row.
        val results = listOf(tool("a"), tool("b"), setting("x"), page("p"), page("q"))
        val byCategory = grouped(results)
        val starts = sectionStartsFor(byCategory)

        for (i in results.indices) {
            val section = starts.entries.first { i in it.value until it.value + byCategory.getValue(it.key).size }
            val sectionsBefore = starts.keys.count { starts.getValue(it) < section.value }
            val positionFromSections = i + 2 * sectionsBefore + 1

            assertEquals(listItemIndexFor(i, results, showSections = true), positionFromSections, "row $i")
        }
    }
}
