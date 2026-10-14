package ai.rever.boss.ui.sdk

import ai.rever.boss.ui.sdk.WidgetProtoConverter.decodeOperations
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toProtoDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ai.rever.boss.ipc.proto.DiffOperation as ProtoDiffOp
import ai.rever.boss.ipc.proto.WidgetDiff as ProtoWidgetDiff

/**
 * `WidgetDiff` in the receiving direction.
 *
 * `toProtoDiff` shipped without an inverse, which made `WidgetUpdate.diff` a write-only half of the
 * protocol: a receiver could decode `full_tree` and nothing else. The host transport consequently only
 * ever handled full trees, so a plugin sending incremental updates — the reason diffs exist — drew
 * nothing at all.
 */
class WidgetDiffWireTest {
    @Test
    fun `every operation kind survives a round trip`() {
        val operations =
            listOf(
                DiffOperation.NodeAdded(
                    node =
                        WidgetNode(
                            id = "added",
                            type = WidgetType.BUTTON,
                            properties = mapOf("label" to "Save", "clickEventId" to "save"),
                            childIds = listOf("child-a", "child-b"),
                            modifier = WidgetModifier(width = -1, paddingTop = 8, backgroundColor = "panel"),
                        ),
                    parentId = "root",
                    index = 2,
                ),
                DiffOperation.NodeRemoved("gone"),
                DiffOperation.NodeUpdated(
                    nodeId = "updated",
                    changedProperties = mapOf("value" to "typed", "placeholder" to ""),
                    newModifier = WidgetModifier(height = 24, clickable = true, clickEventId = "row"),
                    removedProperties = setOf("style", "onChangeEvent"),
                ),
                DiffOperation.NodeMoved("moved", "new-parent", 1),
            )

        val decoded = operations.toProtoDiff(baseVersion = 7, newVersion = 8).decodeOperations()

        assertEquals(operations, decoded.operations)
        assertEquals(0, decoded.skipped)
    }

    @Test
    fun `an update with no modifier decodes as no modifier change`() {
        // `NodeUpdated.modifier` documents "null = no change"; reading proto3's zero value instead would
        // reset a node's whole layout on every property-only update.
        val operations = listOf<DiffOperation>(DiffOperation.NodeUpdated("n", mapOf("value" to "x"), null))

        val decoded = operations.roundTrip(baseVersion = 1, newVersion = 2).operations.single()

        assertNull((decoded as DiffOperation.NodeUpdated).newModifier)
    }

    @Test
    fun `versions are carried on the diff itself`() {
        val diff = listOf<DiffOperation>(DiffOperation.NodeRemoved("n")).toProtoDiff(baseVersion = 3, newVersion = 4)

        assertEquals(3L, diff.baseVersion)
        assertEquals(4L, diff.newVersion)
    }

    @Test
    fun `an operation with no oneof set is skipped, not guessed at`() {
        // What a sender built against a newer proto looks like from here.
        val diff =
            ProtoWidgetDiff
                .newBuilder()
                .addOperations(ProtoDiffOp.getDefaultInstance())
                .addOperations(
                    listOf<DiffOperation>(DiffOperation.NodeRemoved("real")).toProtoDiff(0, 0).getOperations(0),
                ).build()

        val decoded = diff.decodeOperations()

        assertEquals(listOf<DiffOperation>(DiffOperation.NodeRemoved("real")), decoded.operations)
        assertEquals(1, decoded.skipped, "a caller must be able to see that the tree may now diverge")
    }

    @Test
    fun `a diff round trip reproduces the tree the sender diffed to`() {
        // The property that makes diffs usable at all: decode ∘ encode ∘ diff, applied to the old tree,
        // must equal the new one.
        val before =
            widgetTree {
                column {
                    text("Inbox")
                    button("Refresh", "refresh")
                }
            }
        val after =
            widgetTree {
                column {
                    text("Inbox (3)")
                    button("Refresh", "refresh")
                    text("newest first")
                }
            }

        val wire = WidgetDiffEngine.diff(before, after).toProtoDiff(before.version, after.version)
        val applied = WidgetDiffEngine.apply(before, wire.decodeOperations().operations)

        assertEquals(after.nodes, applied.nodes)
        assertTrue(wire.operationsCount > 0, "a changed tree must produce operations")
    }

    @Test
    fun `wire keeps deletion separate from an intentional empty value`() {
        val operation =
            DiffOperation.NodeUpdated(
                nodeId = "field",
                changedProperties = mapOf("value" to ""),
                newModifier = null,
                removedProperties = setOf("placeholder", "onChangeEvent"),
            )

        val wire = listOf<DiffOperation>(operation).toProtoDiff(baseVersion = 4, newVersion = 5)
        val encoded = wire.getOperations(0).updated

        assertEquals("", encoded.changedPropertiesMap.getValue("value"))
        assertEquals(listOf("onChangeEvent", "placeholder"), encoded.removedPropertiesList)
        assertEquals(listOf(operation), wire.decodeOperations().operations)
    }

    @Test
    fun `old wire update with an empty value still means set empty`() {
        val legacy =
            ai.rever.boss.ipc.proto.NodeUpdated
                .newBuilder()
                .setNodeId("field")
                .putChangedProperties("value", "")
                .build()
        val wire =
            ProtoWidgetDiff
                .newBuilder()
                .addOperations(ProtoDiffOp.newBuilder().setUpdated(legacy))
                .build()

        val decoded = assertIs<DiffOperation.NodeUpdated>(wire.decodeOperations().operations.single())

        assertEquals(mapOf("value" to ""), decoded.changedProperties)
        assertTrue(decoded.removedProperties.isEmpty())
    }

    private fun List<DiffOperation>.roundTrip(
        baseVersion: Long,
        newVersion: Long,
    ): DecodedWidgetDiff = toProtoDiff(baseVersion, newVersion).decodeOperations()
}
