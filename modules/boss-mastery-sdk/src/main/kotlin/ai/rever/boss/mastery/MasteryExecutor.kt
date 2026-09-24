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
     * Persisted definitions are re-read at this seam as trusted data, so a structurally hostile
     * document — an oversized graph, a blank, duplicate, or INPUT-reserved node id, a dangling
     * edge endpoint, or a cycle — is refused with a [MasteryProgress.Failed] verdict before a
     * single capability invocation.
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
            // Load-time re-validation: persisted definitions are re-read here as trusted data,
            // so a hostile document is refused before a single capability invocation.
            val violation = structuralViolation(mastery)
            if (violation != null) {
                send(MasteryProgress.Failed(violation, mastery.id, 0L))
                return@channelFlow
            }
            val startTime = System.currentTimeMillis()
            send(MasteryProgress.Started(mastery.id, mastery.name, mastery.nodes.size))

            // Accumulates node outputs; "INPUT" is the virtual source node
            val nodeOutputs = mutableMapOf<String, Map<String, String>>("INPUT" to input)
            val outputBudget = AtomicLong()
            val slots = Semaphore(8)

            try {
                reserveOutput("INPUT", input, outputBudget)
                val levels = topoLevels(mastery)

                for (level in levels) {
                    // All nodes in a level are independent — execute in parallel
                    val snapshot = nodeOutputs.toMap()
                    val levelResults: List<Pair<String, Map<String, String>>> =
                        coroutineScope {
                            level
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
                send(
                    MasteryProgress.Completed(
                        finalOutput,
                        System.currentTimeMillis() - startTime,
                        nodeOutputs.size - 1,
                    ),
                )
            } catch (e: NodeExecutionException) {
                send(
                    MasteryProgress.Failed(
                        e.message ?: "Node execution failed",
                        e.nodeId,
                        System.currentTimeMillis() - startTime,
                    ),
                )
            }
        }

    /**
     * Sort nodes into parallelizable levels, sharing one dependency view between pre-flight
     * validation and execution so the two can never drift apart.
     */
    private fun topoLevels(mastery: MasteryDefinition): List<List<MasteryNode>> =
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

    /**
     * Structural re-validation at the load/execute seam. Persisted definitions are re-read as
     * trusted data, so the executor refuses a hostile-but-schema-valid document — an oversized
     * graph, a blank, duplicate, or INPUT-reserved node id, a dangling edge endpoint, or a
     * cycle — before emitting [MasteryProgress.Started]. Returns the refusal reason handed to
     * [MasteryProgress.Failed], or null when the DAG is walkable.
     */
    private fun structuralViolation(mastery: MasteryDefinition): String? {
        val nodeIds = mutableSetOf<String>()
        var duplicateId: String? = null
        for (node in mastery.nodes) {
            if (!nodeIds.add(node.id)) duplicateId = node.id
        }
        val danglingSource =
            mastery.edges
                .firstOrNull { edge ->
                    edge.fromNode != INPUT_NODE_ID && edge.fromNode !in nodeIds
                }?.fromNode
        val danglingTarget =
            mastery.edges.firstOrNull { edge -> edge.toNode !in nodeIds }?.toNode
        return when {
            mastery.nodes.size > MAX_NODES -> {
                "Definition exceeds the runtime budget of $MAX_NODES nodes (${mastery.nodes.size})"
            }

            mastery.edges.size > MAX_EDGES -> {
                "Definition exceeds the runtime budget of $MAX_EDGES edges (${mastery.edges.size})"
            }

            mastery.nodes.any { it.id.isBlank() || it.id == INPUT_NODE_ID } -> {
                "Node id is blank or claims the reserved '$INPUT_NODE_ID' id of the caller's input"
            }

            duplicateId != null -> {
                "Duplicate node id '$duplicateId'"
            }

            danglingSource != null -> {
                "Edge source '$danglingSource' does not match any node"
            }

            danglingTarget != null -> {
                "Edge target '$danglingTarget' does not match any node"
            }

            else -> {
                walkViolation(mastery)
            }
        }
    }

    /**
     * The final structural check: the definition must be a walkable DAG. [TopologicalSort]
     * refuses cycles with an [IllegalArgumentException]; its message becomes the refusal
     * reason handed to [MasteryProgress.Failed].
     */
    private fun walkViolation(mastery: MasteryDefinition): String? =
        try {
            topoLevels(mastery)
            null
        } catch (conflict: IllegalArgumentException) {
            conflict.message ?: "Definition is not a walkable DAG"
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
                node.pluginId,
                node.action,
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
                emit(
                    MasteryProgress.NodeFailed(
                        node.id,
                        e.message?.take(2048) ?: "Unknown error",
                        willRetry,
                        attempt + 1,
                    ),
                )
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

    private companion object {
        /** Runtime budget, mirroring the persistence-side definition caps. */
        const val MAX_NODES = 128
        const val MAX_EDGES = 512

        /** Reserved id of the virtual source node that carries the caller's input. */
        const val INPUT_NODE_ID = "INPUT"
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
        val masteryName: String,
        val totalNodes: Int,
    ) : MasteryProgress()

    data class NodeStarted(
        val nodeId: String,
        val displayName: String,
        val pluginId: String,
        val action: String,
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
        /**
         * 1-based ordinal of the failed attempt, ranging 1..maxRetries+1: a
         * maxRetries = 5 node can emit 6. The denominator (maxRetries + 1 total
         * attempts) is not carried on the wire, so clients that want to render
         * "attempt 2 of 6" need maxRetries from the definition.
         */
        val retryAttempt: Int,
    ) : MasteryProgress()

    data class NodeSkipped(
        val nodeId: String,
        val reason: String,
    ) : MasteryProgress()

    data class Completed(
        val output: Map<String, String>,
        val totalDurationMs: Long,
        val nodesExecuted: Int,
    ) : MasteryProgress()

    data class Failed(
        val error: String,
        val failedNodeId: String,
        val totalDurationMs: Long,
    ) : MasteryProgress()
}
