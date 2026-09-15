package ai.rever.boss.components.model

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class TabDragSessionTest {
    private data class Tab(
        override val id: String = "tab",
    ) : TabInfo {
        override val typeId = TabTypeId("test", "test.plugin")
        override val title = "Tab"
        override val icon get() = Icons.Outlined.Language
    }

    @Test
    fun `cancelling the pointer coroutine clears ghost and targeting state`() =
        runBlocking {
            val component = TabDraggableComponent()
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    component.withDragSession { session ->
                        session.start(Tab(), "source", 0, Offset.Zero)
                        session.update(Offset(40f, 20f))
                        awaitCancellation()
                    }
                }
            job.cancelAndJoin()
            assertFalse(component.isDragging)
            assertNull(component.dragStartPosition)
            assertEquals(Offset.Zero, component.dragDelta)
            assertNull(component.dropTarget)
        }

    @Test
    fun `exception in gesture callback clears ghost`() =
        runBlocking {
            val component = TabDraggableComponent()
            assertFailsWith<IllegalStateException> {
                component.withDragSession { session ->
                    session.start(Tab(), "source", 0, Offset.Zero)
                    error("interrupted callback")
                }
            }
            assertFalse(component.isDragging)
        }

    @Test
    fun `old coroutine cleanup leaves replacement drag intact even for same tab`() =
        runBlocking {
            val component = TabDraggableComponent()
            component.withDragSession { session ->
                session.start(Tab(), "source", 0, Offset.Zero)
                component.cancelDrag()
                component.startDragging(Tab(), "source", 0, Offset.Zero)
                val replacement = component.draggingTab
                session.update(Offset(99f, 99f))
                assertNull(session.end())
                assertSame(replacement, component.draggingTab)
            }
            assertEquals("tab", component.draggingTab?.tabInfo?.id)
            assertEquals(Offset.Zero, component.dragDelta)
        }

    @Test
    fun `handler that never started a drag cannot cancel another handler`() =
        runBlocking {
            val component = TabDraggableComponent()
            component.startDragging(Tab(), "source", 0, Offset.Zero)
            val original = component.draggingTab
            component.withDragSession { session ->
                session.start(Tab("other"), "other", 0, Offset.Zero)
                session.cancel()
            }
            assertSame(original, component.draggingTab)
        }

    @Test
    fun `ordinary cross pane drop returns result and clears ghost`() =
        runBlocking {
            val component = TabDraggableComponent()
            component.registerTabBarBounds("target", Rect(0f, 0f, 100f, 100f), vertical = true)
            component.withDragSession { session ->
                session.start(Tab(), "source", 0, Offset(20f, 20f))
                val result = session.end() as TabDropResult.MoveToPanel
                assertEquals("source", result.sourcePanelId)
                assertEquals("target", result.targetPanelId)
                assertFalse(component.isDragging)
            }
            assertFalse(component.isDragging)
        }
}
