package ai.rever.boss.search

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the rules [GlobalSearchService] applies to EVERY query, rather than any one source.
 *
 * The sibling suites each own a slice of the search surface: [GlobalSearchOrderingTest] the
 * category order the dialog draws, [GlobalSearchNewSourcesTest] the four [SearchSources]
 * sources and their fail-alone isolation, [GlobalSearchTabsTest] open tabs,
 * [GlobalSearchFileHighlightTest] the highlight ranges, and [FuzzyMatcherTest] the scorer
 * itself. What none of them pin is the service's own contract - the gates every query passes
 * through, the cap and the bonuses it layers on the matcher's score, the shape of the union
 * it hands back, and the two pure helpers the dialog reads - which is what this file owns.
 *
 * The service is a singleton over [SearchSources], so every test clears the registrations in
 * [beforeTest] and [afterTest], the same convention the sibling suites follow. Files arrive as
 * the [GlobalSearchService.search] parameter and never touch the disk; the sources a test is
 * not exercising stay cleared, which [SearchSources.clearForTests] fixes at empty rather than
 * at the production defaults.
 */
class GlobalSearchServiceTest {
    private companion object {
        const val WINDOW = "window-under-test"
    }

    @BeforeTest
    fun setUp() {
        SearchSources.clearForTests()
    }

    @AfterTest
    fun tearDown() {
        SearchSources.clearForTests()
    }

    private fun search(
        query: String,
        files: List<IndexedFile> = emptyList(),
    ): List<SearchResult> = runBlocking { GlobalSearchService.search(query, WINDOW, files) }

    private fun fileResults(
        query: String,
        files: List<IndexedFile>,
    ): List<SearchResult.FileResult> = search(query, files).filterIsInstance<SearchResult.FileResult>()

    private fun indexedFile(
        name: String,
        relativePath: String = name,
    ) = IndexedFile(name = name, path = "/project/$relativePath", relativePath = relativePath)

    /**
     * Twenty files that all match "match", with a score ladder built into their names: every
     * scoring term except the length bonus is identical across them, so each extra "x" costs
     * exactly one point and the ranking is strictly by name length. That determinism is what
     * lets the cap tests say WHICH rows survive, not merely how many.
     */
    private fun twentyMatchFiles(): List<IndexedFile> = (0..19).map(::matchFile)

    private fun matchFile(index: Int) = indexedFile(name = "match" + "x".repeat(index) + ".kt")

    // --- ranking -------------------------------------------------------------------------------

    @Test
    fun `an exact name match outranks every near miss`() {
        val files =
            listOf(
                indexedFile("BossApp.kt"), // the query itself
                indexedFile("BossApp.kt.old"), // the query, with a suffix
                indexedFile("aBossApp.kt"), // the query, embedded
            )

        val hits = fileResults("BossApp.kt", files)

        assertEquals(3, hits.size, "fuzzy matching reaches all three shapes")
        assertEquals("BossApp.kt", hits.first().name, "an exact match must lead its near misses")
        assertEquals(hits.sortedByDescending { it.score }, hits, "and the rest still rank by score")
    }

    @Test
    fun `a name hit outranks a path-only hit, and a directories-only hit underlines nothing`() {
        // searchFiles gives a name hit +50 and a path hit nothing, so a file you typed by NAME
        // is never buried under one whose DIRECTORY happens to match. A path hit confined to
        // the directories is kept - it is still a way of reaching the file - but has no
        // characters in the name to underline, so its ranges arrive empty.
        val byName = indexedFile(name = "alpha.kt", relativePath = "src/alpha.kt")
        val byPath = indexedFile(name = "zzz.kt", relativePath = "src/alpha/zzz.kt")

        val hits = fileResults("alpha", listOf(byName, byPath))

        assertEquals(listOf("alpha.kt", "zzz.kt"), hits.map { it.name })
        assertTrue(hits.first().score > hits.last().score, "the +50 name bonus is what separates them")
        assertTrue(
            hits.last().matchRanges.isEmpty(),
            "a match confined to the directories has no characters in the name to underline",
        )
    }

    @Test
    fun `a file matches whatever case the query is typed in`() {
        // searchFiles matches against IndexedFile.lowerName, so the query's case is a ranking
        // input, never a gate: exact case earns the matcher's small bonus and nothing more.
        val file = indexedFile("BossApp.kt")

        for (query in listOf("bossapp", "BOSSAPP", "BoSsApP")) {
            assertEquals(
                listOf("BossApp.kt"),
                fileResults(query, listOf(file)).map { it.name },
                "case must not gate matching: '$query'",
            )
        }
    }

    // --- the per-category cap ------------------------------------------------------------------

    @Test
    fun `file matches are capped at fifteen and the best fifteen survive`() {
        val files = twentyMatchFiles()

        val hits = fileResults("match", files)

        assertEquals(15, hits.size, "MAX_RESULTS_PER_CATEGORY is fifteen")
        assertEquals(hits.sortedByDescending { it.score }, hits, "capped or not, files stay score-ordered")
        // The cap keeps the BEST fifteen, so it is the longest - lowest-scoring - names that go:
        val kept = hits.map { it.name }.toSet()
        val dropped = files.map { it.name }.filterNot { it in kept }
        assertEquals(files.takeLast(5).map { it.name }, dropped, "the five dropped rows are the five worst matches")
    }

    @Test
    fun `the cap is per category, so two sources can each contribute fifteen`() {
        // The dialog draws per-category sections, so a GLOBAL cap of fifteen would leave one
        // source with nothing once another spent the allowance. Twenty files and twenty tools
        // under one query pin the cap as per category.
        SearchSources.registerTools(WINDOW) {
            (0..19).map { n -> ToolSearchRecord(panelId = "tool-$n", label = "match" + "x".repeat(n)) }
        }

        val results = search("match", twentyMatchFiles())

        assertEquals(15, results.filterIsInstance<SearchResult.FileResult>().size)
        assertEquals(
            15,
            results.filterIsInstance<SearchResult.ToolResult>().size,
            "fifteen each, not fifteen to share between them",
        )
        val labels = results.filterIsInstance<SearchResult.ToolResult>().map { it.label }
        assertTrue("match" in labels, "the best match survives its cap")
        assertTrue("match" + "x".repeat(19) !in labels, "the worst match is the one dropped")
    }

    // --- the gates every query passes through ---------------------------------------------------

    @Test
    fun `a blank query returns nothing and never reads the sources`() {
        // The blank check is the first thing search() does, before the fan-out and before the
        // one source read that happens outside `isolated()`: SearchSources.tools(windowId). A
        // THROWING supplier there makes the gate's position observable - move the check below
        // that read and this test throws instead of passing.
        SearchSources.registerTools(WINDOW) { error("a blank query must not read the tools source") }
        val files = listOf(indexedFile("match.kt"))

        assertTrue(search("", files).isEmpty(), "the empty string is blank")
        assertTrue(search("   ", files).isEmpty(), "whitespace-only is blank after the trim")
        assertTrue(search("\t", files).isEmpty(), "a tab is blank too")
    }

    @Test
    fun `a trailing space does not drop rows from the fuzzy sources`() {
        // The trim happens once, at the top of search(). Before it did, a paused typist's
        // "git " matched no tool at all - a space is not a subsequence of git_status - while
        // the substring sources kept matching, so trailing whitespace changed WHICH sources
        // had rows. All nine sources have to see the same trimmed query.
        SearchSources.registerTools(WINDOW) {
            listOf(ToolSearchRecord(panelId = "git_status", label = "Git Status"))
        }
        val files = listOf(indexedFile("git.kt"))

        val typed = search("git ", files)
        val finished = search("git", files)

        assertEquals(
            finished.filterIsInstance<SearchResult.ToolResult>(),
            typed.filterIsInstance<SearchResult.ToolResult>(),
            "a trailing space must not drop the tool row",
        )
        assertEquals(
            finished.filterIsInstance<SearchResult.FileResult>(),
            typed.filterIsInstance<SearchResult.FileResult>(),
            "a trailing space must not drop the file row",
        )
        assertTrue(typed.isNotEmpty(), "a paused typist still sees their rows")
    }

    // --- the union's shape ----------------------------------------------------------------------

    @Test
    fun `a file that matches by name is not returned again for its path`() {
        // searchFiles tries the name first and `continue`s past the path scorer on a hit: one
        // row per file, from the scorer that won. Without that precedence the same file would
        // arrive twice - once per scorer - and the dialog would draw it in the list twice.
        val both = indexedFile(name = "match.kt", relativePath = "dir/match.kt")

        val hits = fileResults("match", listOf(both))

        assertEquals(1, hits.size)
        // The row came from the name scorer, +50 and all, not from the path scorer.
        val nameMatch = requireNotNull(FuzzyMatcher.match("match", both.name, both.lowerName))
        assertEquals(nameMatch.score + 50, hits.single().score)
    }

    @Test
    fun `identical index entries are carried through, not deduplicated`() {
        // search()'s KDoc promises "every match from every source", and there is no distinct()
        // on the way through: a duplicated index entry is a duplicated row. If the service is
        // ever made to dedupe, that is a contract change to make in one deliberate place -
        // which is why the passthrough is pinned here rather than left ambiguous.
        val files =
            listOf(
                indexedFile("match.kt"),
                indexedFile("match.kt"),
            )

        assertEquals(2, fileResults("match", files).size)
    }

    // --- the pure helpers ----------------------------------------------------------------------

    /**
     * Six rows out of five categories, deliberately unordered, with a score tie inside TOOLS.
     * Built by hand because these tests pin the pure helpers, not the sources: every field
     * that is not score or category is irrelevant to them.
     */
    private fun mixedUnion(): List<SearchResult> =
        listOf(
            SearchResult.FileResult(
                name = "a.kt",
                path = "/a.kt",
                relativePath = "a.kt",
                score = 5,
                matchRanges = emptyList(),
            ),
            SearchResult.ToolResult(panelId = "t2", label = "T2", score = 10),
            SearchResult.PageResult(url = "https://example.com", title = "Example", score = 10),
            SearchResult.SettingResult(
                section = "THEME",
                pluginPageId = null,
                panelId = null,
                group = null,
                label = "Theme",
                breadcrumb = "Appearance",
                highlightable = true,
                score = 10,
            ),
            SearchResult.FileResult(
                name = "b.kt",
                path = "/b.kt",
                relativePath = "b.kt",
                score = 99,
                matchRanges = emptyList(),
            ),
            SearchResult.ToolResult(panelId = "t1", label = "T1", score = 10),
        )

    @Test
    fun `getFilteredResults groups by category, ranks by score and keeps union order on ties`() {
        val union = mixedUnion()

        val ordered = GlobalSearchService.getFilteredResults(union, SearchCategory.ALL)

        // TOOLS(1) then SETTINGS(2) then FILES(4) then PAGES(9): declaration order, as
        // GlobalSearchOrderingTest pins at the search level. What is new here is the third
        // rule: with scores tied, the union's own order decides, because sortedWith is stable -
        // t2 precedes t1 in the input and keeps that lead.
        assertEquals(
            listOf(
                SearchCategory.TOOLS,
                SearchCategory.TOOLS,
                SearchCategory.SETTINGS,
                SearchCategory.FILES,
                SearchCategory.FILES,
                SearchCategory.PAGES,
            ),
            ordered.map { it.category },
        )
        assertEquals(
            listOf("t2", "t1"),
            ordered.filterIsInstance<SearchResult.ToolResult>().map { it.panelId },
            "equal scores keep the union's own order",
        )
        assertEquals(
            listOf("b.kt", "a.kt"),
            ordered.filterIsInstance<SearchResult.FileResult>().map { it.name },
            "score, not input order, decides within a category",
        )
        // The keyboard and the drawn list both read this function, so its output has to be a
        // function of its input alone: the same union, ordered identically, on every call.
        assertEquals(
            ordered,
            GlobalSearchService.getFilteredResults(union, SearchCategory.ALL),
            "ordering must be deterministic",
        )
    }

    @Test
    fun `getResultCounts totals the union and counts every category`() {
        val union = mixedUnion()

        val counts = GlobalSearchService.getResultCounts(union)

        // Every chip gets a key, even the categories holding nothing: the chip renders a
        // zero, not an absence.
        assertEquals(SearchCategory.entries.toSet(), counts.keys)
        assertEquals(6, counts[SearchCategory.ALL], "ALL totals the whole union rather than one category")
        assertEquals(2, counts[SearchCategory.TOOLS])
        assertEquals(1, counts[SearchCategory.SETTINGS])
        assertEquals(2, counts[SearchCategory.FILES])
        assertEquals(1, counts[SearchCategory.PAGES])
        assertEquals(0, counts[SearchCategory.TABS])
        assertEquals(0, counts[SearchCategory.COMMANDS])
        // The count and the filtered list have to agree, or the chip advertises rows its own
        // filter hides.
        for (category in SearchCategory.entries) {
            if (category == SearchCategory.ALL) continue
            assertEquals(
                GlobalSearchService.getFilteredResults(union, category).size,
                counts.getValue(category),
                "the ${category.displayName} chip count must match its filtered list",
            )
        }
    }

    // --- the empty answers ---------------------------------------------------------------------

    @Test
    fun `a query that matches nothing is a clean empty list`() {
        // "zzqq" clears no fuzzy bar anywhere: no KeymapActions description or action id
        // contains two z's, or both a z and a q, so even the always-on COMMANDS source - the
        // one no test can switch off - cannot match it. That is what lets this pin the WHOLE
        // union rather than one source's slice of it. A live tools registration proves the
        // empty came from matching, not from the sources being absent or the query blank.
        SearchSources.registerTools(WINDOW) { listOf(ToolSearchRecord(panelId = "bookmarks", label = "Bookmarks")) }

        val results = search("zzqq", listOf(indexedFile("match.kt")))

        assertTrue(results.isEmpty(), "no match anywhere: an empty list, never null, never a throw")
    }
}
