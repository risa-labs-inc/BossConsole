package ai.rever.boss.ui.sdk

import ai.rever.boss.ipc.proto.DiffOperation as ProtoDiffOp
import ai.rever.boss.ipc.proto.NodeAdded as ProtoNodeAdded
import ai.rever.boss.ipc.proto.NodeMoved as ProtoNodeMoved
import ai.rever.boss.ipc.proto.NodeRemoved as ProtoNodeRemoved
import ai.rever.boss.ipc.proto.NodeUpdated as ProtoNodeUpdated
import ai.rever.boss.ipc.proto.WidgetDiff as ProtoWidgetDiff
import ai.rever.boss.ipc.proto.WidgetModifier as ProtoWidgetModifier
import ai.rever.boss.ipc.proto.WidgetNode as ProtoWidgetNode
import ai.rever.boss.ipc.proto.WidgetTree as ProtoWidgetTree
import ai.rever.boss.ipc.proto.WidgetType as ProtoWidgetType

/**
 * A wire diff read back into SDK operations.
 *
 * @property skipped operations whose `oneof` was unset — a sender built against a newer proto, or a
 *   malformed one. **Non-zero means the receiver's tree may now differ structurally from the sender's**:
 *   a dropped `NodeAdded` or `NodeRemoved` cannot be reconstructed from the operations around it, so a
 *   caller that applies the rest should stop trusting its own version numbering rather than pretend the
 *   diff landed whole.
 */
data class DecodedWidgetDiff(
    val operations: List<DiffOperation>,
    val skipped: Int,
)

object WidgetProtoConverter {
    fun WidgetTree.toProto(): ProtoWidgetTree =
        ProtoWidgetTree
            .newBuilder()
            .setRootId(rootId)
            .addAllNodes(nodes.values.map { it.toProto() })
            .setVersion(version)
            .build()

    fun ProtoWidgetTree.toKotlin(): WidgetTree =
        WidgetTree(
            rootId = rootId,
            nodes = nodesList.associate { it.id to it.toKotlin() },
            version = version,
        )

    /**
     * Read a wire diff back into SDK operations, ready for [WidgetDiffEngine.apply].
     *
     * The forward direction ([toProtoDiff]) shipped without an inverse, so `WidgetUpdate.diff` was a
     * write-only half of the protocol: a receiver could decode `full_tree` and nothing else, which is
     * why the host transport only ever handled full trees. Operations whose `oneof` is unset are
     * skipped rather than guessed at — a sender built against a newer proto is not a reason to
     * misapply the ops around it.
     *
     * The skipped count is returned rather than swallowed: a dropped `NodeAdded` or `NodeRemoved` leaves
     * the receiver's tree structurally different from the sender's, and a caller that cannot see that
     * happened has no way to stop trusting its own copy.
     */
    fun ProtoWidgetDiff.decodeOperations(): DecodedWidgetDiff {
        // One pass, no intermediate nullable list: the proto documents diffs as the steady state after a
        // surface's first full tree, so this runs on every update. Inlined rather than a named helper
        // because WidgetProtoConverter sits one function below detekt's TooManyFunctions threshold for
        // objects, and splitting the converter to name this would trade a real seam for a cosmetic one.
        var skipped = 0
        val operations = ArrayList<DiffOperation>(operationsCount)
        operationsList.forEach { op ->
            val decoded =
                when (op.opCase) {
                    ProtoDiffOp.OpCase.ADDED -> {
                        DiffOperation.NodeAdded(op.added.node.toKotlin(), op.added.parentId, op.added.index)
                    }

                    ProtoDiffOp.OpCase.REMOVED -> {
                        DiffOperation.NodeRemoved(op.removed.nodeId)
                    }

                    ProtoDiffOp.OpCase.UPDATED -> {
                        DiffOperation.NodeUpdated(
                            nodeId = op.updated.nodeId,
                            changedProperties = op.updated.changedPropertiesMap.toMap(),
                            // `NodeUpdated.modifier` documents "null = no change", and proto3 message presence
                            // is the only thing that distinguishes that from "reset every field to its
                            // default" — reading the field unconditionally would silently wipe a node's
                            // layout on any property-only update.
                            newModifier = if (op.updated.hasModifier()) op.updated.modifier.toKotlin() else null,
                            removedProperties = op.updated.removedPropertiesList.toSet(),
                        )
                    }

                    ProtoDiffOp.OpCase.MOVED -> {
                        DiffOperation.NodeMoved(op.moved.nodeId, op.moved.newParentId, op.moved.newIndex)
                    }

                    ProtoDiffOp.OpCase.OP_NOT_SET, null -> {
                        null
                    }
                }
            if (decoded == null) skipped++ else operations += decoded
        }
        return DecodedWidgetDiff(operations = operations, skipped = skipped)
    }

    private fun ProtoWidgetNode.toKotlin(): WidgetNode =
        WidgetNode(
            id = id,
            type = type.toKotlin(),
            properties = propertiesMap.toMap(),
            childIds = childIdsList.toList(),
            modifier = modifier.toKotlin(),
        )

    fun List<DiffOperation>.toProtoDiff(
        baseVersion: Long,
        newVersion: Long,
    ): ProtoWidgetDiff {
        val builder =
            ProtoWidgetDiff
                .newBuilder()
                .setBaseVersion(baseVersion)
                .setNewVersion(newVersion)

        for (op in this) {
            val protoDiffOp =
                when (op) {
                    is DiffOperation.NodeAdded -> {
                        val added =
                            ProtoNodeAdded
                                .newBuilder()
                                .setNode(op.node.toProto())
                                .setParentId(op.parentId)
                                .setIndex(op.index)
                                .build()
                        ProtoDiffOp.newBuilder().setAdded(added).build()
                    }

                    is DiffOperation.NodeRemoved -> {
                        val removed =
                            ProtoNodeRemoved
                                .newBuilder()
                                .setNodeId(op.nodeId)
                                .build()
                        ProtoDiffOp.newBuilder().setRemoved(removed).build()
                    }

                    is DiffOperation.NodeUpdated -> {
                        val updated =
                            ProtoNodeUpdated
                                .newBuilder()
                                .setNodeId(op.nodeId)
                                .putAllChangedProperties(op.changedProperties)
                                .addAllRemovedProperties(op.removedProperties.sorted())
                                .apply { op.newModifier?.let { setModifier(it.toProto()) } }
                                .build()
                        ProtoDiffOp.newBuilder().setUpdated(updated).build()
                    }

                    is DiffOperation.NodeMoved -> {
                        val moved =
                            ProtoNodeMoved
                                .newBuilder()
                                .setNodeId(op.nodeId)
                                .setNewParentId(op.newParentId)
                                .setNewIndex(op.newIndex)
                                .build()
                        ProtoDiffOp.newBuilder().setMoved(moved).build()
                    }
                }
            builder.addOperations(protoDiffOp)
        }

        return builder.build()
    }

    private fun WidgetNode.toProto(): ProtoWidgetNode =
        ProtoWidgetNode
            .newBuilder()
            .setId(id)
            .setType(type.toProto())
            .putAllProperties(properties)
            .addAllChildIds(childIds)
            .setModifier(modifier.toProto())
            .build()

    private fun WidgetModifier.toProto(): ProtoWidgetModifier =
        ProtoWidgetModifier
            .newBuilder()
            .setWidth(width)
            .setHeight(height)
            .setPaddingStart(paddingStart)
            .setPaddingTop(paddingTop)
            .setPaddingEnd(paddingEnd)
            .setPaddingBottom(paddingBottom)
            .setBackgroundColor(backgroundColor)
            .setAlpha(alpha)
            .setClickable(clickable)
            .setClickEventId(clickEventId)
            .build()

    private fun ProtoWidgetModifier.toKotlin(): WidgetModifier =
        WidgetModifier(
            width = width,
            height = height,
            paddingStart = paddingStart,
            paddingTop = paddingTop,
            paddingEnd = paddingEnd,
            paddingBottom = paddingBottom,
            backgroundColor = backgroundColor,
            // Canonicalized, NOT copied verbatim: proto3's unset 0.0 and the Kotlin default 1f both
            // mean "opaque", and WidgetDiffEngine compares modifiers structurally. See
            // normalizeWireAlpha.
            alpha = normalizeWireAlpha(alpha),
            clickable = clickable,
            clickEventId = clickEventId,
        )

    private fun WidgetType.toProto(): ProtoWidgetType =
        when (this) {
            WidgetType.COLUMN -> ProtoWidgetType.WIDGET_TYPE_COLUMN
            WidgetType.ROW -> ProtoWidgetType.WIDGET_TYPE_ROW
            WidgetType.BOX -> ProtoWidgetType.WIDGET_TYPE_BOX
            WidgetType.SCROLL -> ProtoWidgetType.WIDGET_TYPE_SCROLL
            WidgetType.TEXT -> ProtoWidgetType.WIDGET_TYPE_TEXT
            WidgetType.ICON -> ProtoWidgetType.WIDGET_TYPE_ICON
            WidgetType.IMAGE -> ProtoWidgetType.WIDGET_TYPE_IMAGE
            WidgetType.DIVIDER -> ProtoWidgetType.WIDGET_TYPE_DIVIDER
            WidgetType.SPACER -> ProtoWidgetType.WIDGET_TYPE_SPACER
            WidgetType.PROGRESS -> ProtoWidgetType.WIDGET_TYPE_PROGRESS
            WidgetType.BUTTON -> ProtoWidgetType.WIDGET_TYPE_BUTTON
            WidgetType.TEXT_FIELD -> ProtoWidgetType.WIDGET_TYPE_TEXT_FIELD
            WidgetType.CHECKBOX -> ProtoWidgetType.WIDGET_TYPE_CHECKBOX
            WidgetType.DROPDOWN -> ProtoWidgetType.WIDGET_TYPE_DROPDOWN
            WidgetType.TOGGLE -> ProtoWidgetType.WIDGET_TYPE_TOGGLE
            WidgetType.LIST -> ProtoWidgetType.WIDGET_TYPE_LIST
            WidgetType.TREE -> ProtoWidgetType.WIDGET_TYPE_TREE
            WidgetType.TABLE -> ProtoWidgetType.WIDGET_TYPE_TABLE
            WidgetType.TAB_ROW -> ProtoWidgetType.WIDGET_TYPE_TAB_ROW
            WidgetType.CODE_EDITOR -> ProtoWidgetType.WIDGET_TYPE_CODE_EDITOR
            WidgetType.TERMINAL -> ProtoWidgetType.WIDGET_TYPE_TERMINAL
            WidgetType.BROWSER -> ProtoWidgetType.WIDGET_TYPE_BROWSER
            WidgetType.CANVAS -> ProtoWidgetType.WIDGET_TYPE_CANVAS
        }

    private fun ProtoWidgetType.toKotlin(): WidgetType =
        when (this) {
            ProtoWidgetType.WIDGET_TYPE_COLUMN -> WidgetType.COLUMN
            ProtoWidgetType.WIDGET_TYPE_ROW -> WidgetType.ROW
            ProtoWidgetType.WIDGET_TYPE_BOX -> WidgetType.BOX
            ProtoWidgetType.WIDGET_TYPE_SCROLL -> WidgetType.SCROLL
            ProtoWidgetType.WIDGET_TYPE_TEXT -> WidgetType.TEXT
            ProtoWidgetType.WIDGET_TYPE_ICON -> WidgetType.ICON
            ProtoWidgetType.WIDGET_TYPE_IMAGE -> WidgetType.IMAGE
            ProtoWidgetType.WIDGET_TYPE_DIVIDER -> WidgetType.DIVIDER
            ProtoWidgetType.WIDGET_TYPE_SPACER -> WidgetType.SPACER
            ProtoWidgetType.WIDGET_TYPE_PROGRESS -> WidgetType.PROGRESS
            ProtoWidgetType.WIDGET_TYPE_BUTTON -> WidgetType.BUTTON
            ProtoWidgetType.WIDGET_TYPE_TEXT_FIELD -> WidgetType.TEXT_FIELD
            ProtoWidgetType.WIDGET_TYPE_CHECKBOX -> WidgetType.CHECKBOX
            ProtoWidgetType.WIDGET_TYPE_DROPDOWN -> WidgetType.DROPDOWN
            ProtoWidgetType.WIDGET_TYPE_TOGGLE -> WidgetType.TOGGLE
            ProtoWidgetType.WIDGET_TYPE_LIST -> WidgetType.LIST
            ProtoWidgetType.WIDGET_TYPE_TREE -> WidgetType.TREE
            ProtoWidgetType.WIDGET_TYPE_TABLE -> WidgetType.TABLE
            ProtoWidgetType.WIDGET_TYPE_TAB_ROW -> WidgetType.TAB_ROW
            ProtoWidgetType.WIDGET_TYPE_CODE_EDITOR -> WidgetType.CODE_EDITOR
            ProtoWidgetType.WIDGET_TYPE_TERMINAL -> WidgetType.TERMINAL
            ProtoWidgetType.WIDGET_TYPE_BROWSER -> WidgetType.BROWSER
            ProtoWidgetType.WIDGET_TYPE_CANVAS -> WidgetType.CANVAS
            else -> WidgetType.TEXT
        }
}
