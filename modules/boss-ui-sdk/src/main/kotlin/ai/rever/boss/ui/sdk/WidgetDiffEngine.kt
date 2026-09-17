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

    /**
     * Patch a node without replacing its identity or children.
     *
     * [changedProperties] and [removedProperties] are deliberately separate. An empty string is a
     * valid widget value, so using it as a deletion sentinel makes a real clear operation
     * indistinguishable from removing the property. If a hand-built operation puts a key in both,
     * the changed value wins. The three-argument JVM constructor remains available for plugins
     * compiled against the original SDK shape.
     */
    class NodeUpdated(
        val nodeId: String,
        val changedProperties: Map<String, String>,
        val newModifier: WidgetModifier?,
        val removedProperties: Set<String>,
    ) : DiffOperation() {
        /** The constructor descriptor shipped by the original SDK. */
        constructor(
            nodeId: String,
            changedProperties: Map<String, String>,
            newModifier: WidgetModifier?,
        ) : this(nodeId, changedProperties, newModifier, emptySet())

        operator fun component1(): String = nodeId

        operator fun component2(): Map<String, String> = changedProperties

        operator fun component3(): WidgetModifier? = newModifier

        operator fun component4(): Set<String> = removedProperties

        /**
         * Retains the original copy descriptor, including its generated `copy$default` bridge.
         * A copy made through that API preserves this update's removals.
         */
        fun copy(
            nodeId: String = this.nodeId,
            changedProperties: Map<String, String> = this.changedProperties,
            newModifier: WidgetModifier? = this.newModifier,
        ): NodeUpdated = NodeUpdated(nodeId, changedProperties, newModifier, removedProperties)

        fun copyWithRemovedProperties(
            nodeId: String = this.nodeId,
            changedProperties: Map<String, String> = this.changedProperties,
            newModifier: WidgetModifier? = this.newModifier,
            removedProperties: Set<String> = this.removedProperties,
        ): NodeUpdated = NodeUpdated(nodeId, changedProperties, newModifier, removedProperties)

        override fun equals(other: Any?): Boolean =
            other is NodeUpdated &&
                nodeId == other.nodeId &&
                changedProperties == other.changedProperties &&
                newModifier == other.newModifier &&
                removedProperties == other.removedProperties

        override fun hashCode(): Int {
            var result = nodeId.hashCode()
            result = 31 * result + changedProperties.hashCode()
            result = 31 * result + (newModifier?.hashCode() ?: 0)
            result = 31 * result + removedProperties.hashCode()
            return result
        }

        override fun toString(): String =
            "NodeUpdated(" +
                "nodeId=$nodeId, " +
                "changedProperties=$changedProperties, " +
                "newModifier=$newModifier, " +
                "removedProperties=$removedProperties)"
    }

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
            val removedProperties = oldNode.properties.keys - newNode.properties.keys

            val modifierChanged = oldNode.modifier != newNode.modifier
            if (changedProps.isNotEmpty() || removedProperties.isNotEmpty() || modifierChanged) {
                ops.add(
                    DiffOperation.NodeUpdated(
                        id,
                        changedProps,
                        if (modifierChanged) newNode.modifier else null,
                        removedProperties,
                    ),
                )
            }
        }

        // Sibling reorders, last: they must apply after the parent-change moves above, because a
        // NodeMoved re-inserts at an absolute index in the parent's list.
        ops.addAll(reorderOps(old, new, oldParents, newParents))

        return ops
    }

    /**
     * Emit the moves that fix sibling order *within* a parent.
     *
     * `NodeMoved` used to be emitted only when a node changed parent, and common nodes were never
     * compared on `childIds` — so swapping two siblings produced **no operations at all** and
     * `apply(old, diff(old, new)) != new`. `apply` has always handled a same-parent move; nothing
     * generated one. (It was masked while builder ids were positional, since a reorder then looked
     * like a pile of property changes. Stable ids — now a documented requirement in the proto — are
     * exactly what makes it reachable.)
     *
     * Only children that exist in both trees *and* kept this parent participate: additions,
     * removals and cross-parent moves are already covered by their own ops, and their positions fall
     * out of applying those. When the surviving order differs, the whole surviving sequence is
     * re-emitted in its new order rather than a minimal move set — correct and independent of how a
     * receiver orders the ops, at the cost of up to `k` ops for a `k`-child reorder. A minimal
     * (longest-increasing-subsequence) set would be a pure optimization.
     */
    private fun reorderOps(
        old: WidgetTree,
        new: WidgetTree,
        oldParents: Map<String, String>,
        newParents: Map<String, String>,
    ): List<DiffOperation> =
        new.nodes.entries.flatMap { (parentId, newParent) ->
            // Parents absent from the old tree arrive whole; their children need no moves.
            val keptOldOrder =
                old.nodes[parentId]?.childIds?.filter { it.keepsParent(parentId, oldParents, newParents) }
            val keptNewOrder = newParent.childIds.filter { it.keepsParent(parentId, oldParents, newParents) }

            if (keptOldOrder == null || keptOldOrder == keptNewOrder) {
                emptyList()
            } else {
                keptNewOrder.map { childId ->
                    DiffOperation.NodeMoved(childId, parentId, newParent.childIds.indexOf(childId))
                }
            }
        }

    /** True when this child is linked to [parentId] in both the old and the new tree. */
    private fun String.keepsParent(
        parentId: String,
        oldParents: Map<String, String>,
        newParents: Map<String, String>,
    ): Boolean = oldParents[this] == parentId && newParents[this] == parentId

    fun apply(
        base: WidgetTree,
        operations: List<DiffOperation>,
    ): WidgetTree {
        val nodes = base.nodes.toMutableMap()
        // child id → parent id, maintained as ops mutate the links. Unlinking used to scan every node
        // for whoever listed the child (O(n) per remove/move, O(n²) for a list turnover) on the
        // per-update path.
        val parentOf = buildParentMap(base).toMutableMap()

        for (op in operations) {
            when (op) {
                is DiffOperation.NodeAdded -> {
                    nodes[op.node.id] = op.node
                    // The payload carries the node's own children, so they are linked from here on.
                    for (childId in op.node.childIds) parentOf[childId] = op.node.id
                    if (op.parentId.isNotEmpty()) {
                        val parent = nodes[op.parentId]
                        if (parent != null) {
                            nodes[op.parentId] = parent.copy(childIds = parent.childIds.withChild(op.node.id, op.index))
                            parentOf[op.node.id] = op.parentId
                        }
                    }
                }

                is DiffOperation.NodeRemoved -> {
                    nodes.remove(op.nodeId)
                    nodes.unlink(op.nodeId, parentOf)
                }

                is DiffOperation.NodeUpdated -> {
                    val node = nodes[op.nodeId] ?: continue
                    val newProps =
                        node.properties.toMutableMap().apply {
                            op.removedProperties.forEach(::remove)
                            // An explicit value wins over a contradictory removal in a hand-built or
                            // malformed wire operation. diff() itself always emits disjoint sets.
                            putAll(op.changedProperties)
                        }
                    nodes[op.nodeId] =
                        node.copy(
                            properties = newProps,
                            modifier = op.newModifier ?: node.modifier,
                        )
                }

                is DiffOperation.NodeMoved -> {
                    nodes.unlink(op.nodeId, parentOf)
                    val newParent = nodes[op.newParentId]
                    if (newParent != null) {
                        nodes[op.newParentId] =
                            newParent.copy(childIds = newParent.childIds.withChild(op.nodeId, op.newIndex))
                        parentOf[op.nodeId] = op.newParentId
                    }
                }
            }
        }

        return base.copy(nodes = nodes, version = base.version + 1)
    }

    /** Drop [nodeId] from whichever parent currently lists it, keeping [parentOf] in step. */
    private fun MutableMap<String, WidgetNode>.unlink(
        nodeId: String,
        parentOf: MutableMap<String, String>,
    ) {
        val parentId = parentOf.remove(nodeId) ?: return
        val parent = this[parentId] ?: return
        this[parentId] = parent.copy(childIds = parent.childIds - nodeId)
    }

    /**
     * Insert [childId] at [index], or leave the list untouched if it already links that child.
     *
     * A `NodeAdded` payload carries the added node's own `childIds`, so when a whole subtree is added
     * the parent arrives already linked to children that then get their own `NodeAdded` ops. Applying
     * those parent-first (map iteration order decides, so it happens in practice) linked each child
     * twice, and the renderer drew the subtree twice. Idempotent insertion makes op order irrelevant.
     */
    private fun List<String>.withChild(
        childId: String,
        index: Int,
    ): List<String> =
        if (contains(childId)) {
            this
        } else {
            toMutableList().apply { add(index.coerceIn(0, size), childId) }
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
