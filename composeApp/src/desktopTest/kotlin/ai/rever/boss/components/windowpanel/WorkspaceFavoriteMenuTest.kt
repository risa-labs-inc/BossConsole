package ai.rever.boss.components.windowpanel

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.window_panel.components.main_window_panels.favoriteWorkspaceItem
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceFavoriteMenuTest {
    @get:Rule val compose = createComposeRule()
    private val favorites = MutableStateFlow<List<FavoriteWorkspace>>(emptyList())
    private val provider =
        Proxy.newProxyInstance(
            BookmarkDataProvider::class.java.classLoader,
            arrayOf(BookmarkDataProvider::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getFavoriteWorkspaces" -> {
                    favorites
                }

                "addFavoriteWorkspace" -> {
                    favorites.value += FavoriteWorkspace.create(args!![0] as String, args[1] as String)
                    null
                }

                "removeFavoriteWorkspace" -> {
                    favorites.value = favorites.value.filterNot { it.workspaceId == args!![0] }
                    null
                }

                else -> {
                    error("Unexpected call: ${method.name}")
                }
            }
        } as BookmarkDataProvider

    private fun space(id: String) =
        LayoutWorkspace(
            id = id,
            name = id,
            description = "",
            layout = SplitConfig.SinglePanel(PanelConfig("main", emptyList())),
        )

    @Test fun `favorite action updates immediately and only changes the selected Space`() {
        var current by mutableStateOf(space("first"))
        var item: ContextMenuItem? = null
        compose.setContent { item = favoriteWorkspaceItem(current, provider) }
        compose.runOnIdle { item!!.onClick() }
        compose.runOnIdle { assertEquals("Unfavorite Space", item!!.text) }
        compose.runOnIdle { current = space("second") }
        compose.runOnIdle { assertEquals("Favorite Space", item!!.text) }
        compose.runOnIdle { item!!.onClick() }
        compose.runOnIdle { item!!.onClick() }
        compose.runOnIdle {
            assertEquals(listOf("first"), favorites.value.map { it.workspaceId })
            assertEquals("Favorite Space", item!!.text)
        }
    }

    @Test fun `unavailable plugin and no current Space offer no mutation`() {
        var available by mutableStateOf<BookmarkDataProvider?>(null)
        var current by mutableStateOf<LayoutWorkspace?>(space("first"))
        var item: ContextMenuItem? = null
        compose.setContent { item = favoriteWorkspaceItem(current, available) }
        compose.runOnIdle {
            assertNull(item)
            available = provider
        }
        compose.runOnIdle {
            assertEquals("Favorite Space", item!!.text)
            current = null
        }
        compose.runOnIdle { assertNull(item) }
    }
}
