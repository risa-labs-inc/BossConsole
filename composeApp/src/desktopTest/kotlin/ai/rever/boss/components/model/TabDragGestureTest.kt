package ai.rever.boss.components.model

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Test
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.event.MouseEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabDragGestureTest {
    @Test
    fun `release on a native component cancels an orphaned drag`() = verifyNativeRelease(replaceDrag = false)

    @Test
    fun `delayed release cleanup leaves a replacement drag alone`() = verifyNativeRelease(replaceDrag = true)

    private fun verifyNativeRelease(replaceDrag: Boolean) =
        runComposeUiTest {
            val component = TabDraggableComponent()
            component.startDragging(TestTab, "main", 0, Offset.Zero)
            setContent { TabDragReleaseGuard(component) }
            waitForIdle()
            EventQueue.invokeAndWait {
                val source = Canvas()
                source.dispatchEvent(
                    MouseEvent(source, MouseEvent.MOUSE_RELEASED, 0L, 0, 0, 0, 1, false, MouseEvent.BUTTON1),
                )
                if (replaceDrag) {
                    component.endDrag()
                    component.startDragging(TestTab, "main", 0, Offset.Zero)
                }
            }
            EventQueue.invokeAndWait { }
            runOnIdle { assertEquals(replaceDrag, component.isDragging) }
        }

    @Test
    fun `restarting the pointer handler clears an unfinished drag`() = verifyInterruptedDrag(remove = false)

    @Test
    fun `removing the source clears an unfinished drag`() = verifyInterruptedDrag(remove = true)

    private fun verifyInterruptedDrag(remove: Boolean) =
        runComposeUiTest {
            val component = TabDraggableComponent()
            val generation = mutableStateOf(0)
            val visible = mutableStateOf(true)
            var drops = 0
            setContent {
                if (visible.value) {
                    Box(
                        Modifier.size(200.dp).testTag("source").pointerInput(generation.value) {
                            detectTabDragGestures(
                                component,
                                onStart = { component.startDragging(TestTab, "main", 0, it) },
                                onEnd = { drops++ },
                            )
                        },
                    )
                }
            }
            onNodeWithTag("source").performMouseInput {
                moveTo(Offset(30f, 30f))
                press()
                moveTo(Offset(100f, 30f))
            }
            runOnIdle {
                assertTrue(component.isDragging)
                if (remove) visible.value = false else generation.value++
            }
            runOnIdle {
                assertFalse(component.isDragging)
                assertNull(component.getCurrentPosition())
                assertNull(component.dropTarget)
                assertEquals(1, drops)
            }
        }

    @Test
    fun `release delivers one drop and clears the drag`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            component.registerTabBarBounds("destination", Rect(0f, 0f, 500f, 500f), vertical = false)
            val drops = mutableListOf<TabDropResult?>()
            setContent {
                Box(
                    Modifier.size(200.dp).testTag("source").pointerInput(Unit) {
                        detectTabDragGestures(
                            component,
                            onStart = { component.startDragging(TestTab, "main", 0, it) },
                            onEnd = { drops.add(it) },
                        )
                    },
                )
            }
            onNodeWithTag("source").performMouseInput {
                moveTo(Offset(30f, 30f))
                press()
                moveTo(Offset(100f, 30f))
                release()
            }
            runOnIdle {
                assertFalse(component.isDragging)
                assertEquals(1, drops.size)
                assertNotNull(drops.single())
            }
        }

    @Test
    fun `small mouse slips click without starting a tab drop`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var starts = 0
            var drops = 0
            var clicks = 0
            setContent {
                Box(
                    Modifier
                        .size(200.dp)
                        .testTag("source")
                        .pointerInput(Unit) {
                            detectTabDragGestures(
                                component,
                                onStart = {
                                    starts++
                                    component.startDragging(TestTab, "main", 0, it)
                                },
                                onEnd = { drops++ },
                            )
                        }.clickable { clicks++ },
                )
            }
            onNodeWithTag("source").performMouseInput {
                moveTo(Offset(30f, 30f))
                press()
                // Repeated jitter must not accumulate into a drag.
                repeat(10) {
                    moveTo(Offset(33f, 32f))
                    moveTo(Offset(30f, 30f))
                }
                moveTo(Offset(34f, 33f))
                release()
            }
            runOnIdle {
                assertEquals(1, clicks)
                assertEquals(0, starts)
                assertEquals(0, drops)
                assertFalse(component.isDragging)
                assertNull(component.dropTarget)
            }
            onNodeWithTag("source").performMouseInput {
                press()
                moveTo(Offset(110f, 30f))
            }
            runOnIdle {
                assertEquals(1, starts)
                assertEquals(Offset(110f, 30f), component.getCurrentPosition())
            }
            onNodeWithTag("source").performMouseInput { release() }
            runOnIdle {
                assertEquals(1, clicks)
                assertEquals(1, drops)
                assertFalse(component.isDragging)
            }
        }

    private object TestTab : TabInfo {
        override val id = "drag-test"
        override val title = "Drag test"
        override val typeId = TabTypeId("drag-test", "test.plugin")
        override val icon get() = Icons.Outlined.Language
    }
}
