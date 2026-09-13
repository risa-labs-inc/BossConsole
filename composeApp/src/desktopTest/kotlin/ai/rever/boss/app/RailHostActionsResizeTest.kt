package ai.rever.boss.app

import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabRail
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RailHostActionsResizeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `five actions leave a short rail and return when it grows`() {
        var height by mutableStateOf(360.dp)
        var fits by mutableStateOf(true)
        var actionCount by mutableStateOf(5)
        val reports = mutableListOf<Boolean>()
        val actions: List<@Composable () -> Unit> =
            List(5) { index -> { Box(Modifier.size(SIDEBAR_ICON_SIZE).testTag("action-$index")) } }
        rule.setContent {
            Box(Modifier.width(36.dp).height(height).testTag("rail")) {
                BossTabRail(groups = emptyList(), onExpand = {}, onNewTab = {}, belowTabs = {
                    MeasuredRailHostActions(actions.take(actionCount), showActions = fits, onFitsChange = {
                        fits = it
                        reports.add(it)
                    })
                })
            }
        }
        rule.waitForIdle()
        assertFullActions()
        rule.runOnIdle { height = 320.dp }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(fits) }
        rule.onNodeWithTag("action-4").assertDoesNotExist()
        // Loading/unloading the optional Toolbox changes the budget without resizing.
        rule.runOnIdle { actionCount = 4 }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(fits) }
        assertFullActions(4)
        rule.runOnIdle { actionCount = 5 }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(fits) }
        rule.runOnIdle { height = 360.dp }
        rule.waitForIdle()
        assertFullActions()
        rule.runOnIdle { assertEquals(listOf(true, false, true, false, true), reports) }
    }

    private fun assertFullActions(count: Int = 5) {
        val rail = rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot
        repeat(count) { index ->
            val action = rule.onNodeWithTag("action-$index").fetchSemanticsNode().boundsInRoot
            assertEquals(with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }, action.height)
            assertTrue(action.top >= rail.top && action.bottom <= rail.bottom)
        }
    }

    @Test
    fun `fit includes the fixed offset for all five actions`() {
        assertTrue(railFitsActions(212.dp, 4))
        assertFalse(railFitsActions(211.dp, 4))
        assertFalse(railFitsActions(243.dp, 5))
        assertTrue(railFitsActions(244.dp, 5))
    }
}
