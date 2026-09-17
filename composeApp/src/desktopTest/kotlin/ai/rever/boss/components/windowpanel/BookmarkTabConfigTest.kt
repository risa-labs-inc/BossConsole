package ai.rever.boss.components.windowpanel

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.components.main_window_panels.convertTabInfoToTabConfig
import ai.rever.boss.components.workspaces.extractTabConfig
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.project.DefaultWorkingDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BookmarkTabConfigTest {
    @Test fun `terminal bookmarks retain directory and launch command`() {
        val tab =
            TerminalTabInfo("one", title = "Server", workingDirectory = "/work/one", initialCommand = "npm run dev")
        val saved = convertTabInfoToTabConfig(tab)
        assertEquals("/work/one", saved.workingDirectory)
        assertEquals("npm run dev", saved.initialCommand)
        assertNotEquals(saved, convertTabInfoToTabConfig(tab.copy(id = "two", workingDirectory = "/work/two")))
    }

    @Test fun `bookmark and Space extraction agree on default and explicit terminal directories`() {
        val defaultDirectory = DefaultWorkingDirectory.nominalPath()
        val tab = TerminalTabInfo("default", workingDirectory = defaultDirectory, initialCommand = "pwd")
        val bookmark = convertTabInfoToTabConfig(tab, defaultDirectory)
        assertEquals(extractTabConfig(tab, defaultDirectory), bookmark)
        assertNull(bookmark.workingDirectory)
        assertEquals("pwd", bookmark.initialCommand)
        val explicit = tab.copy(workingDirectory = "/explicit/project")
        val explicitBookmark = convertTabInfoToTabConfig(explicit, defaultDirectory)
        assertEquals(extractTabConfig(explicit, defaultDirectory), explicitBookmark)
        assertEquals("/explicit/project", convertTabInfoToTabConfig(explicit, defaultDirectory).workingDirectory)
    }

    @Test fun `browser bookmark captures the page currently shown`() {
        val tab =
            FluckTabInfo(
                id = "browser",
                typeId = TabTypeId("fluck"),
                _title = "Initial",
                url = "https://initial.example",
            )
        tab.navigateToPage("Current", "https://current.example/path")
        val saved = convertTabInfoToTabConfig(tab)
        assertEquals("https://current.example/path", saved.url)
        assertEquals(tab.title, saved.title)
    }

    @Test fun `only working tree file diffs retain their original project`() {
        val tab =
            ai.rever.boss.plugin.tab.diff.DiffTabInfo
                .create("deleted.txt")
        val saved = convertTabInfoToTabConfig(tab, "/project")
        assertEquals("diff", saved.type)
        assertEquals("/project", saved.workingDirectory)
        listOf(tab.copy(staged = true), tab.copy(fromRef = "HEAD"), tab.copy(fromRef = "a", toRef = "b")).forEach {
            assertEquals("unknown", convertTabInfoToTabConfig(it, "/project").type)
        }
    }
}
