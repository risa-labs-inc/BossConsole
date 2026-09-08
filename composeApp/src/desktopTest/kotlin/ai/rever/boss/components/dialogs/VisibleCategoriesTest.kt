package ai.rever.boss.components.dialogs

import ai.rever.boss.search.SearchCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [visibleCategories], which decides both what the chip row draws and what Tab cycles.
 *
 * Two bugs live here when it is wrong, and neither throws. Cycling the whole enum let Tab select a
 * category with no chip and no results, so the user landed on "No Tools Found" with nothing on
 * screen saying where they were. Filtering on count alone let the ACTIVE chip disappear as a query
 * narrowed, which is the same thing reached by typing - and left `indexOf(activeCategory)` at -1,
 * so the next Tab cycled from an arbitrary place.
 */
class VisibleCategoriesTest {
    @Test
    fun `ALL is always on the row, even with no results at all`() {
        val visible = visibleCategories(emptyMap(), SearchCategory.ALL)

        assertEquals(listOf(SearchCategory.ALL), visible)
    }

    @Test
    fun `a category with results gets a chip`() {
        val visible = visibleCategories(mapOf(SearchCategory.TOOLS to 3), SearchCategory.ALL)

        assertEquals(listOf(SearchCategory.ALL, SearchCategory.TOOLS), visible)
    }

    @Test
    fun `a category with no results and no selection gets none`() {
        val visible = visibleCategories(mapOf(SearchCategory.TOOLS to 0), SearchCategory.ALL)

        assertTrue(SearchCategory.TOOLS !in visible)
    }

    @Test
    fun `the active category keeps its chip after its count drops to zero`() {
        // The invisible-filter bug: Tab to Tools, then narrow the query until no tool matches. The
        // pane still says "No Tools Found", so the chip has to stay to explain why.
        val visible = visibleCategories(mapOf(SearchCategory.TOOLS to 0), SearchCategory.TOOLS)

        assertEquals(listOf(SearchCategory.ALL, SearchCategory.TOOLS), visible)
    }

    @Test
    fun `the active category is always cyclable, so Tab never starts from -1`() {
        // What the Tab handler depends on. indexOf(-1) did not crash but cycled from nowhere in
        // particular, which is worse to reason about than a crash.
        SearchCategory.entries.forEach { active ->
            val visible = visibleCategories(emptyMap(), active)

            assertTrue(active in visible, "$active must remain cyclable at zero results")
            assertTrue(visible.indexOf(active) >= 0)
        }
    }

    @Test
    fun `chips stay in declaration order`() {
        // The order three surfaces read - chip row, section order, keyboard start. Filtering must
        // not reshuffle it.
        val counts = mapOf(SearchCategory.PAGES to 1, SearchCategory.TOOLS to 1, SearchCategory.FILES to 1)

        val visible = visibleCategories(counts, SearchCategory.ALL)

        assertEquals(visible.sortedBy { it.ordinal }, visible)
        assertEquals(
            listOf(SearchCategory.ALL, SearchCategory.TOOLS, SearchCategory.FILES, SearchCategory.PAGES),
            visible,
        )
    }
}
