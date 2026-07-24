package ai.rever.boss.process

import org.slf4j.LoggerFactory

/**
 * Dependency graph for process startup ordering.
 *
 * Computes topological sort to determine which processes can start in parallel
 * and which must wait for dependencies.
 *
 * Example:
 * ```
 * auth-service (no deps) ─────┐
 * settings-service (no deps) ──┤
 *                               ├──> workspace-service (depends: auth, settings)
 *                               └──> plugin processes (depends: auth, workspace)
 * ```
 */
class DependencyGraph {
    private val logger = LoggerFactory.getLogger(DependencyGraph::class.java)
    private val nodes = mutableMapOf<String, MutableSet<String>>()

    /**
     * Add a process with its dependencies.
     */
    fun addProcess(
        id: String,
        dependencies: List<String> = emptyList(),
    ) {
        nodes.getOrPut(id) { mutableSetOf() }.addAll(dependencies)
        // Ensure dependency nodes exist in the graph
        dependencies.forEach { dep ->
            nodes.getOrPut(dep) { mutableSetOf() }
        }
    }

    /**
     * Remove a process from the graph.
     */
    fun removeProcess(id: String) {
        nodes.remove(id)
        // Remove as dependency from other nodes
        nodes.values.forEach { it.remove(id) }
    }

    /**
     * Check if a process can start (all dependencies are satisfied).
     */
    fun canStart(
        id: String,
        runningProcesses: Set<String>,
    ): Boolean {
        val deps = nodes[id] ?: return true
        return deps.all { it in runningProcesses }
    }

    /**
     * Compute startup order as levels of parallelizable starts.
     *
     * Returns a list of levels. Each level contains process IDs that can start in parallel.
     * Processes in level N+1 depend on processes in levels 0..N.
     *
     * @throws IllegalStateException if a circular dependency is detected
     */
    fun getStartupOrder(): List<List<String>> {
        val inDegree = mutableMapOf<String, Int>()
        val adjList = mutableMapOf<String, MutableList<String>>()

        // Initialize
        nodes.keys.forEach { id ->
            inDegree[id] = 0
            adjList[id] = mutableListOf()
        }

        // Build adjacency list (dependency -> dependent)
        nodes.forEach { (id, deps) ->
            deps.forEach { dep ->
                adjList.getOrPut(dep) { mutableListOf() }.add(id)
                inDegree[id] = (inDegree[id] ?: 0) + 1
            }
        }

        val levels = mutableListOf<List<String>>()
        val remaining = inDegree.toMutableMap()

        while (remaining.isNotEmpty()) {
            // Find all nodes with zero in-degree (can start now)
            val level = remaining.filter { it.value == 0 }.keys.toList()

            if (level.isEmpty()) {
                val cycle = remaining.keys.joinToString(", ")
                throw IllegalStateException("Circular dependency detected among: $cycle")
            }

            levels.add(level)
            logger.debug("Startup level {}: {}", levels.size - 1, level)

            // Remove these nodes and update in-degrees
            level.forEach { id ->
                remaining.remove(id)
                adjList[id]?.forEach { dependent ->
                    remaining[dependent] = (remaining[dependent] ?: 1) - 1
                }
            }
        }

        return levels
    }

    /**
     * Get all process IDs that depend on the given process (directly or transitively).
     */
    fun getDependents(id: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        // Find direct dependents
        nodes.forEach { (processId, deps) ->
            if (id in deps) {
                queue.add(processId)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (result.add(current)) {
                nodes.forEach { (processId, deps) ->
                    if (current in deps && processId !in result) {
                        queue.add(processId)
                    }
                }
            }
        }

        return result
    }

    val size: Int get() = nodes.size
}
