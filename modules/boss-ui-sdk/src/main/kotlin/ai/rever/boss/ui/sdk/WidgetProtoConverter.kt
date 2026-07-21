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

object WidgetProtoConverter {

    fun WidgetTree.toProto(): ProtoWidgetTree =
        ProtoWidgetTree.newBuilder()
            .setRootId(rootId)
            .addAllNodes(nodes.values.map { it.toProto() })
            .setVersion(version)
            .build()

    fun ProtoWidgetTree.toKotlin(): WidgetTree {
        val nodeMap = nodesList.associate { protoNode ->
            protoNode.id to WidgetNode(
                id = protoNode.id,
                type = protoNode.type.toKotlin(),
                properties = protoNode.propertiesMap.toMap(),
                childIds = protoNode.childIdsList.toList(),
                modifier = protoNode.modifier.toKotlin(),
            )
        }
        return WidgetTree(rootId, nodeMap, version)
    }

    fun List<DiffOperation>.toProtoDiff(baseVersion: Long, newVersion: Long): ProtoWidgetDiff {
        val builder = ProtoWidgetDiff.newBuilder()
            .setBaseVersion(baseVersion)
            .setNewVersion(newVersion)

        for (op in this) {
            val protoDiffOp = when (op) {
                is DiffOperation.NodeAdded -> {
                    val added = ProtoNodeAdded.newBuilder()
                        .setNode(op.node.toProto())
                        .setParentId(op.parentId)
                        .setIndex(op.index)
                        .build()
                    ProtoDiffOp.newBuilder().setAdded(added).build()
                }
                is DiffOperation.NodeRemoved -> {
                    val removed = ProtoNodeRemoved.newBuilder()
                        .setNodeId(op.nodeId)
                        .build()
                    ProtoDiffOp.newBuilder().setRemoved(removed).build()
                }
                is DiffOperation.NodeUpdated -> {
                    val updated = ProtoNodeUpdated.newBuilder()
                        .setNodeId(op.nodeId)
                        .putAllChangedProperties(op.changedProperties)
                        .apply { op.newModifier?.let { setModifier(it.toProto()) } }
                        .build()
                    ProtoDiffOp.newBuilder().setUpdated(updated).build()
                }
                is DiffOperation.NodeMoved -> {
                    val moved = ProtoNodeMoved.newBuilder()
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
        ProtoWidgetNode.newBuilder()
            .setId(id)
            .setType(type.toProto())
            .putAllProperties(properties)
            .addAllChildIds(childIds)
            .setModifier(modifier.toProto())
            .build()

    private fun WidgetModifier.toProto(): ProtoWidgetModifier =
        ProtoWidgetModifier.newBuilder()
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

    private fun ProtoWidgetModifier.toKotlin(): WidgetModifier = WidgetModifier(
        width = width,
        height = height,
        paddingStart = paddingStart,
        paddingTop = paddingTop,
        paddingEnd = paddingEnd,
        paddingBottom = paddingBottom,
        backgroundColor = backgroundColor,
        alpha = alpha,
        clickable = clickable,
        clickEventId = clickEventId,
    )

    private fun WidgetType.toProto(): ProtoWidgetType = when (this) {
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

    private fun ProtoWidgetType.toKotlin(): WidgetType = when (this) {
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
