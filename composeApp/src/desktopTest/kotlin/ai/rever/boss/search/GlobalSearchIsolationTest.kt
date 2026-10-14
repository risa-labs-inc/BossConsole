package ai.rever.boss.search

import ai.rever.boss.components.dialogs.SpotlightDialogState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalSearchIsolationTest {
    @BeforeTest
    fun setUp() {
        SearchSources.clearForTests()
    }

    @AfterTest
    fun tearDown() {
        SearchSources.clearForTests()
    }

    @Test
    fun `each search sees only its supplied project snapshot`() =
        runBlocking {
            val projectA = listOf(indexedFile("/projects/a", "alpha.kt"))
            val projectB = listOf(indexedFile("/projects/b", "beta.kt"))

            val aResults = GlobalSearchService.search("alpha", "window-a", projectA).fileResults()
            val bResults = GlobalSearchService.search("beta", "window-b", projectB).fileResults()

            assertEquals(listOf("/projects/a/alpha.kt"), aResults.map { it.path })
            assertEquals(listOf("/projects/b/beta.kt"), bResults.map { it.path })
            assertTrue(aResults.none { it.path.startsWith("/projects/b") })
            assertTrue(bResults.none { it.path.startsWith("/projects/a") })
        }

    @Test
    fun `dialog states do not share results categories or selection`() {
        val first = SpotlightDialogState()
        val second = SpotlightDialogState()
        val firstResult = SearchResult.FileResult("alpha.kt", "/a/alpha.kt", "alpha.kt", 10, emptyList())
        val secondResult = SearchResult.FileResult("beta.kt", "/b/beta.kt", "beta.kt", 20, emptyList())

        first.query = "alpha"
        first.results = listOf(firstResult)
        first.activeCategory = SearchCategory.FILES
        first.selectedIndex = 0
        first.isSearching = true

        second.query = "beta"
        second.results = listOf(secondResult)
        second.activeCategory = SearchCategory.ALL
        second.selectedIndex = 0

        assertEquals("alpha", first.query)
        assertEquals(listOf(firstResult), first.results)
        assertEquals(SearchCategory.FILES, first.activeCategory)
        assertTrue(first.isSearching)
        assertEquals("beta", second.query)
        assertEquals(listOf(secondResult), second.results)
        assertEquals(SearchCategory.ALL, second.activeCategory)
        assertEquals(0, second.selectedIndex)
    }

    @Test
    fun `dialog receives a window-owned indexer instead of constructing one per open`() {
        val dialogSource =
            File("src/commonMain/kotlin/ai/rever/boss/components/dialogs/GlobalSearchDialog.kt").readText()
        val hostSource = File("src/commonMain/kotlin/ai/rever/boss/app/BossAppDialogs.kt").readText()

        assertTrue(dialogSource.contains("fileIndexer: FileIndexer"))
        assertTrue(dialogSource.contains("remember(projectPath) { SpotlightDialogState() }"))
        assertFalse(dialogSource.contains("fileIndexer.indexProject("))
        assertTrue(hostSource.contains("onIndexProject = { state.spotlightFileIndexes.ensureIndexed"))
        assertFalse(dialogSource.contains("FileIndexer("))
        val ownerLookup = "rememberSpotlightFileIndexer(state.spotlightFileIndexes, selectedProject.path)"
        assertTrue(hostSource.contains("val spotlightFileIndexer = $ownerLookup"))
        assertTrue(hostSource.contains("fileIndexer = spotlightFileIndexer"))
        assertFalse(hostSource.contains("state.spotlightFileIndexes.indexerFor("))
    }

    private fun List<SearchResult>.fileResults() = filterIsInstance<SearchResult.FileResult>()

    private fun indexedFile(
        project: String,
        name: String,
    ) = IndexedFile(name = name, path = "$project/$name", relativePath = name)
}
