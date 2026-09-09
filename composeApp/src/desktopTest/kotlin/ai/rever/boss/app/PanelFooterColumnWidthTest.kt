package ai.rever.boss.app

import ai.rever.boss.components.window_panel.PanelColumn
import ai.rever.boss.plugin.api.Panel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class PanelFooterColumnWidthTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `end aligned content follows the column width with and without its footer`() {
        var width by mutableStateOf(160.dp)
        var footer by mutableStateOf(false)
        rule.setContent {
            Box(Modifier.size(width, 240.dp).testTag("column")) {
                PanelColumn(
                    column = Panel.right,
                    footerEdge = if (footer) Panel.right else null,
                    footer = { Spacer(Modifier.height(40.dp)) },
                ) {
                    Box(Modifier.align(Alignment.CenterEnd).size(32.dp).testTag("control"))
                }
            }
        }
        assertAtRightEdge()
        rule.runOnIdle { width = 320.dp }
        assertAtRightEdge()
        // Guard the sibling-footer structure as well as the no-footer alignment contract.
        rule.runOnIdle { footer = true }
        assertAtRightEdge()
        rule.runOnIdle { width = 96.dp }
        assertAtRightEdge()
    }

    private fun assertAtRightEdge() {
        rule.waitForIdle()
        val column = rule.onNodeWithTag("column").fetchSemanticsNode().boundsInRoot
        val control = rule.onNodeWithTag("control").fetchSemanticsNode().boundsInRoot
        assertEquals(column.right, control.right, "end alignment must use the whole panel column")
        assertEquals(
            with(rule.density) { 32.dp.roundToPx().toFloat() },
            control.width,
            "the control must keep its full width",
        )
    }
}
