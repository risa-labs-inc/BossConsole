package ai.rever.boss.ui.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WidgetTreeBuilderTest {

    @Test
    fun `build tree column with text button and row`() {
        val tree = widgetTree {
            column {
                text("Hello")
                button("Click me", "click1")
                row {
                    icon("star", 24)
                    text("World")
                }
            }
        }

        // column + text + button + row + icon + text = 6 nodes
        assertEquals(6, tree.nodes.size)

        val root = tree.nodes[tree.rootId]
        assertNotNull(root)
        assertEquals(WidgetType.COLUMN, root.type)
        assertEquals(3, root.childIds.size)
    }

    @Test
    fun `verify parent-child relationships`() {
        val tree = widgetTree {
            column {
                text("Hello")
                button("Click me", "click1")
                row {
                    icon("star", 24)
                    text("World")
                }
            }
        }

        val root = tree.nodes[tree.rootId]!!
        val textId = root.childIds[0]
        val buttonId = root.childIds[1]
        val rowId = root.childIds[2]

        assertEquals(WidgetType.TEXT, tree.nodes[textId]!!.type)
        assertEquals("Hello", tree.nodes[textId]!!.properties["value"])

        assertEquals(WidgetType.BUTTON, tree.nodes[buttonId]!!.type)
        assertEquals("Click me", tree.nodes[buttonId]!!.properties["label"])
        assertEquals("click1", tree.nodes[buttonId]!!.properties["onClickEvent"])

        val rowNode = tree.nodes[rowId]!!
        assertEquals(WidgetType.ROW, rowNode.type)
        assertEquals(2, rowNode.childIds.size)

        assertEquals(WidgetType.ICON, tree.nodes[rowNode.childIds[0]]!!.type)
        assertEquals(WidgetType.TEXT, tree.nodes[rowNode.childIds[1]]!!.type)
        assertEquals("World", tree.nodes[rowNode.childIds[1]]!!.properties["value"])
    }

    @Test
    fun `build tree with all leaf widget types`() {
        val tree = widgetTree {
            column {
                text("label")
                icon("home")
                button("OK", "ok_event")
                textField("", "change_event", "placeholder")
                checkbox(true, "toggle_event", "Accept")
                dropdown("opt1", listOf("opt1", "opt2"), "select_event")
                progress(0.5f, false)
                spacer(16)
                divider()
                list(listOf("a", "b", "c"))
            }
        }

        // 1 column + 10 leaf nodes = 11
        assertEquals(11, tree.nodes.size)
        val root = tree.nodes[tree.rootId]!!
        assertEquals(10, root.childIds.size)
    }

    @Test
    fun `scroll container wraps children`() {
        val tree = widgetTree {
            scroll {
                text("item1")
                text("item2")
            }
        }

        assertEquals(3, tree.nodes.size)
        val root = tree.nodes[tree.rootId]!!
        assertEquals(WidgetType.SCROLL, root.type)
        assertEquals(2, root.childIds.size)
    }
}
