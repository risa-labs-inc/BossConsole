package ai.rever.boss.components.model

import ai.rever.boss.components.buttons.BossTabButton
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
    fun `changing chip index during mouse drag preserves the gesture until release`() {
        interruptedDrag(remove = false)
    }

    @Test
    fun `removing button during mouse drag clears ghost and allows another drag`() {
        interruptedDrag(remove = true, button = true)
    }

    @Test
    fun `replacing button drag component cleans old owner and uses new component`() {
        interruptedDrag(remove = false, button = true, replaceComponent = true)
    }

    private fun interruptedDrag(
        remove: Boolean,
        button: Boolean = false,
        replaceComponent: Boolean = false,
    ) {
        val visible = mutableStateOf(true)
        val index = mutableStateOf(0)
        val component = TabDraggableComponent()
        val currentComponent = mutableStateOf(component)
        rule.setContent {
            Box(Modifier.size(100.dp).testTag("source")) {
                if (visible.value) {
                    if (button) {
                        BossTabButton(
                            fileName = "Test tab",
                            onClick = {},
                            tabInfo = tab,
                            panelId = "source",
                            tabIndex = index.value,
                            tabDragComponent = currentComponent.value,
                        )
                    } else {
                        TabFaviconChip(
                            tab = tab,
                            isActive = true,
                            onClick = {},
                            size = 80.dp,
                            tabDragComponent = currentComponent.value,
                            panelId = "source",
                            tabIndex = index.value,
                        )
                    }
                }
            }
        }
        startMouseDrag()
        rule.runOnIdle {
            assertTrue(component.isDragging, "the actual chip must have started the drag")
            if (remove) {
                visible.value = false
            } else if (replaceComponent) {
                currentComponent.value = TabDraggableComponent()
            } else {
                index.value = 1
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            if (remove || replaceComponent) {
                assertFalse(component.isDragging, "source interruption must dispose the ghost")
            } else {
                assertTrue(component.isDragging, "reindexing must preserve the user's drag")
            }
        }
        rule.onNodeWithTag("source").performMouseInput { release() }
        rule.runOnIdle { assertFalse(component.isDragging, "release must clear the ghost") }
        rule.runOnIdle { visible.value = true }
        rule.waitForIdle()
        startMouseDrag()
        rule.runOnIdle { assertTrue(currentComponent.value.isDragging, "a later gesture must start successfully") }
        rule.onNodeWithTag("source").performMouseInput { release() }
        rule.runOnIdle { assertFalse(currentComponent.value.isDragging) }
    }

    private fun startMouseDrag() {
        rule.onNodeWithTag("source").performMouseInput {
            moveTo(Offset(10f, 10f))
            press()
            moveTo(Offset(65f, 10f))
        }
    }
}
