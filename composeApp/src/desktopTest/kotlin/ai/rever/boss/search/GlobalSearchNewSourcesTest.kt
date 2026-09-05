package ai.rever.boss.search

import ai.rever.boss.components.settings.search.SettingsSearchEntry
import ai.rever.boss.components.settings.search.SettingsSearchMatcher
import ai.rever.boss.components.settings.sidebar.SettingsSection
import ai.rever.boss.plugin.api.PanelId
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the four sources the double-shift search grew: tools, settings, MCP tools and recent pages.
 *
 * All four arrive through [SearchSources], and that indirection is the thing most likely to break
 * silently: nothing fails to compile when a supplier is never registered, the search simply returns
 * fewer kinds of result than it should, and the only symptom is a thing that exists and cannot be
 * found.
 *
 * The service is a singleton, so each test registers what it needs and clears afterwards.
 */
class GlobalSearchNewSourcesTest {
    private companion object {
        /** Tools are per window, so every registration and every search names one. */
        const val WINDOW = "window-under-test"
    }

    @BeforeTest
    fun setUp() {
        SearchSources.clearForTests()
        GlobalSearchService.clearResults()
        // Files leak in from whatever indexed a project earlier in this JVM otherwise, and these
        // tests read whole result lists in places.
        GlobalSearchService.clearIndex()
        GlobalSearchService.setActiveCategory(SearchCategory.ALL)
    }

    @AfterTest
    fun tearDown() {
        SearchSources.clearForTests()
        GlobalSearchService.clearResults()
        GlobalSearchService.setActiveCategory(SearchCategory.ALL)
    }

    private fun searchFor(query: String): List<SearchResult> = runBlocking { GlobalSearchService.search(query, WINDOW) }

    private inline fun <reified T : SearchResult> resultsOf(q: String) = searchFor(q).filterIsInstance<T>()

    private fun tool(
        panelId: String,
        label: String,
    ) = ToolSearchRecord(panelId = panelId, label = label)

    private fun registerTools(vararg tools: ToolSearchRecord) {
        SearchSources.registerTools(WINDOW) { tools.toList() }
    }

    private fun entry(
        label: String,
        section: SettingsSection? = SettingsSection.THEME,
        keywords: List<String> = emptyList(),
        group: String? = null,
        pluginPageId: String? = null,
        panelId: String? = null,
        highlightable: Boolean = true,
    ) = SettingsSearchEntry(
        label = label,
        section = if (pluginPageId == null && panelId == null) section else null,
        group = group,
        keywords = keywords,
        pluginPageId = pluginPageId,
        panel = panelId?.let { PanelId(it, 0) },
        highlightable = highlightable,
        curated = true,
    )

    /**
     * Register [entries] the way the desktop side really does - through [SettingsSearchMatcher].
     *
     * Not a hand-rolled scorer: the whole point of registering a ranking function rather than rows
     * is that settings relevance has one definition, so a test that scored them itself would be
     * pinning something the app does not do.
     */
    private fun registerSettings(vararg entries: SettingsSearchEntry) {
        SearchSources.registerSettingsSearch { query ->
            SettingsSearchMatcher.search(query, entries.toList()).map { hit ->
                SettingSearchRecord(
                    label = hit.entry.label,
                    breadcrumb = hit.entry.breadcrumb,
                    section = hit.entry.section?.name,
                    pluginPageId = hit.entry.pluginPageId,
                    panelId = hit.entry.panel?.panelId,
                    group = hit.entry.group,
                    highlightable = hit.entry.highlightable,
                    score = hit.score,
                )
            }
        }
    }

    // --- tools ---------------------------------------------------------------------------------

    @Test
    fun `a tool is found by its label`() {
        registerTools(tool("bookmarks", "Bookmarks"))

        assertEquals(listOf("bookmarks"), resultsOf<SearchResult.ToolResult>("bookmark").map { it.panelId })
    }

    @Test
    fun `a tool is found by its panel id`() {
        // The id is what a plugin's own docs and its MCP tools call it, so it has to match too.
        registerTools(tool("run-configurations", "Runners"))

        assertTrue(resultsOf<SearchResult.ToolResult>("run-config").isNotEmpty())
    }

    @Test
    fun `a tool nobody matched is not returned`() {
        registerTools(tool("bookmarks", "Bookmarks"))

        assertTrue(resultsOf<SearchResult.ToolResult>("zzzz").isEmpty())
    }

    @Test
    fun `no registered tools source contributes nothing rather than failing`() {
        // The state during startup, and in every test that does not care about tools. A missing
        // source has to be silent: the search still has eight other kinds of result to return.
        assertTrue(resultsOf<SearchResult.ToolResult>("bookmark").isEmpty())
    }

    @Test
    fun `a window searches its own tools, not another window's`() {
        // One slot could not do this. The first version overwrote on registration and cleared on
        // dispose, so two windows with a dialog open fought over it: one searched the other's
        // tools, and closing the newer dialog left the older window with none for the rest of the
        // session. Keyed per window, each sees exactly its own - which matters because the panel
        // it offers is opened through THAT window's component.
        SearchSources.registerTools("other-window") { listOf(tool("docker", "Docker")) }
        registerTools(tool("bookmarks", "Bookmarks"))

        assertEquals(listOf("bookmarks"), resultsOf<SearchResult.ToolResult>("bookmark").map { it.panelId })
        assertTrue(resultsOf<SearchResult.ToolResult>("docker").isEmpty(), "another window's tools are not ours")
    }

    @Test
    fun `one window closing leaves another window's tools alone`() {
        registerTools(tool("bookmarks", "Bookmarks"))
        SearchSources.registerTools("other-window") { listOf(tool("docker", "Docker")) }

        SearchSources.unregisterTools("other-window")

        assertTrue(resultsOf<SearchResult.ToolResult>("bookmark").isNotEmpty(), "our tools survive their close")
    }

    // --- settings ------------------------------------------------------------------------------

    @Test
    fun `a setting is found by its label`() {
        registerSettings(entry(label = "Show Title Bar"))

        assertEquals(listOf("Show Title Bar"), resultsOf<SearchResult.SettingResult>("title bar").map { it.label })
    }

    @Test
    fun `a multi-word query reaches a setting no single-target matcher could`() {
        // Why the desktop side registers a ranking function instead of rows. FuzzyMatcher is a
        // strict subsequence matcher over ONE target, so match("user agent", "Browser Identity")
        // is null and scoring here missed the setting entirely. SettingsSearchMatcher tokenises,
        // which is what makes the natural query work - and this is the test that would have caught
        // the divergence.
        registerSettings(entry(label = "Browser Identity", keywords = listOf("user", "agent")))

        assertTrue(resultsOf<SearchResult.SettingResult>("user agent").isNotEmpty())
    }

    @Test
    fun `every token has to match, so a second word narrows`() {
        // The conjunction the matcher documents: typing more must not grow the list.
        registerSettings(entry(label = "Show Title Bar"))

        assertTrue(resultsOf<SearchResult.SettingResult>("title").isNotEmpty())
        assertTrue(resultsOf<SearchResult.SettingResult>("title zzzz").isEmpty())
    }

    @Test
    fun `a keyword finds a setting whose label does not contain it`() {
        // The reason keywords exist: "passkey" has to reach "Platform Authenticator".
        registerSettings(entry(label = "Platform Authenticator", keywords = listOf("passkey")))

        assertTrue(resultsOf<SearchResult.SettingResult>("passkey").isNotEmpty())
    }

    @Test
    fun `a label hit outranks a keyword hit`() {
        // A keyword is a way in, not a second name. A row actually called what you typed wins.
        registerSettings(
            entry(label = "Passkeys"),
            entry(label = "Platform Authenticator", keywords = listOf("passkeys")),
        )

        assertEquals("Passkeys", resultsOf<SearchResult.SettingResult>("passkeys").first().label)
    }

    @Test
    fun `a plugin page carries its page id and cannot be highlighted`() {
        registerSettings(entry(label = "AI Gateway", pluginPageId = "ai-gateway", highlightable = false))

        val hit = resultsOf<SearchResult.SettingResult>("gateway").first()
        assertEquals("ai-gateway", hit.pluginPageId)
        assertEquals(null, hit.section)
        assertTrue(!hit.highlightable, "a plugin page has no host control to point at")
    }

    // --- signposts: settings rows that point out of the window ---------------------------------

    @Test
    fun `a signpost is offered when its panel is present, and carries the panel id`() {
        // "AI Providers" moved into the Secret Manager panel and the row keeps the words a user
        // still types for it. The dialog routes on panelId, so it has to survive the flattening.
        registerTools(tool("secret-manager", "Secret Manager"))
        registerSettings(
            entry(
                label = "AI Providers",
                keywords = listOf("anthropic"),
                panelId = "secret-manager",
                highlightable = false,
            ),
        )

        val hit = resultsOf<SearchResult.SettingResult>("anthropic").first()
        assertEquals("secret-manager", hit.panelId)
        assertEquals(null, hit.section)
        assertEquals(null, hit.pluginPageId)
    }

    @Test
    fun `a signpost whose panel is absent is not offered at all`() {
        // withoutUnreachableSignposts states the intent: someone who never installed Secret
        // Manager should find NOTHING for "anthropic", rather than a row that closes the dialog
        // having done nothing. The Settings window filtered its own box and this surface did not,
        // so the two disagreed about the one entry type that can be dead.
        registerSettings(
            entry(
                label = "AI Providers",
                keywords = listOf("anthropic"),
                panelId = "secret-manager",
                highlightable = false,
            ),
        )

        assertTrue(resultsOf<SearchResult.SettingResult>("anthropic").isEmpty())
    }

    @Test
    fun `the reachability test is the one activation uses`() {
        // Registered tools, not some other notion of "installed": activatePlugin matches
        // itemsBySlot on panelId and SearchSources.tools is that list. A signpost pointing at a
        // panel this window does not have is dropped even though another window has it.
        SearchSources.registerTools("other-window") { listOf(tool("secret-manager", "Secret Manager")) }
        registerSettings(
            entry(
                label = "AI Providers",
                keywords = listOf("anthropic"),
                panelId = "secret-manager",
                highlightable = false,
            ),
        )

        assertTrue(resultsOf<SearchResult.SettingResult>("anthropic").isEmpty())
    }

    // --- MCP tools -----------------------------------------------------------------------------

    @Test
    fun `an MCP tool is found by name and reports whether it is switched off`() {
        SearchSources.registerMcpTools {
            listOf(
                McpToolSearchRecord("git_status", "git", "Working tree status", enabled = true),
                McpToolSearchRecord("git_diff", "git", "Show a diff", enabled = false),
            )
        }

        val hits = resultsOf<SearchResult.McpToolResult>("git_diff")
        assertEquals(listOf("git_diff"), hits.map { it.name })
        assertTrue(!hits.first().enabled, "a disabled tool is found and says so")
    }

    @Test
    fun `a tool the host withheld is not searchable`() {
        // The RBAC boundary, from this side. The host supplies only permitted tools - see
        // defaultMcpTools - so a name and a full description that never arrive here
        // cannot be enumerated by typing. Withholding has to mean invisible, not merely unranked.
        SearchSources.registerMcpTools {
            listOf(McpToolSearchRecord("git_status", "git", "Working tree status", enabled = true))
        }

        assertTrue(resultsOf<SearchResult.McpToolResult>("secret_read").isEmpty())
    }

    @Test
    fun `a short query does not match every tool by its description`() {
        // FuzzyMatcher accepts any in-order subsequence, so "abc" hits almost any paragraph. Those
        // rows still drew an "MCP Tools" section header full of rows that cannot be activated. A
        // score floor could not fix it - word-boundary and start-of-string bonuses push scattered
        // initials on prose into the sixties - so a description hit has to CONTAIN what was typed.
        SearchSources.registerMcpTools {
            listOf(
                McpToolSearchRecord(
                    name = "unrelated_tool",
                    providerId = "p",
                    description = "Applies a broad configuration change to every connected resource",
                    enabled = true,
                ),
            )
        }

        assertTrue(resultsOf<SearchResult.McpToolResult>("abc").isEmpty(), "scattered initials are not a hit")
        // A phrase actually in the description still works, which is what the field is there for.
        assertTrue(resultsOf<SearchResult.McpToolResult>("broad configuration").isNotEmpty())
    }

    @Test
    fun `an explicitly empty MCP source contributes nothing`() {
        // What clearForTests installs. Phrased as "empty" and not "unregistered" on purpose:
        // unregistered means "use the production default", and a test asserting that absence is
        // empty would pin the very bug that shipped once - see SearchSourceRegistrarTest.
        SearchSources.registerMcpTools { emptyList() }

        assertTrue(resultsOf<SearchResult.McpToolResult>("git").isEmpty())
    }

    // --- recent pages --------------------------------------------------------------------------

    @Test
    fun `a recent page is found by its title`() {
        SearchSources.registerRecentPages {
            listOf(PageSearchRecord(url = "https://example.com/docs", title = "Getting Started"))
        }

        assertEquals(
            listOf("https://example.com/docs"),
            resultsOf<SearchResult.PageResult>("getting started").map { it.url },
        )
    }

    @Test
    fun `a recent page is found by its domain, which its title may never mention`() {
        SearchSources.registerRecentPages {
            listOf(PageSearchRecord(url = "https://github.com/risa-labs-inc/BossConsole", title = "Pull requests"))
        }

        assertTrue(resultsOf<SearchResult.PageResult>("github").isNotEmpty())
    }

    @Test
    fun `an explicitly empty recent-pages source contributes nothing`() {
        SearchSources.registerRecentPages { emptyList() }

        assertTrue(resultsOf<SearchResult.PageResult>("github").isEmpty())
    }

    // --- one source failing must not take the search down --------------------------------------

    @Test
    fun `a throwing source fails alone`() {
        // SearchSources promises "a missing source returns no results rather than failing the whole
        // search", and awaitAll rethrows the first failure while cancelling its siblings - so
        // without per-source isolation one bad supplier took all nine down and the exception
        // escaped into the dialog's LaunchedEffect.
        registerTools(tool("bookmarks", "Bookmarks"))
        registerSettings(entry(label = "Bookmarks Bar"))
        SearchSources.registerMcpTools { error("this source is broken") }

        val results = searchFor("bookmark")

        assertTrue(results.filterIsInstance<SearchResult.ToolResult>().isNotEmpty(), "tools survive")
        assertTrue(results.filterIsInstance<SearchResult.SettingResult>().isNotEmpty(), "settings survive")
        assertTrue(results.filterIsInstance<SearchResult.McpToolResult>().isEmpty(), "the broken one yields nothing")
    }

    @Test
    fun `a source throwing an unloaded-class Error also fails alone`() {
        // The case the guard exists for and the one it originally missed: a plugin's classloader
        // going while its objects are still referenced throws NoClassDefFoundError, an Error and
        // not an Exception, so catching Exception alone left this propagating.
        registerTools(tool("bookmarks", "Bookmarks"))
        SearchSources.registerRecentPages { throw NoClassDefFoundError("ai/rever/boss/plugin/Gone") }

        val results = searchFor("bookmark")

        assertTrue(results.filterIsInstance<SearchResult.ToolResult>().isNotEmpty(), "tools survive")
        assertTrue(results.filterIsInstance<SearchResult.PageResult>().isEmpty())
    }

    // --- shape ---------------------------------------------------------------------------------

    @Test
    fun `every result reports the category its chip filters on`() {
        registerTools(tool("bookmarks", "Bookmarks"))
        registerSettings(entry(label = "Bookmarks Bar"))

        // Without this the result is found and then filtered into the wrong chip, which looks like
        // it was never found at all.
        assertTrue(resultsOf<SearchResult.ToolResult>("bookmark").all { it.category == SearchCategory.TOOLS })
        assertTrue(resultsOf<SearchResult.SettingResult>("bookmark").all { it.category == SearchCategory.SETTINGS })
    }

    @Test
    fun `results from the new sources survive the category filter`() {
        registerTools(tool("bookmarks", "Bookmarks"))
        searchFor("bookmark")

        // The category is global state on a singleton, so the restore goes in a finally: an
        // assertion failing here would otherwise leave a filter set for whatever runs next in
        // this JVM, and the failure would be reported against that test instead of this one.
        try {
            GlobalSearchService.setActiveCategory(SearchCategory.TOOLS)
            val filtered = GlobalSearchService.getFilteredResults()

            assertTrue(filtered.isNotEmpty(), "the TOOLS chip must show tools")
            assertTrue(filtered.all { it.category == SearchCategory.TOOLS })
        } finally {
            GlobalSearchService.setActiveCategory(SearchCategory.ALL)
        }
    }

    @Test
    fun `a blank query returns nothing from the new sources either`() {
        registerTools(tool("bookmarks", "Bookmarks"))

        assertTrue(searchFor("   ").isEmpty())
    }

    @Test
    fun `clearing the tools source leaves the settings source alone`() {
        // Clearing one source must not empty another.
        registerTools(tool("bookmarks", "Bookmarks"))
        registerSettings(entry(label = "Bookmarks Bar"))

        SearchSources.unregisterTools(WINDOW)

        assertTrue(resultsOf<SearchResult.ToolResult>("bookmark").isEmpty())
        assertTrue(resultsOf<SearchResult.SettingResult>("bookmark").isNotEmpty(), "settings must survive")
    }
}
