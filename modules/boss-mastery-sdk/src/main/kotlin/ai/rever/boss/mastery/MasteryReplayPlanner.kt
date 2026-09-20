package ai.rever.boss.mastery

/**
 * Computes which nodes must be rerun when replaying from a given start node.
 */
class MasteryReplayPlanner {
    /**
     * Return the list of nodes (in topological order) that are descendants of startNodeId,
     * including startNodeId itself.
     */
    fun nodesToReplay(mastery: MasteryDefinition, startNodeId: String): List<MasteryNode> {
        // Build adjacency map
        val adj = mastery.edges.groupBy({ it.fromNode }, { it.toNode })

        // DFS/BFS to collect descendants
        val toVisit = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        toVisit.add(startNodeId)
        visited.add(startNodeId)

        while (toVisit.isNotEmpty()) {
            val cur = toVisit.removeFirst()
            val neighbors = adj[cur] ?: emptyList()
            for (n in neighbors) {
                if (n !in visited) {
                    visited.add(n)
                    toVisit.add(n)
                }
            }
        }

        // Produce topological ordering using existing TopologicalSort and filter
        val levels = TopologicalSort.sort(
            nodes = mastery.nodes,
            getId = { it.id },
            getDeps = { node ->
                mastery.edges.filter { it.toNode == node.id }.map { it.fromNode }.filter { it != "INPUT" }
            },
        )

        return levels.flatten().filter { it.id in visited }
    }
}
