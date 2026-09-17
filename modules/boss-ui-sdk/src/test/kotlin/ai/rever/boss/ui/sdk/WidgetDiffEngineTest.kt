package ai.rever.boss.ui.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WidgetDiffEngineTest {
    private fun simpleTree(): WidgetTree {
        val colId = "col1"
        val textId = "text1"
        return WidgetTree(
            rootId = colId,
            nodes =
                mapOf(
                    colId to WidgetNode(colId, WidgetType.COLUMN, childIds = listOf(textId)),
                    textId to WidgetNode(textId, WidgetType.TEXT, properties = mapOf("value" to "Hello")),
                ),
        )
    }

    @Test
    fun `diff identical trees produces no operations`() {
        val tree = simpleTree()
        val ops = WidgetDiffEngine.diff(tree, tree)
        assertTrue(ops.isEmpty(), "Expected empty diff for identical trees, got: $ops")
    }

    @Test
    fun `add node produces NodeAdded`() {
        val base = simpleTree()
        val colId = "col1"
        val textId = "text1"
        val text2Id = "text2"
        val newTree =
            WidgetTree(
                rootId = colId,
                nodes =
                    mapOf(
                        colId to WidgetNode(colId, WidgetType.COLUMN, childIds = listOf(textId, text2Id)),
                        textId to WidgetNode(textId, WidgetType.TEXT, properties = mapOf("value" to "Hello")),
                        text2Id to WidgetNode(text2Id, WidgetType.TEXT, properties = mapOf("value" to "World")),
                    ),
            )

        val ops = WidgetDiffEngine.diff(base, newTree)
        val added = ops.filterIsInstance<DiffOperation.NodeAdded>()
        assertEquals(1, added.size)
        assertEquals(text2Id, added[0].node.id)
        assertEquals(colId, added[0].parentId)
        assertEquals(1, added[0].index)
    }

    @Test
    fun `remove node produces NodeRemoved`() {
        val base = simpleTree()
        val colId = "col1"
        val textId = "text1"
        val newTree =
            WidgetTree(
                rootId = colId,
                nodes =
                    mapOf(
                        colId to WidgetNode(colId, WidgetType.COLUMN, childIds = emptyList()),
                    ),
            )

        val ops = WidgetDiffEngine.diff(base, newTree)
        val removed = ops.filterIsInstance<DiffOperation.NodeRemoved>()
        assertEquals(1, removed.size)
        assertEquals(textId, removed[0].nodeId)
    }

    @Test
    fun `change property produces NodeUpdated`() {
        val base = simpleTree()
        val colId = "col1"
        val textId = "text1"
        val newTree =
            WidgetTree(
                rootId = colId,
                nodes =
                    mapOf(
                        colId to WidgetNode(colId, WidgetType.COLUMN, childIds = listOf(textId)),
                        textId to WidgetNode(textId, WidgetType.TEXT, properties = mapOf("value" to "Changed")),
                    ),
            )

        val ops = WidgetDiffEngine.diff(base, newTree)
        val updated = ops.filterIsInstance<DiffOperation.NodeUpdated>()
        assertEquals(1, updated.size)
        assertEquals(textId, updated[0].nodeId)
        assertEquals("Changed", updated[0].changedProperties["value"])
    }

    @Test
    fun `change modifier produces NodeUpdated with newModifier`() {
        val base = simpleTree()
        val colId = "col1"
        val textId = "text1"
        val newModifier = WidgetModifier(width = 100, height = 50)
        val newTree =
            WidgetTree(
                rootId = colId,
                nodes =
                    mapOf(
                        colId to WidgetNode(colId, WidgetType.COLUMN, childIds = listOf(textId)),
                        textId to
                            WidgetNode(
                                textId,
                                WidgetType.TEXT,
                                properties = mapOf("value" to "Hello"),
                                modifier = newModifier,
                            ),
                    ),
            )

        val ops = WidgetDiffEngine.diff(base, newTree)
        val updated = ops.filterIsInstance<DiffOperation.NodeUpdated>()
        assertEquals(1, updated.size)
        assertEquals(textId, updated[0].nodeId)
        assertEquals(newModifier, updated[0].newModifier)
    }

    @Test
    fun `apply NodeAdded matches expected tree`() {
        val base = simpleTree()
        val colId = "col1"
        val textId = "text1"
        val text2Id = "text2"
        val newNode = WidgetNode(text2Id, WidgetType.TEXT, properties = mapOf("value" to "World"))

        val ops = listOf(DiffOperation.NodeAdded(newNode, colId, 1))
        val result = WidgetDiffEngine.apply(base, ops)

        assertEquals(3, result.nodes.size)
        val col = result.nodes[colId]!!
        assertEquals(listOf(textId, text2Id), col.childIds)
        assertEquals("World", result.nodes[text2Id]!!.properties["value"])
    }

    @Test
    fun `apply NodeRemoved matches expected tree`() {
        val colId = "col1"
        val text1Id = "text1"
        val text2Id = "text2"
        val base =
            WidgetTree(
                rootId = colId,
                nodes =
                    mapOf(
                        colId to WidgetNode(colId, WidgetType.COLUMN, childIds = listOf(text1Id, text2Id)),
                        text1Id to WidgetNode(text1Id, WidgetType.TEXT, properties = mapOf("value" to "A")),
                        text2Id to WidgetNode(text2Id, WidgetType.TEXT, properties = mapOf("value" to "B")),
                    ),
            )

        val ops = listOf(DiffOperation.NodeRemoved(text1Id))
        val result = WidgetDiffEngine.apply(base, ops)

        assertEquals(2, result.nodes.size)
        assertEquals(listOf(text2Id), result.nodes[colId]!!.childIds)
    }

    @Test
    fun `apply NodeUpdated changes properties`() {
        val base = simpleTree()
        val textId = "text1"

        val ops = listOf(DiffOperation.NodeUpdated(textId, mapOf("value" to "Updated"), null))
        val result = WidgetDiffEngine.apply(base, ops)

        assertEquals("Updated", result.nodes[textId]!!.properties["value"])
    }

    // ---- Subtree adds must not double-link children (issue #34 item 7) ----

    /**
     * A `NodeAdded` payload carries the added node's own `childIds`, so a subtree add links the
     * child from the parent's payload *and* from the child's own op. Applying them parent-first used
     * to link the child twice (and the renderer drew the subtree twice); insertion is now idempotent,
     * so both orders converge.
     */
    private fun subtreeAddOps(): List<DiffOperation> {
        val rowId = "row1"
        val innerId = "inner1"
        val row = WidgetNode(rowId, WidgetType.ROW, childIds = listOf(innerId))
        val inner = WidgetNode(innerId, WidgetType.TEXT, properties = mapOf("value" to "Nested"))
        return listOf(
            DiffOperation.NodeAdded(row, "col1", 1),
            DiffOperation.NodeAdded(inner, rowId, 0),
        )
    }

    @Test
    fun `apply subtree add parent-first does not duplicate child links`() {
        val result = WidgetDiffEngine.apply(simpleTree(), subtreeAddOps())

        assertEquals(listOf("inner1"), result.nodes["row1"]!!.childIds)
        assertEquals(listOf("text1", "row1"), result.nodes["col1"]!!.childIds)
    }

    @Test
    fun `apply subtree add child-first matches parent-first`() {
        val parentFirst = WidgetDiffEngine.apply(simpleTree(), subtreeAddOps())
        val childFirst = WidgetDiffEngine.apply(simpleTree(), subtreeAddOps().reversed())

        assertEquals(parentFirst.nodes, childFirst.nodes)
    }

    @Test
    fun `diff then apply of a subtree add matches the new tree exactly`() {
        val base = simpleTree()
        val expected =
            WidgetTree(
                rootId = "col1",
                nodes =
                    mapOf(
                        "col1" to WidgetNode("col1", WidgetType.COLUMN, childIds = listOf("text1", "row1")),
                        "text1" to WidgetNode("text1", WidgetType.TEXT, properties = mapOf("value" to "Hello")),
                        "row1" to WidgetNode("row1", WidgetType.ROW, childIds = listOf("inner1")),
                        "inner1" to WidgetNode("inner1", WidgetType.TEXT, properties = mapOf("value" to "Nested")),
                    ),
            )

        val result = WidgetDiffEngine.apply(base, WidgetDiffEngine.diff(base, expected))

        assertEquals(expected.nodes, result.nodes)
    }

    @Test
    fun `two siblings added to the same existing parent converge in either order`() {
        val base = simpleTree()
        val expected =
            WidgetTree(
                rootId = "col1",
                nodes =
                    mapOf(
                        "col1" to WidgetNode("col1", WidgetType.COLUMN, childIds = listOf("text1", "a", "b")),
                        "text1" to WidgetNode("text1", WidgetType.TEXT, properties = mapOf("value" to "Hello")),
                        "a" to WidgetNode("a", WidgetType.TEXT, properties = mapOf("value" to "A")),
                        "b" to WidgetNode("b", WidgetType.TEXT, properties = mapOf("value" to "B")),
                    ),
            )

        val ops = WidgetDiffEngine.diff(base, expected)
        val forward = WidgetDiffEngine.apply(base, ops)
        val reversed = WidgetDiffEngine.apply(base, ops.reversed())

        assertEquals(expected.nodes, forward.nodes)
        assertEquals(listOf("text1", "a", "b"), reversed.nodes["col1"]!!.childIds)
    }

    // ---- Sibling reorder (review finding 4) ----

    private fun columnOf(vararg children: String): WidgetTree =
        WidgetTree(
            rootId = "col1",
            nodes =
                buildMap {
                    put("col1", WidgetNode("col1", WidgetType.COLUMN, childIds = children.toList()))
                    for (child in children) {
                        put(child, WidgetNode(child, WidgetType.TEXT, properties = mapOf("value" to child)))
                    }
                },
        )

    @Test
    fun `swapping two siblings produces operations`() {
        val before = columnOf("a", "b")
        val after = columnOf("b", "a")

        val ops = WidgetDiffEngine.diff(before, after)

        // Used to be empty: NodeMoved was only emitted when the PARENT changed, so a reorder was
        // invisible and apply(old, diff(old, new)) != new.
        assertTrue(ops.isNotEmpty(), "a reorder must be expressible")
        assertEquals(after.nodes, WidgetDiffEngine.apply(before, ops).nodes)
    }

    @Test
    fun `reorder round-trips for every permutation of three siblings`() {
        val before = columnOf("a", "b", "c")
        val permutations =
            listOf(
                listOf("a", "b", "c"),
                listOf("a", "c", "b"),
                listOf("b", "a", "c"),
                listOf("b", "c", "a"),
                listOf("c", "a", "b"),
                listOf("c", "b", "a"),
            )

        for (order in permutations) {
            val after = columnOf(*order.toTypedArray())
            val result = WidgetDiffEngine.apply(before, WidgetDiffEngine.diff(before, after))
            assertEquals(order, result.nodes["col1"]!!.childIds, "reorder to $order")
        }
    }

    @Test
    fun `an insertion alone is not reported as a reorder`() {
        val before = columnOf("a", "b")
        val after = columnOf("a", "x", "b")

        val ops = WidgetDiffEngine.diff(before, after)

        // `a` and `b` shift index but keep their relative order, so the add is the whole story.
        assertEquals(1, ops.size, "expected only the add, got: $ops")
        assertIs<DiffOperation.NodeAdded>(ops.single())
        assertEquals(after.nodes, WidgetDiffEngine.apply(before, ops).nodes)
    }

    @Test
    fun `reorder combined with an insertion round-trips`() {
        val before = columnOf("a", "b")
        val after = columnOf("x", "b", "a")

        val result = WidgetDiffEngine.apply(before, WidgetDiffEngine.diff(before, after))

        assertEquals(after.nodes, result.nodes)
    }

    @Test
    fun `reorder combined with a removal round-trips`() {
        val before = columnOf("a", "b", "c")
        val after = columnOf("c", "a")

        val result = WidgetDiffEngine.apply(before, WidgetDiffEngine.diff(before, after))

        assertEquals(after.nodes, result.nodes)
    }

    @Test
    fun `a cross-parent move alongside a reorder round-trips`() {
        val before =
            WidgetTree(
                rootId = "root",
                nodes =
                    mapOf(
                        "root" to WidgetNode("root", WidgetType.COLUMN, childIds = listOf("p1", "p2")),
                        "p1" to WidgetNode("p1", WidgetType.ROW, childIds = listOf("a", "b", "c")),
                        "p2" to WidgetNode("p2", WidgetType.ROW, childIds = emptyList()),
                        "a" to WidgetNode("a", WidgetType.TEXT),
                        "b" to WidgetNode("b", WidgetType.TEXT),
                        "c" to WidgetNode("c", WidgetType.TEXT),
                    ),
            )
        val after =
            WidgetTree(
                rootId = "root",
                nodes =
                    mapOf(
                        "root" to WidgetNode("root", WidgetType.COLUMN, childIds = listOf("p1", "p2")),
                        "p1" to WidgetNode("p1", WidgetType.ROW, childIds = listOf("c", "b")),
                        "p2" to WidgetNode("p2", WidgetType.ROW, childIds = listOf("a")),
                        "a" to WidgetNode("a", WidgetType.TEXT),
                        "b" to WidgetNode("b", WidgetType.TEXT),
                        "c" to WidgetNode("c", WidgetType.TEXT),
                    ),
            )

        val result = WidgetDiffEngine.apply(before, WidgetDiffEngine.diff(before, after))

        assertEquals(after.nodes, result.nodes)
    }

    @Test
    fun `apply NodeMoved within the same parent re-indexes without duplicating`() {
        val base =
            WidgetTree(
                rootId = "col1",
                nodes =
                    mapOf(
                        "col1" to WidgetNode("col1", WidgetType.COLUMN, childIds = listOf("a", "b")),
                        "a" to WidgetNode("a", WidgetType.TEXT),
                        "b" to WidgetNode("b", WidgetType.TEXT),
                    ),
            )

        val result = WidgetDiffEngine.apply(base, listOf(DiffOperation.NodeMoved("a", "col1", 1)))

        assertEquals(listOf("b", "a"), result.nodes["col1"]!!.childIds)
    }

    @Test
    fun `apply diff round-trip matches new tree`() {
        val base = simpleTree()
        val colId = "col1"
        val textId = "text1"
        val text2Id = "text2"
        val expected =
            WidgetTree(
                rootId = colId,
                nodes =
                    mapOf(
                        colId to WidgetNode(colId, WidgetType.COLUMN, childIds = listOf(textId, text2Id)),
                        textId to WidgetNode(textId, WidgetType.TEXT, properties = mapOf("value" to "Hello")),
                        text2Id to WidgetNode(text2Id, WidgetType.TEXT, properties = mapOf("value" to "World")),
                    ),
            )

        val ops = WidgetDiffEngine.diff(base, expected)
        val result = WidgetDiffEngine.apply(base, ops)

        assertEquals(expected.nodes.keys, result.nodes.keys)
        assertEquals(expected.nodes[colId]!!.childIds, result.nodes[colId]!!.childIds)
        assertEquals("World", result.nodes[text2Id]!!.properties["value"])
    }

    @Test
    fun `property removal is an explicit patch and converges exactly`() {
        val before = propertyTree(mapOf("value" to "Ready", "style" to "muted"))
        val after = propertyTree(mapOf("value" to "Ready"))

        val update = assertIs<DiffOperation.NodeUpdated>(WidgetDiffEngine.diff(before, after).single())
        val applied = WidgetDiffEngine.apply(before, listOf(update))

        assertEquals(setOf("style"), update.removedProperties)
        assertFalse("style" in update.changedProperties, "removal must not masquerade as an empty value")
        assertEquals(after.nodes, applied.nodes)
        assertTrue(WidgetDiffEngine.diff(applied, after).isEmpty(), "an applied removal must settle the diff")
    }

    @Test
    fun `empty string remains a real value rather than becoming removal`() {
        val before = propertyTree(mapOf("value" to "Ready"))
        val after = propertyTree(mapOf("value" to ""))

        val update = assertIs<DiffOperation.NodeUpdated>(WidgetDiffEngine.diff(before, after).single())
        val applied = WidgetDiffEngine.apply(before, listOf(update))

        assertEquals(mapOf("value" to ""), update.changedProperties)
        assertTrue(update.removedProperties.isEmpty())
        assertEquals("", applied.nodes["node"]?.properties?.get("value"))
        assertTrue("value" in applied.nodes.getValue("node").properties)
    }

    @Test
    fun `explicit change wins over a contradictory hand-built removal`() {
        val before = propertyTree(mapOf("label" to "Before"))
        val update =
            DiffOperation.NodeUpdated(
                nodeId = "node",
                changedProperties = mapOf("label" to "After"),
                newModifier = null,
                removedProperties = setOf("label"),
            )

        val applied = WidgetDiffEngine.apply(before, listOf(update))

        assertEquals("After", applied.nodes["node"]?.properties?.get("label"))
    }

    @Test
    fun `legacy three argument constructor remains available on the JVM`() {
        val type = DiffOperation.NodeUpdated::class.java
        val constructor =
            type.getDeclaredConstructor(
                String::class.java,
                Map::class.java,
                WidgetModifier::class.java,
            )
        val copy = type.getDeclaredMethod("copy", String::class.java, Map::class.java, WidgetModifier::class.java)
        val component1 = type.getDeclaredMethod("component1")
        val component2 = type.getDeclaredMethod("component2")
        val component3 = type.getDeclaredMethod("component3")
        val copyDefault =
            type.getDeclaredMethod(
                "copy\$default",
                type,
                String::class.java,
                Map::class.java,
                WidgetModifier::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )

        val update = constructor.newInstance("node", mapOf("value" to "next"), null)

        assertTrue(update.removedProperties.isEmpty())
        assertEquals(type, copy.returnType)
        assertEquals(type, copyDefault.returnType)
        assertEquals(String::class.java, component1.returnType)
        assertEquals(Map::class.java, component2.returnType)
        assertEquals(WidgetModifier::class.java, component3.returnType)
    }

    private fun propertyTree(properties: Map<String, String>): WidgetTree =
        WidgetTree(
            rootId = "node",
            nodes = mapOf("node" to WidgetNode("node", WidgetType.TEXT, properties = properties)),
        )
}
