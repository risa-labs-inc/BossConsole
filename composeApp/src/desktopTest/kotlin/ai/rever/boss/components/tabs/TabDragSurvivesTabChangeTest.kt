package ai.rever.boss.components.tabs

import ai.rever.boss.components.buttons.BossTabButton
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.window_panel.components.main_window_panels.TabFaviconChip
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins that a tab drag survives the tab changing underneath it.
 *
 * The stuck-ghost bug: the drag gesture was keyed on the TabInfo, the panel id and the tab's index,
 * so any of the three changing restarted its `pointerInput`. A TabInfo is a data class carrying the
 * title, so a terminal writing a new title or a page finishing its load hands the tab list a fresh
 * instance mid-drag - and the index moves whenever anything else in the panel opens or closes. A
 * restart cancels the gesture's coroutine WITHOUT calling onDragEnd or onDragCancel, so the release
 * that followed went nowhere: `draggingTab` stayed set and the ghost window kept tracking the
 * cursor for the rest of the session, with no gesture left alive to put it down.
 *
 * Composition tests exercise gesture lifetime and callback freshness; the result assertion also
 * pins that a surviving gesture uses the current source index at release.
 */
@OptIn(ExperimentalTestApi::class)
class TabDragSurvivesTabChangeTest {
    @Test
    fun `a title change mid-drag does not strand the drag`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var title by mutableStateOf("Running")

            setContent {
                Row(modifier = Modifier.testTag(CHIP_TAG)) {
                    TabFaviconChip(
                        tab = DragTestTab("tab-1", title = title),
                        isActive = true,
                        onClick = {},
                        tabDragComponent = component,
                        panelId = "panel-1",
                        tabIndex = 0,
                    )
                }
            }

            val chip = onNodeWithTag(CHIP_TAG)
            chip.performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertNotNull(component.draggingTab, "the drag should be under way")

            // What the terminal does every time its command changes: a brand-new TabInfo.
            title = "Review oldest pull request"
            waitForIdle()
            assertNotNull(component.draggingTab, "a new title must not end the drag")

            chip.performTouchInput { up() }
            assertNull(component.draggingTab, "the release must put the ghost down")
        }

    @Test
    fun `an index change mid-drag does not strand the drag`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var index by mutableStateOf(2)
            var result: TabDropResult? = null
            var callbackVersion by mutableStateOf(1)
            var endedBy = 0

            setContent {
                val version = callbackVersion
                Row(modifier = Modifier.testTag(CHIP_TAG)) {
                    TabFaviconChip(
                        tab = DragTestTab("tab-1"),
                        isActive = true,
                        onClick = {},
                        tabDragComponent = component,
                        panelId = "panel-1",
                        tabIndex = index,
                        onDragEnd = {
                            result = it
                            endedBy = version
                        },
                    )
                }
            }

            val chip = onNodeWithTag(CHIP_TAG)
            chip.performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertEquals(2, component.draggingTab?.sourceIndex, "the drag starts from where the tab was")

            // A tab ahead of this one closed while the drag was in the air.
            index = 1
            callbackVersion = 2
            waitForIdle()
            assertNotNull(component.draggingTab, "a reindex must not end the drag")
            // Register a deterministic first-slot target around the actual pointer position.
            val position = component.getCurrentPosition()!!
            val bounds = Rect(position.x - 1f, position.y - 20f, position.x + 100f, position.y + 20f)
            component.registerTabBarBounds("panel-1", bounds, vertical = false)
            component.registerTabBounds("panel-1:target", bounds, 0)

            chip.performTouchInput { up() }
            assertNull(component.draggingTab, "the release must put the ghost down")
            assertEquals(TabDropResult.Reorder("panel-1", 1, 0), result)
            assertEquals(2, endedBy)
        }

    @Test
    fun `button title and index updates preserve gesture and use latest callback`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var title by mutableStateOf("Running")
            var index by mutableStateOf(2)
            var callbackVersion by mutableStateOf(1)
            var endedBy = 0
            setContent {
                val version = callbackVersion
                Row(Modifier.testTag(CHIP_TAG)) {
                    BossTabButton(
                        fileName = title,
                        onClick = {},
                        tabInfo = DragTestTab("tab-1", title = title),
                        panelId = "panel-1",
                        tabIndex = index,
                        tabDragComponent = component,
                        onDragEnd = { endedBy = version },
                    )
                }
            }
            val chip = onNodeWithTag(CHIP_TAG)
            chip.performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertNotNull(component.draggingTab)
            title = "Changed"
            index = 1
            callbackVersion = 2
            waitForIdle()
            assertNotNull(component.draggingTab)
            chip.performTouchInput { up() }
            assertNull(component.draggingTab)
            assertEquals(2, endedBy)
        }

    @Test
    fun `disposing an idle copy does not cancel another chip gesture`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var showCopy by mutableStateOf(true)
            var showSource by mutableStateOf(true)
            setContent {
                Row {
                    if (showSource) {
                        Row(Modifier.testTag(CHIP_TAG)) {
                            TabFaviconChip(
                                tab = DragTestTab("tab-1"),
                                isActive = true,
                                onClick = {},
                                tabDragComponent = component,
                                panelId = "panel-1",
                                tabIndex = 0,
                            )
                        }
                    }
                    if (showCopy) {
                        TabFaviconChip(
                            tab = DragTestTab("tab-1"),
                            isActive = false,
                            onClick = {},
                            tabDragComponent = component,
                            panelId = "panel-2",
                            tabIndex = 0,
                        )
                    }
                }
            }
            onNodeWithTag(CHIP_TAG).performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertNotNull(component.draggingTab)
            showCopy = false
            waitForIdle()
            assertNotNull(component.draggingTab, "a node which never started this drag cannot cancel it")
            showSource = false
            waitForIdle()
            assertNull(component.draggingTab, "disposing the owner clears the ghost")
        }

    @Test
    fun `disposing an idle button copy does not cancel another button gesture`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var showCopy by mutableStateOf(true)
            var showSource by mutableStateOf(true)
            setContent {
                Row {
                    if (showSource) {
                        Row(Modifier.testTag(CHIP_TAG)) {
                            BossTabButton(
                                fileName = "tab-1",
                                onClick = {},
                                tabInfo = DragTestTab("tab-1"),
                                panelId = "panel-1",
                                tabIndex = 0,
                                tabDragComponent = component,
                            )
                        }
                    }
                    if (showCopy) {
                        BossTabButton(
                            fileName = "tab-1",
                            onClick = {},
                            tabInfo = DragTestTab("tab-1"),
                            panelId = "panel-2",
                            tabIndex = 0,
                            tabDragComponent = component,
                        )
                    }
                }
            }
            onNodeWithTag(CHIP_TAG).performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertNotNull(component.draggingTab)
            showCopy = false
            waitForIdle()
            assertNotNull(component.draggingTab, "a button which never started this drag cannot cancel it")
            showSource = false
            waitForIdle()
            assertNull(component.draggingTab, "disposing the owner clears the ghost")
        }


    @Test
    fun `changing a button identity cancels only its old gesture`() =
        runComposeUiTest {
            val component = TabDraggableComponent()
            var id by mutableStateOf("tab-1")
            var cancellations = 0
            setContent {
                Row(Modifier.testTag(CHIP_TAG)) {
                    BossTabButton(
                        fileName = id,
                        onClick = {},
                        tabInfo = DragTestTab(id),
                        panelId = "panel-1",
                        tabIndex = 0,
                        tabDragComponent = component,
                        onDragEnd = { if (it == null) cancellations++ },
                    )
                }
            }
            onNodeWithTag(CHIP_TAG).performTouchInput {
                down(center)
                moveBy(Offset(160f, 0f))
            }
            assertNotNull(component.draggingTab)
            id = "tab-2"
            waitForIdle()
            assertNull(component.draggingTab)
            assertEquals(1, cancellations)
        }

    @Test
    fun `reindexed source is the tab reordered on release`() {
        val component = TabDraggableComponent()
        component.registerTabBarBounds("panel-1", Rect(0f, 0f, 400f, 40f), vertical = false)
        component.registerTabBounds("panel-1:target", Rect(0f, 0f, 100f, 40f), 0)
        component.startDragging(DragTestTab("tab-1"), "panel-1", 2, Offset(10f, 20f))
        assertEquals(TabDropResult.Reorder("panel-1", 1, 0), component.endDrag(sourceIndex = 1))
        assertNull(component.draggingTab)
    }
}

private const val CHIP_TAG = "drag-chip"

private data class DragTestTab(
    override val id: String,
    override val typeId: TabTypeId = TabTypeId("drag-test", "test.plugin"),
    override val title: String = "Chip Test Tab",
) : TabInfo {
    override val icon get() = Icons.Outlined.Language
}
