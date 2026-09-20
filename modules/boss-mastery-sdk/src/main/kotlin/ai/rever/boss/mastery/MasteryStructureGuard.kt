package ai.rever.boss.mastery

/**
 * Fail-closed structural gate for the mastery runtime walk.
 *
 * Whatever a persistence seam accepted when a mastery document was saved
 * (CreateMastery today), the executor refuses to *run* a document whose
 * shape is hostile. Over-budget graphs, blank, duplicate or reserved ("INPUT")
 * node ids, dangling edge endpoints and cycles all fail with one precise
 * reason before a single capability is invoked.
 *
 * This is the runtime's own invariant, independent of save-time validation:
 * a document that was valid per schema when persisted, but hostile in shape,
 * must never reach the walk. Without this gate a cyclic document kills the
 * progress stream with an uncaught exception, a node claiming the reserved
 * "INPUT" namespace silently overwrites the caller's input, and a dangling
 * target edge is ignored outright.
 */
object MasteryStructureGuard {
    /** Mirrors the persistence seam's node budget so load re-checks what save capped. */
    private const val MAX_NODES = 128

    /** Mirrors the persistence seam's edge budget so load re-checks what save capped. */
    private const val MAX_EDGES = 512

    /** Virtual source namespace; no real node may ever claim it. */
    private const val INPUT_NODE_ID = "INPUT"

    /**
     * Returns the reason this definition must not execute, or null when its
     * shape is safe to walk.
     */
    fun executionRefusal(mastery: MasteryDefinition): String? =
        budgetRefusal(mastery)
            ?: identityRefusal(mastery)
            ?: endpointRefusal(mastery)
            ?: cycleRefusal(mastery)

    private fun budgetRefusal(mastery: MasteryDefinition): String? =
        if (mastery.nodes.size > MAX_NODES) {
            "Mastery exceeds the runtime node budget: ${mastery.nodes.size} > $MAX_NODES nodes"
        } else if (mastery.edges.size > MAX_EDGES) {
            "Mastery exceeds the runtime edge budget: ${mastery.edges.size} > $MAX_EDGES edges"
        } else {
            null
        }

    private fun identityRefusal(mastery: MasteryDefinition): String? {
        val nodeIds = mastery.nodes.map { it.id }
        return if (nodeIds.any { it.isBlank() }) {
            "Mastery contains a blank node id"
        } else if (INPUT_NODE_ID in nodeIds) {
            "Mastery node id \"$INPUT_NODE_ID\" is reserved for the execution input namespace"
        } else {
            duplicateRefusal(nodeIds)
        }
    }

    private fun duplicateRefusal(nodeIds: List<String>): String? {
        val duplicates =
            nodeIds
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        return if (duplicates.isNotEmpty()) {
            "Duplicate mastery node ids: ${duplicates.joinToString()}"
        } else {
            null
        }
    }

    private fun endpointRefusal(mastery: MasteryDefinition): String? {
        val nodeIds = mastery.nodes.map { it.id }.toSet()
        val unknownTargets =
            mastery.edges
                .map { it.toNode }
                .filter { it !in nodeIds }
                .distinct()
        return if (unknownTargets.isNotEmpty()) {
            "Mastery edges reference unknown target node ids: ${unknownTargets.joinToString()}"
        } else {
            unknownSourceRefusal(mastery, nodeIds)
        }
    }

    private fun unknownSourceRefusal(
        mastery: MasteryDefinition,
        nodeIds: Set<String>,
    ): String? {
        val unknownSources =
            mastery.edges
                .map { it.fromNode }
                .filter { it != INPUT_NODE_ID && it !in nodeIds }
                .distinct()
        return if (unknownSources.isNotEmpty()) {
            "Mastery edges reference unknown source node ids: ${unknownSources.joinToString()}"
        } else {
            null
        }
    }

    /**
     * Detects cycles by running the same leveled walk the executor will run,
     * so the refusal reason matches what the walk itself would have found.
     */
    private fun cycleRefusal(mastery: MasteryDefinition): String? =
        try {
            TopologicalSort.sort(
                nodes = mastery.nodes,
                getId = { it.id },
                getDeps = { node ->
                    mastery.edges
                        .filter { it.toNode == node.id }
                        .map { it.fromNode }
                        .filter { it != INPUT_NODE_ID }
                },
            )
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Mastery definition contains a cycle"
        }
}
