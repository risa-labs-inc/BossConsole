package ai.rever.boss.components.dialogs

import ai.rever.boss.search.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the two-family split `SearchResultItem` branches on.
 *
 * `simpleRow` returns a row for the four sources that share one renderer and null for the five that
 * draw themselves, and the caller picks the branch from that. Getting it wrong is not a cosmetic
 * failure: a type on the wrong side of the split either renders as the wrong shape or reaches the
 * `else` in `DetailedResultItem`, and both happen inside a composable, which in this app means the
 * render-recovery path rather than a log line.
 *
 * It is worth a test and not just the compiler because the `when` being exhaustive only forces a
 * new type to be *named* - it cannot say which side it was meant to land on.
 */
class SearchResultRowFamilyTest {
    @Test
    fun `the four shared-renderer types get a row`() {
        val simple =
            listOf(
                SearchResult.ToolResult(panelId = "bookmarks", label = "Bookmarks", score = 1),
                SearchResult.SettingResult(
                    section = "APPEARANCE",
                    pluginPageId = null,
                    panelId = null,
                    group = null,
                    label = "Show Title Bar",
                    breadcrumb = "Appearance",
                    highlightable = true,
                    score = 1,
                ),
                SearchResult.McpToolResult(
                    name = "git_status",
                    providerId = "git",
                    description = "Working tree status",
                    enabled = true,
                    score = 1,
                ),
                SearchResult.PageResult(url = "https://example.com", title = "Example", score = 1),
            )

        simple.forEach { assertNotNull(it.simpleRow(), "${it::class.simpleName} must have a shared row") }
    }

    @Test
    fun `a disabled MCP tool says so on the row itself`() {
        // The row has no activation, so its state has to be legible where it is drawn - a disabled
        // tool is precisely the one someone searched for.
        val off =
            SearchResult
                .McpToolResult(name = "git_status", providerId = "git", description = "", enabled = false, score = 1)
                .simpleRow()

        assertEquals("off - git", off?.trailing)
        assertEquals("mcp__boss__git_status", off?.title, "the name clients actually type")
    }

    @Test
    fun `the five types that draw themselves get no shared row`() {
        // Each of these has a shape of its own - a file shows matched ranges, a tab shows which
        // window it is in - so a row here would be the wrong one, and before the split existed a
        // non-nullable return sent every one of them into an error() instead.
        val detailed =
            listOf(
                SearchResult.FileResult(
                    name = "b.kt",
                    path = "/a/b.kt",
                    relativePath = "a/b.kt",
                    score = 1,
                    matchRanges = emptyList(),
                ),
                SearchResult.TabResult(
                    title = "Tab",
                    tabId = "t1",
                    workspaceName = "w",
                    windowId = "win",
                    panelId = "browser",
                    tabType = "browser",
                    score = 1,
                    matchRanges = emptyList(),
                ),
                SearchResult.BookmarkResult(
                    title = "Bookmark",
                    bookmarkId = "b1",
                    collectionId = "c1",
                    collectionName = "Collection",
                    tabType = "browser",
                    score = 1,
                    matchRanges = emptyList(),
                ),
                SearchResult.RunConfigResult(
                    name = "Run",
                    configId = "c1",
                    language = "kotlin",
                    filePath = "/a/b.kt",
                    configType = "run",
                    score = 1,
                    matchRanges = emptyList(),
                ),
                SearchResult.CommandResult(actionId = "a1", description = "Command", shortcut = null, score = 1),
            )

        detailed.forEach { assertNull(it.simpleRow(), "${it::class.simpleName} draws its own row") }
    }
}
