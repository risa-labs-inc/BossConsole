package ai.rever.boss.components.tabs

import ai.rever.boss.components.window_panel.components.main_window_panels.PaneGlyph
import ai.rever.boss.components.window_panel.components.main_window_panels.SplitMap
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarGroup
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarState
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

/**
 * The navigation map says a pane can go full screen WITHOUT being hovered, and the mark does it.
 *
 * Double-click was the only way in and nothing on screen hinted at it, so these pin that every
 * pane big enough carries a visible full-screen mark at rest, that clicking the mark is full screen
 * rather than "go to this pane", and that it goes away where it would mean nothing.
 */
@OptIn(ExperimentalTestApi::class)
class SplitMapFullScreenMarkTest {
    private val events = mutableListOf<String>()

    private fun state() =
        TabBarState(
            items = {},
            barContextMenuItems = emptyList(),
            openNewTab = {},
            openPinnedTab = {},
            tabs = emptyList(),
            activeIndex = 0,
            pinnedCount = 0,
            tabMenuItems = { _, _ -> emptyList() },
            activateTab = {},
            favorites = emptyList(),
            removeFavorite = {},
            openFavorite = {},
            bookmarksInstalled = null,
            bookmarksApiReachable = false,
            shrinkTabsToFit = false,
            dialogs = {},
            leadingListItems = 0,
            renderedTabCount = 0,
            collapsedToActiveTab = false,
            listState = LazyListState(),
        )

    private fun pane(
        id: String,
        glyph: PaneGlyph,
        active: Boolean = false,
    ) = TabBarGroup(
        panelId = id,
        state = state(),
        isActive = active,
        glyph = glyph,
        label = id,
        activate = { events += "activate:$id" },
        zoom = { events += "zoom:$id" },
    )

    private val leftRight =
        listOf(
            pane("Left", PaneGlyph(0f, 0f, 0.5f, 1f), active = true),
            pane("Right", PaneGlyph(0.5f, 0f, 1f, 1f)),
        )

    @Test
    fun `every pane shows the full-screen mark with nothing hovered`() =
        runComposeUiTest {
            // On the tab bar's own panel colour, which is what sits behind the map in the app.
            setContent {
                Box(Modifier.width(MAP_WIDTH).background(BossTheme.colors.panel)) { SplitMap(leftRight) }
            }

            onAllNodesWithContentDescription(MARK).assertCountEquals(2)

            // Saved so the mark can be LOOKED at, which is what a layout claim needs.
            System.getenv("SPLIT_MAP_SHOT")?.let { path ->
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(path))
            }
        }

    @Test
    fun `clicking a mark is full screen for that pane, not only going to it`() =
        runComposeUiTest {
            setContent { Box(Modifier.width(MAP_WIDTH)) { SplitMap(leftRight) } }

            onAllNodesWithContentDescription(MARK)[1].performClick()

            assertEquals(listOf("zoom:Right"), events)
        }

    @Test
    fun `a zoomed map, whose one job is the way back, shows no mark`() =
        runComposeUiTest {
            setContent { Box(Modifier.width(MAP_WIDTH)) { SplitMap(leftRight, zoomed = true) } }

            onAllNodesWithContentDescription(MARK).assertCountEquals(0)
        }

    @Test
    fun `a pane too small to hold the mark beside its label goes without`() =
        runComposeUiTest {
            val sliver =
                listOf(
                    pane("Wide", PaneGlyph(0f, 0f, 0.9f, 1f)),
                    pane("Sliver", PaneGlyph(0.9f, 0f, 1f, 1f)),
                )
            setContent { Box(Modifier.width(MAP_WIDTH)) { SplitMap(sliver) } }

            onAllNodesWithContentDescription(MARK).assertCountEquals(1)
        }

    private companion object {
        const val MARK = "Full screen"

        /** The vertical tab bar's width, so the panes are the size a user sees. */
        val MAP_WIDTH = 200.dp
    }
}
