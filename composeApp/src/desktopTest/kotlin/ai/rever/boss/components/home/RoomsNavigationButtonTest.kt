package ai.rever.boss.components.home

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class RoomsNavigationButtonTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `entry follows plugin registration and invokes its tab action`() {
        val registry = PanelRegistry()
        var clicks = 0
        val panel =
            object : PanelInfo {
                override val id = PanelId("rooms", 65)
                override val displayName = "Rooms"
                override val icon = Icons.Outlined.Forum
                override val defaultSlotPosition = Panel.bottom
                override val sidebarItem get() = SidebarItem(id, icon, displayName) { clicks++ }
            }
        rule.setContent { CompositionLocalProvider(LocalPanelRegistry provides registry) { RoomsNavigationButton() } }
        rule.onNodeWithContentDescription("Rooms").assertDoesNotExist()
        rule.runOnIdle { registry.registerPanel(panel) { _, _ -> error("Opening uses the sidebar action") } }
        rule.onNodeWithContentDescription("Rooms").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        rule.runOnIdle { registry.unregisterPanel(panel.id) }
        rule.onNodeWithContentDescription("Rooms").assertDoesNotExist()
    }

    @Test
    fun `compact destination is accessible and selected`() {
        var clicks = 0
        rule.setContent { RoomsNavigationTile(selected = true, compact = true) { clicks++ } }
        rule.onNodeWithContentDescription("Rooms").assertIsSelected().performClick()
        assertEquals(1, clicks)
    }
}
