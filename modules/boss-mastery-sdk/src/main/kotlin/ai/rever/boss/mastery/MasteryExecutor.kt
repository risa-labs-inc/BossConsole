package ai.rever.boss.mastery

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * DAG execution engine for mastery workflows.
 *
 * Executes mastery nodes in topological order with:
 * - Parallel execution within each level
 * - Data passing between nodes via [MasteryEdge] and [MasteryNode.inputMapping]
 * - Conditional edges: an edge is followed only when its [MasteryEdge.condition]
 *   holds against the source node's output map (see [MasteryEdgeCondition])
 * - Per-node retry logic with linear backoff
 * - Real-time progress events emitted via [Flow]
 */
class MasteryExecutor(
    private val capabilityResolver: CapabilityResolver,
) {
    private val logger = LoggerFactory.getLogger(MasteryExecutor::class.java)

    /**
     * Execute a mastery definition, streaming progress events.
     *
     * A node joins its level only when at least one incoming edge is
     * followed — its source produced output and its [MasteryEdge.condition]
     * (if any) evaluated true against that output. Nodes whose incoming
     * edges are all blocked are skipped, reported on the stream as
     * [MasteryProgress.NodeSkipped] and logged at WARN, which in turn
     * blocks their downstream edges; nodes without incoming edges and blank
     * or null conditions keep the previous unconditional behaviour.
     *
     * @param mastery The mastery DAG to execute
     * @param input   Initial key-value input (available to nodes as "INPUT.key")
     * @return [Flow] of [MasteryProgress] events emitted in real time
     */
    fun execute(
        mastery: MasteryDefinition,
        input: Map<String, String>,
    ): Flow<MasteryProgress> =
        channelFlow {
            val startTime = System.currentTimeMillis()
            send(MasteryProgress.Started(mastery.id, mastery.nodes.size))

            // Accumulates node outputs; "INPUT" is the virtual source node
            val nodeOutputs = mutableMapOf<String, Map<String, String>>("INPUT" to input)
            val outputBudget = AtomicLong()
            val slots = Semaphore(8)

            try {
                reserveOutput("INPUT", input, outputBudget)
                val levels =
                    TopologicalSort.sort(
                        nodes = mastery.nodes,
                        getId = { it.id },
                        getDeps = { node ->
                            mastery.edges
                                .filter { it.toNode == node.id }
                                .map { it.fromNode }
                                .filter { it != "INPUT" }
                        },
                    )

                for (level in levels) {
                    // All nodes in a level are independent — execute in parallel
                    val snapshot = nodeOutputs.toMap()

                    // A node joins its level only when at least one incoming edge
                    // is followed; skips are reported as NodeSkipped and logged at
                    // WARN so a guard that fires (or a malformed condition that
                    // fails closed) is visible, not silent.
                    val admitted = admit(level, mastery.edges, snapshot) { send(it) }
                    if (admitted.isEmpty()) continue

                    val levelResults: List<Pair<String, Map<String, String>>> =
                        coroutineScope {
                            admitted
                                .map { node ->
                                    async {
                                        executeNode(node, snapshot, outputBudget, slots) { progress ->
                                            this@channelFlow.send(progress)
                                        }
                                    }
                                }.awaitAll()
                        }
                    levelResults.forEach { (nodeId, output) ->
                        nodeOutputs[nodeId] = output
                    }
                }

                val finalOutput = collectFinalOutput(mastery, nodeOutputs)
                send(MasteryProgress.Completed(finalOutput, System.currentTimeMillis() - startTime))
            } catch (e: NodeExecutionException) {
                send(MasteryProgress.Failed(e.message ?: "Node execution failed", e.nodeId))
            }
        }

    private suspend fun executeNode(
        node: MasteryNode,
        nodeOutputs: Map<String, Map<String, String>>,
        outputBudget: AtomicLong,
        slots: Semaphore,
        emit: suspend (MasteryProgress) -> Unit,
    ): Pair<String, Map<String, String>> {
        emit(
            MasteryProgress.NodeStarted(
                node.id,
                node.displayName.ifEmpty { "${node.pluginId}/${node.action}" },
            ),
        )

        val resolvedInput = resolveNodeInput(node, nodeOutputs)
        val nodeStart = System.currentTimeMillis()
        val output = invokeWithRetries(node, resolvedInput, slots, emit)
        reserveOutput(node.id, output, outputBudget)
        emit(MasteryProgress.NodeCompleted(node.id, output, System.currentTimeMillis() - nodeStart))
        return node.id to output
    }

    private suspend fun invokeWithRetries(
        node: MasteryNode,
        resolvedInput: Map<String, String>,
        slots: Semaphore,
        emit: suspend (MasteryProgress) -> Unit,
    ): Map<String, String> {
        var lastError: String? = null

        for (attempt in 0..node.maxRetries) {
            try {
                return slots.withPermit {
                    // Queueing and retry backoff do not consume the invocation deadline or a permit.
                    invokeAttempt(node, resolvedInput)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message?.take(2048)
                val willRetry = attempt < node.maxRetries
                emit(MasteryProgress.NodeFailed(node.id, e.message?.take(2048) ?: "Unknown error", willRetry))
                logger.warn(
                    "Node {} attempt {}/{} failed: {}",
                    node.id,
                    attempt + 1,
                    node.maxRetries + 1,
                    e.message?.take(2048),
                )
                if (willRetry) delay(1_000L * (attempt + 1))
            }
        }

        throw NodeExecutionException(node.id, lastError ?: "Max retries exceeded")
    }

    private suspend fun invokeAttempt(
        node: MasteryNode,
        resolvedInput: Map<String, String>,
    ): Map<String, String> =
        withTimeoutOrNull(node.timeoutMs) {
            capabilityResolver.invoke(node.pluginId, node.action, resolvedInput)
        } ?: throw NodeExecutionException(node.id, "Node timed out after ${node.timeoutMs} ms")

    /** Bound map overhead and UTF-16 strings before buffering progress or retaining a node result. */
    private fun reserveOutput(
        nodeId: String,
        output: Map<String, String>,
        budget: AtomicLong,
    ) {
        val characters =
            if (output.size <= 1024) {
                output.entries.sumOf { (key, value) -> key.length.toLong() + value.length }
            } else {
                0
            }
        val rejection =
            when {
                output.size > 1024 -> "Node output exceeds 1024 entries"
                characters > 262_144 -> "Node output exceeds 256 Ki characters"
                budget.addAndGet(characters) > 2_097_152 -> "Execution output exceeds 2 Mi characters"
                else -> null
            }
        if (rejection != null) throw NodeExecutionException(nodeId, rejection)
    }

    /**
     * Build the resolved input map for a node by combining static config and edge mappings.
     * Edge source format: "SOURCE_NODE_ID.outputKey" or "INPUT.key"
     */
    private fun resolveNodeInput(
        node: MasteryNode,
        nodeOutputs: Map<String, Map<String, String>>,
    ): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        resolved.putAll(node.staticConfig)

        for ((targetKey, source) in node.inputMapping) {
            val dotIdx = source.indexOf('.')
            if (dotIdx == -1) {
                resolved[targetKey] = source
                continue
            }
            val sourceNodeId = source.substring(0, dotIdx)
            val sourceKey = source.substring(dotIdx + 1)
            val sourceOutput = nodeOutputs[sourceNodeId]
            if (sourceOutput != null) {
                sourceOutput[sourceKey]?.let { resolved[targetKey] = it }
            } else {
                logger.warn(
                    "Source node '{}' output not available when resolving input for node '{}'",
                    sourceNodeId,
                    node.id,
                )
            }
        }

        return resolved
    }

    /** Merge the outputs of all terminal nodes (nodes with no outgoing edges). */
    private fun collectFinalOutput(
        mastery: MasteryDefinition,
        nodeOutputs: Map<String, Map<String, String>>,
    ): Map<String, String> {
        val nodesWithOutgoingEdges = mastery.edges.map { it.fromNode }.toSet()
        val terminalNodes = mastery.nodes.filter { it.id !in nodesWithOutgoingEdges }
        return terminalNodes
            .flatMap { node ->
                nodeOutputs[node.id]?.entries ?: emptySet()
            }.associate { it.key to it.value }
    }

    /**
     * The nodes of [level] that may execute now, in [level] order; a node is
     * admitted only when at least one incoming edge is followed (see
     * [skipReason]). Each skipped node is reported through [emit] as
     * [MasteryProgress.NodeSkipped] carrying the same human-readable reason
     * written to the WARN log, so an execution watcher sees why a node did
     * not run instead of a silently missing NodeStarted.
     */
    private suspend fun admit(
        level: List<MasteryNode>,
        edges: List<MasteryEdge>,
        nodeOutputs: Map<String, Map<String, String>>,
        emit: suspend (MasteryProgress) -> Unit,
    ): List<MasteryNode> {
        val admitted = mutableListOf<MasteryNode>()
        for (node in level) {
            val reason = skipReason(node, edges, nodeOutputs)
            if (reason == null) {
                admitted += node
            } else {
                logger.warn("Node '{}' skipped: {}", node.id, reason)
                emit(MasteryProgress.NodeSkipped(node.id, reason))
            }
        }
        return admitted
    }

    /**
     * Why [node] may not join the current level, or null when it may.
     *
     * A node with no incoming edges is unconditional (pre-existing behaviour).
     * Otherwise it runs only when at least one incoming edge is followed: the
     * edge's source produced output — the virtual INPUT node always has — and
     * its condition, evaluated against that output, holds. Because a skipped
     * node never records output, a guard also skips everything reachable from
     * it through its remaining edges. Malformed conditions fail closed
     * ([MasteryEdgeCondition]).
     */
    private fun skipReason(
        node: MasteryNode,
        edges: List<MasteryEdge>,
        nodeOutputs: Map<String, Map<String, String>>,
    ): String? {
        val verdicts = edges.filter { it.toNode == node.id }.map { edgeVerdict(it, nodeOutputs) }
        val blocked = verdicts.filterIsInstance<MasteryEdgeCondition.Blocked>()
        return when {
            verdicts.isEmpty() -> null
            blocked.size < verdicts.size -> null
            else -> blocked.joinToString("; ") { it.reason }
        }
    }

    /** Whether [edge] may be followed given the node outputs recorded so far. */
    private fun edgeVerdict(
        edge: MasteryEdge,
        nodeOutputs: Map<String, Map<String, String>>,
    ): MasteryEdgeCondition.Result =
        when (val sourceOutput = nodeOutputs[edge.fromNode]) {
            null -> {
                MasteryEdgeCondition.Blocked(
                    "source node '${edge.fromNode}' produced no output (it was skipped)",
                )
            }

            else -> {
                MasteryEdgeCondition.evaluate(edge.condition, sourceOutput)
            }
        }

    private class NodeExecutionException(
        val nodeId: String,
        message: String,
    ) : Exception(message)
}

// ---- Progress event hierarchy ----

sealed class MasteryProgress {
    data class Started(
        val masteryId: String,
        val totalNodes: Int,
    ) : MasteryProgress()

    data class NodeStarted(
        val nodeId: String,
        val displayName: String,
    ) : MasteryProgress()

    data class NodeCompleted(
        val nodeId: String,
        val output: Map<String, String>,
        val durationMs: Long,
    ) : MasteryProgress()

    data class NodeFailed(
        val nodeId: String,
        val error: String,
        val willRetry: Boolean,
    ) : MasteryProgress()

    /**
     * A node did not execute: every incoming edge was blocked — a guard
     * fired or a malformed condition failed closed. [reason] is the same
     * human-readable string the executor logs at WARN.
     */
    data class NodeSkipped(
        val nodeId: String,
        val reason: String,
    ) : MasteryProgress()

    data class Completed(
        val output: Map<String, String>,
        val totalDurationMs: Long,
    ) : MasteryProgress()

    data class Failed(
        val error: String,
        val failedNodeId: String,
    ) : MasteryProgress()
}
