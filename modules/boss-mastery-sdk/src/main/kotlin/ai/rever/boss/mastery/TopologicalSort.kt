package ai.rever.boss.mastery

/**
 * DAG topological sort into parallelizable execution levels (Kahn's algorithm).
 *
 * Each level in the result can be executed in parallel; all dependencies of nodes
 * in level N are satisfied by levels 0..N-1.
 */
object TopologicalSort {
    /**
     * Sort nodes topologically into parallelizable levels.
     *
     * @param nodes   All nodes in the graph
     * @param getId   Returns the unique ID of a node
     * @param getDeps Returns the list of dependency IDs that must complete before this node
     * @return A list of levels; each level is a list of nodes that can run in parallel
     * @throws IllegalArgumentException if a cycle is detected or a dependency ID is unknown
     */
    fun <T : Any> sort(
        nodes: List<T>,
        getId: (T) -> String,
        getDeps: (T) -> List<String>,
    ): List<List<T>> {
        if (nodes.isEmpty()) return emptyList()

        val nodeById = nodes.associateBy(getId)
        // in-degree: number of dependencies not yet satisfied
        val inDegree = mutableMapOf<String, Int>()
        // dependents[id] = list of node IDs that depend on id
        val dependents = mutableMapOf<String, MutableList<String>>()

        // Build graph
        for (node in nodes) {
            val id = getId(node)
            inDegree.getOrPut(id) { 0 }
            for (dep in getDeps(node)) {
                require(nodeById.containsKey(dep)) {
                    "Dependency '$dep' referenced by '$id' does not exist in the node list"
                }
                dependents.getOrPut(dep) { mutableListOf() }.add(id)
                inDegree[id] = (inDegree[id] ?: 0) + 1
            }
        }

        // Kahn's BFS: process one level at a time
        val levels = mutableListOf<List<T>>()
        var currentLevel =
            inDegree
                .filter { it.value == 0 }
                .keys
                .mapNotNull { nodeById[it] }

        val visited = mutableSetOf<String>()

        while (currentLevel.isNotEmpty()) {
            levels.add(currentLevel)
            val nextLevel = mutableListOf<T>()
            for (node in currentLevel) {
                val id = getId(node)
                visited.add(id)
                for (dependent in dependents[id] ?: emptyList()) {
                    inDegree[dependent] = (inDegree[dependent] ?: 0) - 1
                    if (inDegree[dependent] == 0) {
                        nodeById[dependent]?.let { nextLevel.add(it) }
                    }
                }
            }
            currentLevel = nextLevel
        }

        require(visited.size == nodes.size) {
            val cycle = nodes.map(getId).filter { it !in visited }
            "Cycle detected in mastery DAG involving nodes: $cycle"
        }

        return levels
    }
}
