package ai.rever.boss.app

import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabRail
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class RailFavoritesActionsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `measuring favorites does not expose an invisible click target`() {
        var opened = 0
        rule.setContent {
            Box(Modifier.width(36.dp).height(400.dp).testTag("rail")) {
                BossTabRail(
                    groups = emptyList(),
                    onExpand = {},
                    onNewTab = {},
                    favoritesSpacer = {
                        Box(Modifier.size(100.dp).clickable { opened++ })
                    },
                )
            }
        }
        // Inside the shelf reserve, below the visible expand button and above New Tab.
        rule.onNodeWithTag("rail").performTouchInput {
            click(Offset(18.dp.toPx(), 70.dp.toPx()))
        }
        rule.runOnIdle { assertEquals(0, opened, "a hidden bookmark must not open") }
    }
}
