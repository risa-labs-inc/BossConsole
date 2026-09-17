package ai.rever.boss.services.bookmarks

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitDirection
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.BookmarkOpenResult
import ai.rever.boss.plugin.bookmark.BookmarkOpeningProvider
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkOpeningServiceTest {
    private val windows = mutableListOf<SplitViewState>()
    private val service = BookmarkOpeningService(Dispatchers.Unconfined) { _, _ -> true }

    @After fun cleanup() {
        windows.forEach { it.dispose() }
    }

    private fun window(vararg types: String = arrayOf("browser", "editor", "terminal", "jupyter")): SplitViewState {
        val registry = TabRegistry()
        types.forEach { type ->
            val info =
                object : TabTypeInfo {
                    override val typeId = requireNotNull(bookmarkTabType(type))
                    override val displayName = type
                    override val icon = Icons.Outlined.Code
                }
            registry.registerTabType(info) { config, context -> TestComponent(context, config, info) }
        }
        return SplitViewState(registry, "test-${windows.size}").also(windows::add)
    }

    private class TestComponent(
        context: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by context {
        @Composable override fun Content() = Unit
    }

    private fun bookmark(config: TabConfig) = Bookmark(id = "saved", tabConfig = config, workspaceName = "Original")

    private fun tabs(
        window: SplitViewState,
        panel: String = window.activePanelId,
    ) = requireNotNull(window.getPanel(panel))
        .tabsComponent.tabsState.value.tabs

    @Test fun `browser reuses exact current URL only in requested pane and explicit new creates another`() =
        runBlocking {
            val state = window()
            val firstPane = state.activePanelId
            val saved = bookmark(TabConfig("browser", "Saved", url = "https://example.test"))
            val first = service.open(state, saved)
            val repeated = service.open(state, saved)
            assertTrue(repeated.reused)
            assertEquals(first.tabId, repeated.tabId)
            val new = service.open(state, saved, forceNewTab = true)
            assertFalse(new.reused)
            assertNotEquals(first.tabId, new.tabId)
            val secondPane =
                state.splitPanel(
                    firstPane,
                    SplitOrientation.VERTICAL,
                    tabToMove = TerminalTabInfo("other", workingDirectory = "/saved"),
                )
            val other = service.open(state, saved, secondPane)
            assertFalse(other.reused)
            assertEquals(2, tabs(state, firstPane).size)
            assertEquals(2, tabs(state, secondPane).size)
        }

    @Test fun `terminal opens fresh sessions retaining startup fields without replacing busy terminal`() =
        runBlocking {
            val state = window()
            val config =
                TabConfig(
                    "terminal",
                    "Build",
                    initialCommand = "printf preview",
                    workingDirectory = "/saved folder",
                )
            val saved = bookmark(config)
            val first = service.open(state, saved)
            val second = service.open(state, saved)
            assertNotEquals(first.tabId, second.tabId)
            assertEquals(saved.id, TerminalBookmarkLinks.find(requireNotNull(first.tabId)))
            assertEquals(saved.id, TerminalBookmarkLinks.find(requireNotNull(second.tabId)))
            val sessions = tabs(state).filterIsInstance<TerminalTabInfo>()
            assertEquals(2, sessions.size)
            assertTrue(sessions.all { it.initialCommand == "printf preview" && it.workingDirectory == "/saved folder" })
        }

    @Test fun `same directory terminal remains unrelated to explicit bookmarked session`() {
        val linked = TerminalTabInfo("linked-regression", workingDirectory = "/saved")
        val unrelated = TerminalTabInfo("unrelated-regression", workingDirectory = "/saved")
        try {
            TerminalBookmarkLinks.bind(linked.id, "bookmark-one")
            assertEquals("bookmark-one", TerminalBookmarkLinks.find(linked.id))
            assertNull(TerminalBookmarkLinks.find(unrelated.id))
            TerminalBookmarkLinks.bind(linked.id, "bookmark-two")
            assertEquals("bookmark-two", TerminalBookmarkLinks.find(linked.id))
            TerminalBookmarkLinks.unbind(linked.id)
            assertNull(TerminalBookmarkLinks.find(linked.id))
        } finally {
            TerminalBookmarkLinks.unbind(linked.id)
        }
    }

    @Test fun `default terminal bookmark resolves destination context on every opening`() =
        runBlocking {
            val state = window()
            var destination = "/project/B"
            val opener =
                BookmarkOpeningService(
                    Dispatchers.Unconfined,
                    resolveDefaultDirectory = { destination },
                    checkPath = { _, _ -> true },
                )
            val saved = bookmark(TabConfig("terminal", "Default", initialCommand = "pwd"))
            opener.open(state, saved)
            assertEquals("/project/B", (tabs(state).last() as TerminalTabInfo).workingDirectory)
            destination = "/project/C"
            opener.open(state, saved)
            assertEquals("/project/C", (tabs(state).last() as TerminalTabInfo).workingDirectory)
            opener.open(state, bookmark(saved.tabConfig.copy(workingDirectory = "/explicit")))
            assertEquals("/explicit", (tabs(state).last() as TerminalTabInfo).workingDirectory)
            assertTrue(tabs(state).filterIsInstance<TerminalTabInfo>().all { it.initialCommand == "pwd" })
        }

    @Test fun `file and notebook use correct registered provider and exact path reuse`() =
        runBlocking {
            val state = window()
            for (type in listOf("editor", "jupyter")) {
                val path = if (type == "editor") "/saved/a.txt" else "/saved/a.ipynb"
                val saved = bookmark(TabConfig(type, "Saved $type", filePath = path))
                val first = service.open(state, saved)
                assertTrue(first.success)
                assertTrue(service.open(state, saved).reused)
                assertFalse(service.open(state, saved, forceNewTab = true).reused)
            }
            assertEquals(2, tabs(state).filterIsInstance<EditorTabInfo>().size)
            assertEquals(2, tabs(state).filterIsInstance<JupyterTabInfo>().size)
        }

    @Test fun `missing notebook does not silently fall back to editor and missing targets leave work intact`() =
        runBlocking {
            val state = window("editor", "terminal")
            state.getPanel(state.activePanelId)!!.tabsComponent.addTab(TerminalTabInfo("busy"))
            val missing = service.open(state, bookmark(TabConfig("jupyter", "Notebook", filePath = "/saved/a.ipynb")))
            assertFalse(missing.success)
            assertTrue(missing.message!!.contains("Jupyter"))
            val bad = service.open(state, bookmark(TabConfig("editor", "Unsaved")))
            assertFalse(bad.success)
            assertEquals(listOf("busy"), tabs(state).map { it.id })
            assertFalse(service.open(state, bookmark(TabConfig("unknown", "Legacy"))).success)
        }

    @Test fun `path failure has visible reason and never opens or replaces tab`() =
        runBlocking {
            val state = window()
            val opener = BookmarkOpeningService(Dispatchers.Unconfined) { _, _ -> false }
            val result = opener.open(state, bookmark(TabConfig("editor", "Gone", filePath = "/gone.txt")))
            assertFalse(result.success)
            assertTrue(result.message!!.contains("missing or inaccessible"))
            assertTrue(tabs(state).isEmpty())
        }

    @Test fun `opening does not consume pending new tab split`() =
        runBlocking {
            val state = window()
            val target = state.activePanelId
            state.requestSplitWithNewTab(target, SplitDirection.RIGHT)
            val pending = state.pendingSplit
            val saved = bookmark(TabConfig("browser", "Saved", url = "https://example.test"))
            assertTrue(service.open(state, saved).success)
            assertEquals(pending, state.pendingSplit)
            assertEquals(1, state.getAllPanels().size)
        }

    @Test fun `async path checks keep original pane and coalesce only in flight terminal requests`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val opener =
                BookmarkOpeningService(Dispatchers.Unconfined) { _, _ ->
                    started.complete(Unit)
                    gate.await()
                    true
                }
            val state = window()
            val pane = state.activePanelId
            val saved = bookmark(TabConfig("terminal", "Saved", workingDirectory = "/saved"))
            val first = async { opener.open(state, saved) }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { opener.open(state, saved, pane) }
            val other = state.splitPanel(pane, SplitOrientation.VERTICAL, tabToMove = TerminalTabInfo("other"))
            val originalOtherTabs = tabs(state, other).map { it.id }
            state.setActivePanel(other)
            gate.complete(Unit)
            assertEquals(first.await().tabId, second.await().tabId)
            assertEquals(1, tabs(state, pane).size)
            assertEquals(originalOtherTabs, tabs(state, other).map { it.id })
            assertFalse(opener.open(state, saved, pane).reused)
            assertEquals(2, tabs(state, pane).size)
        }

    @Test fun `window removed during validation and unknown window fail without redirecting`() =
        runBlocking {
            val state = window()
            var current: SplitViewState? = state
            val opener =
                BookmarkOpeningService(Dispatchers.Unconfined) { _, _ ->
                    current = null
                    true
                }
            val provider = HostBookmarkOpeningProvider(opener, { current }, Dispatchers.Unconfined)
            val saved = bookmark(TabConfig("editor", "Saved", filePath = "/saved/a.txt"))
            assertFalse(provider.openBookmark(saved, "window", null, false).success)
            assertTrue(tabs(state).isEmpty())
            assertFalse(provider.openBookmark(saved, "unknown", null, false).success)
        }

    @Test fun `search routes terminal and notebook through shared provider with captured target`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val provider =
                object : BookmarkOpeningProvider {
                    override suspend fun openBookmark(
                        bookmark: Bookmark,
                        windowId: String,
                        panelId: String?,
                        forceNewTab: Boolean,
                    ): BookmarkOpenResult {
                        calls.add("${bookmark.tabConfig.type}:$windowId:$panelId:$forceNewTab")
                        return BookmarkOpenResult(true, tabId = bookmark.id)
                    }
                }
            for (type in listOf("terminal", "jupyter")) {
                val saved = bookmark(TabConfig(type, "Saved", filePath = "/saved/a.ipynb", workingDirectory = "/saved"))
                val collection = BookmarkCollection("collection", "All", listOf(saved))
                assertTrue(
                    openBookmarkFromSearch(
                        BookmarkSearchRequest(saved.id, collection.id, "window", "pane"),
                        listOf(collection),
                        provider,
                    ).success,
                )
            }
            assertEquals(listOf("terminal:window:pane:false", "jupyter:window:pane:false"), calls)
            val missing = BookmarkSearchRequest("missing", "collection", "window", "pane")
            assertFalse(openBookmarkFromSearch(missing, emptyList(), provider).success)
            assertEquals(2, calls.size)
        }

    @Test fun `eligibility rejects unsaved paths and terminal identity is never guessed`() {
        assertNotNull(bookmarkTargetProblem(TabConfig("editor", "Unsaved")))
        assertNotNull(bookmarkTargetProblem(TabConfig("terminal", "Relative", workingDirectory = "relative")))
        assertNull(bookmarkTargetProblem(TabConfig("terminal", "Default")))
        assertFalse(
            matchesResource(
                TabConfig("terminal", "Same", workingDirectory = "/saved"),
                TerminalTabInfo("live", workingDirectory = "/saved"),
            ),
        )
        assertFalse(
            matchesResource(
                TabConfig("browser", "Home", url = "https://saved.test"),
                FluckTabInfo(
                    "web",
                    typeId = requireNotNull(bookmarkTabType("browser")),
                    _title = "Home",
                    url = "https://old.test",
                    _currentUrl = "https://different.test",
                ),
            ),
        )
    }

    @Test fun `working tree diff preserves project and deleted path but never reuses staged scope`() =
        runBlocking {
            val state = window("diff")
            val opener =
                BookmarkOpeningService(
                    Dispatchers.Unconfined,
                    resolveProjectDirectory = { "/project" },
                    checkPath = { _, _ -> false },
                )
            val config = TabConfig("diff", "Deleted file", filePath = "deleted.txt", workingDirectory = "/project")
            val saved = bookmark(config)
            state.getPanel(state.activePanelId)!!.tabsComponent.addTab(DiffTabInfo.create("deleted.txt", staged = true))
            val first = opener.open(state, saved)
            assertTrue(first.success)
            assertFalse(first.reused)
            assertEquals("deleted.txt", (tabs(state).last() as DiffTabInfo).filePath)
            assertTrue(opener.open(state, saved).reused)
            val wrongProject = BookmarkOpeningService(Dispatchers.Unconfined, resolveProjectDirectory = { "/other" })
            assertFalse(wrongProject.open(state, saved).success)
            assertFalse(opener.open(state, bookmark(config.copy(workingDirectory = null))).success)
            assertFalse(opener.open(state, bookmark(config.copy(filePath = "../outside"))).success)
            assertFalse(opener.open(window(), saved).success)
        }

    @Test fun `composer restores exact opaque session and checks tool not filesystem`() =
        runBlocking {
            val state = window("composer")
            val opener =
                BookmarkOpeningService(
                    Dispatchers.Unconfined,
                    checkPath = { _, _ -> error("Composer is not a file") },
                )
            val saved = bookmark(TabConfig("composer", "Plan", filePath = "session:opaque"))
            val first = opener.open(state, saved)
            assertTrue(first.success)
            assertEquals("session:opaque", (tabs(state).single() as ComposerTabInfo).sessionId)
            assertEquals("session:opaque", first.tabId)
            assertTrue(opener.open(state, saved).reused)
            assertFalse(opener.open(state, saved, forceNewTab = true).success)
            assertFalse(opener.open(window(), saved).success)
            assertFalse(opener.open(state, saved.copy(tabConfig = saved.tabConfig.copy(filePath = " "))).success)
        }
}
