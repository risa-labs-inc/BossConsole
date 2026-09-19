package ai.rever.boss.components.model

import ai.rever.boss.components.window_panel.components.main_window_panels.TabFaviconChip
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabChipDragCancellationTest {
    @get:Rule
    val rule = createComposeRule()

    private val tab =
        object : TabInfo {
            override val id = "chip-drag-test"
            override val title = "Test tab"
            override val typeId = TabTypeId("test", "test.plugin")
            override val icon get() = Icons.Outlined.Language
        }

    @Test
    fun `removing chip during mouse drag clears ghost`() {
        interruptedDrag(remove = true)
    }

    @Test
    fun `changing chip index during mouse drag clears ghost`() {
        interruptedDrag(remove = false)
    }

    private fun interruptedDrag(remove: Boolean) {
        val visible = mutableStateOf(true)
        val index = mutableStateOf(0)
        val component = TabDraggableComponent()
        rule.setContent {
            Box(Modifier.size(100.dp).testTag("source")) {
                if (visible.value) {
                    TabFaviconChip(
                        tab = tab,
                        isActive = true,
                        onClick = {},
                        size = 80.dp,
                        tabDragComponent = component,
                        panelId = "source",
                        tabIndex = index.value,
                    )
                }
            }
        }
        rule.onNodeWithTag("source").performMouseInput {
            moveTo(Offset(10f, 10f))
            press()
            moveTo(Offset(65f, 10f))
        }
        rule.runOnIdle {
            assertTrue(component.isDragging, "the actual chip must have started the drag")
            if (remove) visible.value = false else index.value = 1
        }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(component.isDragging, "source interruption must dispose the ghost") }
        rule.onNodeWithTag("source").performMouseInput { release() }
    }
}
