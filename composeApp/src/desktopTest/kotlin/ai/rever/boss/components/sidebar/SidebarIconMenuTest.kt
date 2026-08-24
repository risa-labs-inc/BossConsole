package ai.rever.boss.components.sidebar

import ai.rever.boss.components.buttons.DraggableActionButton
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.NativeContextMenuTestOverride
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.window.LocalWindowId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * What right-clicking a plugin's icon in the sidebar rail offers.
 *
 * The rail icon is the only handle a plugin has while its panel is closed, and it used to offer a
 * one-item menu ("Sidebar settings") - reloading, updating or opening a plugin meant opening its
 * panel first just to reach the header's "…" kebab. These pin that the icon now opens the panel's
 * own menu, and that the sidebar's row is still there behind it.
 */
class SidebarIconMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val LABEL = "Probe"
        const val WINDOW = "window-1"
    }

    /**
     * The rows are found as Compose nodes, so this must run against the drawn menu. A native menu is
     * an OS window with no Compose tree, which would make these fail on macOS and pass on CI for a
     * reason unrelated to what they assert.
     */
    @Before
    fun useDrawnMenu() {
        NativeContextMenuTestOverride.enabled = false
    }

    @After
    fun clearOverride() {
        NativeContextMenuTestOverride.enabled = null
    }

    private fun showIcon(windowId: String? = WINDOW) {
        val model = BossDraggableComponent(PanelRegistry())
        val item = SidebarItem(PanelId("probe", 1), Icons.Default.Extension, LABEL)
        compose.setContent {
            CompositionLocalProvider(LocalWindowId provides windowId) {
                with(model) {
                    DraggableActionButton(item = item, slot = left.top.top)
                }
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(LABEL).performMouseInput { rightClick() }
    }

    @Test
    fun `the icon's menu offers the plugin's own actions, not only the sidebar's`() {
        showIcon()
        openMenu()

        compose.onNodeWithText("Reload Panel").assertExists()
        compose.onNodeWithText("Check for Updates").assertExists()
        compose.onNodeWithText("Open as Tab").assertExists()
    }

    @Test
    fun `the sidebar's own row survives, after the plugin's`() {
        showIcon()
        openMenu()

        compose.onNodeWithText("Sidebar settings").assertExists()
    }

    @Test
    fun `no Minimize row, since the menu opens just as often with the panel closed`() {
        // The icon's own click already toggles the panel. A Minimize row would be the only item in
        // this menu that silently does nothing for the state the rail is most often in.
        showIcon()
        openMenu()

        compose.onNodeWithText("Minimize").assertDoesNotExist()
    }

    @Test
    fun `outside a tracked window the panel rows are left out rather than shown inert`() {
        // LocalWindowId defaults to null wherever the rail is not hosted in a tracked window, and
        // every one of these actions routes through a window. A row named as an imperative that
        // does nothing is worse than no row.
        showIcon(windowId = null)
        openMenu()

        compose.onNodeWithText("Reload Panel").assertDoesNotExist()
        compose.onNodeWithText("Sidebar settings").assertExists()
    }
}
