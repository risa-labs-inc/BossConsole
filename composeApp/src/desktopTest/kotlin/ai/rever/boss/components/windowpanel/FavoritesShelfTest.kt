package ai.rever.boss.components.windowpanel

import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarFavorites
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FavoritesShelfTest {
    @get:Rule val rule = createComposeRule()

    private fun bookmark(
        id: Int,
        title: String = "Terminal $id",
    ) = Bookmark(
        id = "saved-$id",
        tabConfig = TabConfig("terminal", title, workingDirectory = "/work/project-$id"),
        workspaceName = "Work",
    )

    @Test fun `unstar is separate from opening or deleting the bookmark`() {
        val saved = bookmark(1)
        var opened = 0
        var unstarred = 0
        var deleted = 0
        rule.setContent {
            Box(Modifier.width(220.dp)) {
                TabBarFavorites(
                    listOf(saved),
                    true,
                    true,
                    onOpen = { opened++ },
                    onRemove = { unstarred++ },
                    onInstallPlugin = {},
                    onDelete = { deleted++ },
                )
            }
        }
        rule.onNodeWithContentDescription("Remove Terminal 1 from Favorites").performClick()
        rule.waitForIdle()
        assertEquals(1, unstarred)
        assertEquals(0, opened)
        assertEquals(0, deleted)
        rule.onNodeWithText("Terminal 1").performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun `bounded shelf scrolls to later favorites while keeping header access`() {
        val bookmarks = (1..20).map { bookmark(it) }
        var opened = ""
        var library = 0
        rule.setContent {
            Box(Modifier.width(200.dp).height(280.dp).testTag("shelf")) {
                TabBarFavorites(
                    bookmarks,
                    true,
                    true,
                    onOpen = { opened = it.id },
                    onRemove = {},
                    onInstallPlugin = {},
                    onOpenAll = { library++ },
                    trailing = { Text("Collapse") },
                )
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Terminal 20"))
        rule.onNodeWithText("Terminal 20").assertIsDisplayed().performClick()
        rule.onNodeWithText("FAVORITES").assertIsDisplayed()
        rule.onNodeWithText("Collapse").assertIsDisplayed()
        rule.onNodeWithContentDescription("All Bookmarks").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals("saved-20", opened)
        assertEquals(1, library)
        val list = rule.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        val shelf = rule.onNodeWithTag("shelf").fetchSemanticsNode().boundsInRoot
        assertTrue(list.height <= with(rule.density) { 180.dp.toPx() })
        assertTrue(list.bottom <= shelf.bottom)
    }

    @Test fun `long title remains readable and unstar stays inside narrow shelf`() {
        val title = "A very long saved terminal title repeated to exceed the sidebar width"
        rule.setContent {
            Box(Modifier.width(170.dp).height(170.dp).testTag("shelf")) {
                TabBarFavorites(listOf(bookmark(1, title)), true, true, {}, {}, {})
            }
        }
        rule.onNodeWithText(title).assertIsDisplayed()
        val action = rule.onNodeWithContentDescription("Remove $title from Favorites").fetchSemanticsNode().boundsInRoot
        val shelf = rule.onNodeWithTag("shelf").fetchSemanticsNode().boundsInRoot
        assertTrue(action.right <= shelf.right && action.bottom <= shelf.bottom)
    }

    @Test fun `missing plugin retains install recovery`() {
        var installs = 0
        rule.setContent {
            Box(Modifier.width(200.dp)) {
                TabBarFavorites(emptyList(), false, false, {}, {}, { installs++ })
            }
        }
        rule.onNodeWithText("Install Bookmarks").performClick()
        rule.waitForIdle()
        assertEquals(1, installs)
    }
}
