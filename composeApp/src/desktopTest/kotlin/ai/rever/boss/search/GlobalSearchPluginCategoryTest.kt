package ai.rever.boss.search

import ai.rever.boss.plugin.api.PluginSearchResult
import ai.rever.boss.plugin.api.SearchProvider
import ai.rever.boss.plugin.api.SearchResultAction
import ai.rever.boss.plugin.api.SearchResultIcon
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogLevel
import ai.rever.boss.utils.logging.LogListener
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A plugin's `PluginSearchResult.category` used to convert only when it said "bookmarks" -
 * every other known category was mapped then dropped (`else -> null`), and an unknown one
 * silently defaulted to bookmarks first, so a plugin returning file or command hits read as
 * zero results to the caller. These tests pin that each known category produces its real
 * [SearchResult] type and that an unknown category is dropped WITH a warning naming it.
 */
class GlobalSearchPluginCategoryTest {
    @BeforeTest
    fun setUp() {
        SearchSources.clearForTests()
    }

    @AfterTest
    fun tearDown() {
        SearchSources.clearForTests()
        SearchRegistryImpl.unregisterProvider(PROVIDER_ID)
    }

    @Test
    fun `a plugin files result is returned, not silently dropped`() =
        runBlocking {
            registerProvider(
                pluginResult(
                    id = "file-1",
                    category = "files",
                    action = SearchResultAction.OpenFile("/projects/a/alpha.kt"),
                ),
            )

            val hits = search("alpha")

            val file = assertIs<SearchResult.FileResult>(providerHits(hits).single())
            assertEquals("/projects/a/alpha.kt", file.path)
            assertEquals(SearchCategory.FILES, file.category)
        }

    @Test
    fun `plugin tabs run configs and commands results all survive conversion`() =
        runBlocking {
            registerProvider(
                pluginResult(
                    id = "tab-1",
                    category = "tabs",
                    metadata = mapOf("tabId" to "tab-1", "windowId" to "w1", "panelId" to "p1"),
                ),
                pluginResult(
                    id = "run-1",
                    category = "run_configs",
                    metadata = mapOf("configId" to "run-1", "language" to "kotlin"),
                ),
                pluginResult(
                    id = "cmd-1",
                    category = "commands",
                    metadata = mapOf("actionId" to "cmd-1"),
                ),
            )

            val hits = providerHits(search("probe"))

            assertIs<SearchResult.TabResult>(hits.single { it.category == SearchCategory.TABS })
            assertIs<SearchResult.RunConfigResult>(hits.single { it.category == SearchCategory.RUN_CONFIGS })
            val command = assertIs<SearchResult.CommandResult>(hits.single { it.category == SearchCategory.COMMANDS })
            assertEquals("cmd-1", command.actionId)
        }

    @Test
    fun `a plugin bookmarks result still converts`() =
        runBlocking {
            registerProvider(
                pluginResult(
                    id = "bm-1",
                    category = "bookmarks",
                    action = SearchResultAction.OpenUrl("https://example.com/probe"),
                ),
            )

            val hits = providerHits(search("probe"))

            val bookmark = assertIs<SearchResult.BookmarkResult>(hits.single())
            assertEquals("https://example.com/probe", bookmark.url)
        }

    @Test
    fun `an unknown plugin category is dropped with a warning naming it`() =
        runBlocking {
            val warnings = mutableListOf<LogEntry>()
            val listener =
                LogListener { entry ->
                    if (entry.level == LogLevel.WARN && entry.message == "Dropping a plugin search result") {
                        warnings.add(entry)
                    }
                }
            BossLogger.addListener(listener)
            try {
                registerProvider(pluginResult(id = "odd-1", category = "spaceships"))

                val hits = providerHits(search("probe"))

                assertTrue(hits.isEmpty(), "an unknown category must not default to bookmarks")
                val warning = warnings.single()
                assertEquals("spaceships", warning.data?.get("category"))
                assertEquals(PROVIDER_ID, warning.data?.get("providerId"))
            } finally {
                BossLogger.removeListener(listener)
            }
        }

    private fun pluginResult(
        id: String,
        category: String,
        action: SearchResultAction = SearchResultAction.Custom("none", emptyMap()),
        metadata: Map<String, String> = emptyMap(),
    ) = PluginSearchResult(
        id = id,
        title = "probe-$id",
        subtitle = "",
        icon = SearchResultIcon.Emoji("x"),
        category = category,
        providerId = PROVIDER_ID,
        action = action,
        score = 50,
        matchRanges = emptyList(),
        metadata = metadata,
    )

    private fun registerProvider(vararg results: PluginSearchResult) {
        SearchRegistryImpl.registerProvider(
            object : SearchProvider {
                override val providerId = PROVIDER_ID
                override val displayName = PROVIDER_ID

                override suspend fun search(
                    query: String,
                    limit: Int,
                ): List<PluginSearchResult> = results.toList()
            },
        )
    }

    private suspend fun search(query: String): List<SearchResult> =
        GlobalSearchService.search(query, windowId = null, indexedFiles = emptyList())

    /** Only this test's provider emits `probe-` titles; the other sources are filtered out. */
    private fun providerHits(all: List<SearchResult>) = all.filter { it.displayName.startsWith("probe-") }

    private companion object {
        const val PROVIDER_ID = "category-test-provider"
    }
}
