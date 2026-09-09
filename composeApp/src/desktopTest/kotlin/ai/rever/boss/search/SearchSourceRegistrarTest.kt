package ai.rever.boss.search

import ai.rever.boss.components.settings.search.SettingsSearchIndex
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the REAL settings registrar, not a hand-rolled stand-in.
 *
 * [GlobalSearchNewSourcesTest] builds its own entry-to-record mapping so it can control what is
 * indexed, which leaves the production mapping - the thing that actually runs - untested. That is a
 * gap with teeth: transposing `group` and `breadcrumb`, or dropping `panelId`, would keep that
 * whole suite green while the shipped app silently lost signposts or highlighted the wrong row,
 * because a search index is the one place staleness and misrouting are invisible.
 *
 * The two MCP tests touch `McpToolRegistryImpl`, which forces the `~/.boss` disabled-tools read
 * that `SearchSources`' KDoc cites as a reason for the seam. That is accepted here and only here:
 * it is read-only, and a test whose whole purpose is the unregistered production path cannot
 * substitute the thing it is testing. The recent-pages half would also WRITE, which is why
 * `settingsFile` is redirected in `setUp`.
 *
 * **Order- and fork-sensitive by construction.** It mutates process-global state - `settingsFile`,
 * a real MCP provider, the real settings supplier - and restores it in `finally`/`tearDown`, which
 * is correct in one JVM running tests in sequence. Raising `maxParallelForks`, or running these
 * concurrently with anything else that reads those singletons, would make it flaky. That is the
 * cost of covering a production path built out of singletons, and it is worth paying here rather
 * than in a postmortem.
 *
 * Asserts against a real built-in row rather than a fixture, so a rename in
 * `SettingsSearchEntries.kt` that this file does not follow shows up here. `SettingsSearchIndexDriftTest`
 * already guarantees the label exists; this guarantees it survives the trip into the global search.
 */
class SearchSourceRegistrarTest {
    private companion object {
        const val PROBE_PAGE = "https://registrar-probe.example.com/page"
        const val RECORD_TIMEOUT_MS = 5_000L
    }

    private lateinit var realPagesFile: File
    private lateinit var tempPagesFile: File

    @BeforeTest
    fun setUp() {
        // RecentBrowserPagesManager persists, and its own KDoc says settingsFile is "overridable so
        // tests exercise the real read/write path without touching ~/.boss". The recent-pages test
        // below records a probe page, so without this it queues a write of that probe into the
        // developer's real profile - and `removePage` saves immediately, so a mistimed cleanup
        // could leave it there for good.
        //
        // The object's own initialiser has already read the real file by the time this runs; that
        // read is harmless and unavoidable for a singleton. What matters is that no WRITE lands
        // there.
        realPagesFile = RecentBrowserPagesManager.settingsFile
        tempPagesFile =
            kotlin.io.path
                .createTempDirectory("registrar-test")
                .toFile()
                .resolve("recent-browser-pages.json")
        RecentBrowserPagesManager.settingsFile = tempPagesFile

        // Empty suppliers for MCP and pages, which is right for the tests that want a source
        // absent and wrong for the three below, which exist to exercise the production default.
        // Those re-clear with useProductionDefaults for themselves.
        SearchSources.clearForTests()
        GlobalSearchService.clearResults()
        GlobalSearchService.clearIndex()
        GlobalSearchService.setActiveCategory(SearchCategory.ALL)
    }

    @AfterTest
    fun tearDown() {
        RecentBrowserPagesManager.settingsFile = realPagesFile
        tempPagesFile.parentFile?.deleteRecursively()

        SearchSources.clearForTests()
        GlobalSearchService.clearResults()
        GlobalSearchService.setActiveCategory(SearchCategory.ALL)
    }

    @Test
    fun `the real registrar carries a built-in row through with its section and breadcrumb`() {
        SettingsSearchIndex.registerWithGlobalSearch()

        val hit =
            runBlocking { GlobalSearchService.search("show title bar", windowId = null) }
                .filterIsInstance<SearchResult.SettingResult>()
                .firstOrNull { it.label == "Show Title Bar" }

        assertNotNull(hit, "the registrar must deliver a known built-in setting")
        // The two fields the window navigates and highlights by. Swapping them is the failure this
        // test exists for: search would still find the row and then land on the wrong page.
        assertEquals("WINDOW_APPEARANCE", hit.section)
        assertEquals("Title Bar", hit.group)
        assertEquals("Appearance > Title Bar", hit.breadcrumb)
        assertEquals(null, hit.pluginPageId)
        assertEquals(null, hit.panelId)
        assertTrue(hit.highlightable, "a real host control can be pointed at")
    }

    @Test
    fun `the real registrar ranks with the settings matcher, so a multi-word query works`() {
        // The reason the registrar hands over a function instead of rows. FuzzyMatcher cannot match
        // "title bar" against "Show Title Bar" as one target in a way that beats nothing - the
        // tokenised matcher can, and this proves the shipped wiring uses it.
        SettingsSearchIndex.registerWithGlobalSearch()

        val labels =
            runBlocking { GlobalSearchService.search("title bar", windowId = null) }
                .filterIsInstance<SearchResult.SettingResult>()
                .map { it.label }

        assertTrue(labels.isNotEmpty(), "a two-word query has to reach the settings index")
        assertTrue(labels.any { it == "Show Title Bar" })
    }

    @Test
    fun `a keyword on a real entry reaches its setting`() {
        // "passkey" -> "Platform Authenticator" is the canonical case the keywords exist for, and
        // it goes through the production keyword list rather than a fixture's.
        SettingsSearchIndex.registerWithGlobalSearch()

        val labels =
            runBlocking { GlobalSearchService.search("passkey", windowId = null) }
                .filterIsInstance<SearchResult.SettingResult>()
                .map { it.label }

        assertTrue("Platform Authenticator" in labels)
    }

    // --- the two sources that default to a singleton -------------------------------------------
    //
    // These drive the PRODUCTION path with nothing registered, which is the gap that let a real
    // regression ship: every other MCP and pages test installs its own fake, so when the
    // fall-back to `defaultMcpTools`/`defaultRecentPages` was lost in a bad edit, both sources
    // returned nothing in the shipped app and the whole suite stayed green. A test that registers
    // no override is the only one that can see that.

    @Test
    fun `with no override registered, MCP tools come from the real registry`() {
        val provider =
            object : McpToolProvider {
                override val providerId = "registrar-test"

                override fun tools() =
                    listOf(
                        McpToolDefinition(
                            name = "registrar_probe",
                            description = "a tool the default supplier must surface",
                            handler = McpToolHandler { McpToolResult("ok") },
                        ),
                    )
            }
        SearchSources.clearForTests(useProductionDefaults = true)
        McpToolRegistryImpl.registerProvider(provider)
        try {
            // Deliberately NO registerMcpTools call. Absent an override, the accessor has to reach
            // the registry - if it falls back to empty instead, this is the only failing test.
            val names = SearchSources.mcpTools().map { it.name }

            assertTrue("registrar_probe" in names, "the default supplier must read the real registry")
        } finally {
            McpToolRegistryImpl.unregisterProvider("registrar-test")
        }
    }

    @Test
    fun `with no override registered, an MCP tool is searchable end to end`() {
        val provider =
            object : McpToolProvider {
                override val providerId = "registrar-test"

                override fun tools() =
                    listOf(
                        McpToolDefinition(
                            name = "registrar_probe",
                            description = "a tool the default supplier must surface",
                            handler = McpToolHandler { McpToolResult("ok") },
                        ),
                    )
            }
        SearchSources.clearForTests(useProductionDefaults = true)
        McpToolRegistryImpl.registerProvider(provider)
        try {
            val hits =
                runBlocking { GlobalSearchService.search("registrar_probe", windowId = null) }
                    .filterIsInstance<SearchResult.McpToolResult>()

            assertEquals(listOf("registrar_probe"), hits.map { it.name })
        } finally {
            McpToolRegistryImpl.unregisterProvider("registrar-test")
        }
    }

    @Test
    fun `with no override registered, recent pages come from the real manager`() {
        SearchSources.clearForTests(useProductionDefaults = true)
        RecentBrowserPagesManager.recordPageVisit(
            url = PROBE_PAGE,
            title = "Registrar Probe Page",
        )
        try {
            // recordPageVisit dispatches its update into a coroutine on IO and returns, so the
            // page is not in the flow yet. Waiting on the state rather than assuming the nine
            // async hops of a search are enough slack - and if the update landed after the
            // `finally`, the cleanup would run first and leave the probe behind.
            runBlocking {
                withTimeout(RECORD_TIMEOUT_MS) {
                    RecentBrowserPagesManager.recentPages.first { pages -> pages.any { it.url == PROBE_PAGE } }
                }
            }
            val hits =
                runBlocking { GlobalSearchService.search("registrar probe", windowId = null) }
                    .filterIsInstance<SearchResult.PageResult>()

            assertTrue(
                hits.any { it.url == PROBE_PAGE },
                "the default supplier must read the real recent-pages manager",
            )
        } finally {
            RecentBrowserPagesManager.removePage(PROBE_PAGE)
        }
    }

    @Test
    fun `an unregistered settings source contributes nothing rather than failing the search`() {
        // The startup state. Registering is a single call in main(), so the failure mode if it is
        // ever dropped is silence - worth pinning that it is silence and not a crash.
        val results = runBlocking { GlobalSearchService.search("show title bar", windowId = null) }

        assertTrue(results.filterIsInstance<SearchResult.SettingResult>().isEmpty())
    }
}
