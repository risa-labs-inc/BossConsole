package ai.rever.boss.ui.sdk

sealed class DiffOperation {
    data class NodeAdded(
        val node: WidgetNode,
        val parentId: String,
        val index: Int,
    ) : DiffOperation()

    data class NodeRemoved(
        val nodeId: String,
    ) : DiffOperation()

    data class NodeUpdated(
        val nodeId: String,
        val changedProperties: Map<String, String>,
        val newModifier: WidgetModifier?,
    ) : DiffOperation()

    data class NodeMoved(
        val nodeId: String,
        val newParentId: String,
        val newIndex: Int,
    ) : DiffOperation()
}

object WidgetDiffEngine {
    fun diff(
        old: WidgetTree,
        new: WidgetTree,
    ): List<DiffOperation> {
        val ops = mutableListOf<DiffOperation>()

        val oldParents = buildParentMap(old)
        val newParents = buildParentMap(new)

        val oldIds = old.nodes.keys
        val newIds = new.nodes.keys

        // Removed nodes (emit for all descendants too so apply stays clean)
        val removed = oldIds - newIds
        for (id in removed) {
            ops.add(DiffOperation.NodeRemoved(id))
        }

        // Added nodes
        val added = newIds - oldIds
        for (id in added) {
            val node = new.nodes[id]!!
            val parentId = newParents[id] ?: ""
            val index =
                if (parentId.isNotEmpty()) {
                    new.nodes[parentId]?.childIds?.indexOf(id) ?: 0
                } else {
                    0
                }
            ops.add(DiffOperation.NodeAdded(node, parentId, index))
        }

        // Common nodes: check for moves and updates
        for (id in oldIds.intersect(newIds)) {
            val oldNode = old.nodes[id]!!
            val newNode = new.nodes[id]!!

            val oldParent = oldParents[id]
            val newParent = newParents[id]

            if (oldParent != newParent && newParent != null) {
                val newIndex = new.nodes[newParent]?.childIds?.indexOf(id) ?: 0
                ops.add(DiffOperation.NodeMoved(id, newParent, newIndex))
            }

            val changedProps = mutableMapOf<String, String>()
            for ((key, value) in newNode.properties) {
                if (oldNode.properties[key] != value) changedProps[key] = value
            }
            for (key in oldNode.properties.keys) {
                if (!newNode.properties.containsKey(key)) changedProps[key] = ""
            }

            val modifierChanged = oldNode.modifier != newNode.modifier
            if (changedProps.isNotEmpty() || modifierChanged) {
                ops.add(
                    DiffOperation.NodeUpdated(
                        id,
                        changedProps,
                        if (modifierChanged) newNode.modifier else null,
                    ),
                )
            }
        }

        return ops
    }

    fun apply(
        base: WidgetTree,
        operations: List<DiffOperation>,
    ): WidgetTree {
        val nodes = base.nodes.toMutableMap()

        for (op in operations) {
            when (op) {
                is DiffOperation.NodeAdded -> {
                    nodes[op.node.id] = op.node
                    if (op.parentId.isNotEmpty()) {
                        val parent = nodes[op.parentId]
                        if (parent != null) {
                            val children = parent.childIds.toMutableList()
                            children.add(op.index.coerceIn(0, children.size), op.node.id)
                            nodes[op.parentId] = parent.copy(childIds = children)
                        }
                    }
                }

                is DiffOperation.NodeRemoved -> {
                    val parentEntry = nodes.entries.firstOrNull { op.nodeId in it.value.childIds }
                    nodes.remove(op.nodeId)
                    if (parentEntry != null) {
                        val (parentId, parentNode) = parentEntry
                        nodes[parentId] = parentNode.copy(childIds = parentNode.childIds - op.nodeId)
                    }
                }

                is DiffOperation.NodeUpdated -> {
                    val node = nodes[op.nodeId] ?: continue
                    val newProps = node.properties.toMutableMap().apply { putAll(op.changedProperties) }
                    nodes[op.nodeId] =
                        node.copy(
                            properties = newProps,
                            modifier = op.newModifier ?: node.modifier,
                        )
                }

                is DiffOperation.NodeMoved -> {
                    // Remove from old parent
                    val oldParentEntry = nodes.entries.firstOrNull { op.nodeId in it.value.childIds }
                    if (oldParentEntry != null) {
                        val (oldParentId, oldParentNode) = oldParentEntry
                        nodes[oldParentId] =
                            oldParentNode.copy(
                                childIds = oldParentNode.childIds - op.nodeId,
                            )
                    }
                    // Add to new parent
                    val newParent = nodes[op.newParentId]
                    if (newParent != null) {
                        val children = newParent.childIds.toMutableList()
                        children.add(op.newIndex.coerceIn(0, children.size), op.nodeId)
                        nodes[op.newParentId] = newParent.copy(childIds = children)
                    }
                }
            }
        }

        return base.copy(nodes = nodes, version = base.version + 1)
    }

    private fun buildParentMap(tree: WidgetTree): Map<String, String> {
        val parentOf = mutableMapOf<String, String>()
        for ((nodeId, node) in tree.nodes) {
            for (childId in node.childIds) {
                parentOf[childId] = nodeId
            }
        }
        return parentOf
    }
}
