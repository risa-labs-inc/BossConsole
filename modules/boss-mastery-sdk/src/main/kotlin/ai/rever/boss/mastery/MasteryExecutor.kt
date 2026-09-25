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
     * Persisted definitions are re-read at this seam as trusted data, so a structurally hostile
     * document — an oversized graph, a blank, duplicate, or INPUT-reserved node id, a dangling
     * edge endpoint, or a cycle — is refused with a [MasteryProgress.Failed] verdict before a
     * single capability invocation.
     *
     * Fan-in admission (see [admissionFor]): a node with no incoming edges is
     * unconditional. Otherwise, a node whose every incoming edge is blocked
     * is skipped, reported on the stream as [MasteryProgress.NodeSkipped]
     * and logged at WARN, which in turn blocks its downstream edges. When
     * only some incoming edges are blocked, a side-effecting node is vetoed
     * by any one of them, while a `pure` node (see [MasteryNode.pure]) is
     * admitted on any followed edge. A blocked edge never contributes its
     * source's mapped data to the target node. Blank or null conditions
     * keep the previous unconditional behaviour.
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
            val violation = StructuralChecks.structuralViolation(mastery)
            if (violation != null) {
                send(MasteryProgress.Failed(violation, mastery.id))
                return@channelFlow
            }
            val startTime = System.currentTimeMillis()
            send(MasteryProgress.Started(mastery.id, mastery.nodes.size))

            // Accumulates node outputs; "INPUT" is the virtual source node
            val nodeOutputs = mutableMapOf<String, Map<String, String>>("INPUT" to input)
            val outputBudget = AtomicLong()
            val slots = Semaphore(8)

            try {
                reserveOutput("INPUT", input, outputBudget)
                val levels = StructuralChecks.topoLevels(mastery)

                for (level in levels) {
                    // All nodes in a level are independent — execute in parallel
                    val snapshot = nodeOutputs.toMap()

                    // Fan-in admission (see admissionFor); skips are reported
                    // as NodeSkipped and logged at WARN so a guard that fires
                    // (or a malformed condition that fails closed) is
                    // visible, not silent.
                    val admitted = admit(level, mastery.edges, snapshot) { send(it) }
                    if (admitted.isEmpty()) continue

                    val levelResults: List<Pair<String, Map<String, String>>> =
                        coroutineScope {
                            admitted
                                .map { admission ->
                                    async {
                                        executeNode(admission, snapshot, outputBudget, slots) { progress ->
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

    /**
     * The pure structural checks at the load/execute seam. Level planning and re-validation
     * share one dependency view, so the two can never drift apart.
     */
    private object StructuralChecks {
        fun topoLevels(mastery: MasteryDefinition): List<List<MasteryNode>> =
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
        fun structuralViolation(mastery: MasteryDefinition): String? {
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
        fun walkViolation(mastery: MasteryDefinition): String? =
            try {
                topoLevels(mastery)
                null
            } catch (conflict: IllegalArgumentException) {
                conflict.message ?: "Definition is not a walkable DAG"
            }
    }

    private suspend fun executeNode(
        admission: Admission,
        nodeOutputs: Map<String, Map<String, String>>,
        outputBudget: AtomicLong,
        slots: Semaphore,
        emit: suspend (MasteryProgress) -> Unit,
    ): Pair<String, Map<String, String>> {
        val node = admission.node
        emit(
            MasteryProgress.NodeStarted(
                node.id,
                node.displayName.ifEmpty { "${node.pluginId}/${node.action}" },
            ),
        )

        val resolvedInput = resolveNodeInput(node, nodeOutputs, admission.blockedSources)
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
     *
     * A source whose incoming edges into [node] are all blocked (see
     * [Admission.blockedSources]) never contributes its mapped data: the
     * author guarded that path, so the guarded output must not reach the
     * node even when the node itself is admitted.
     */
    private fun resolveNodeInput(
        node: MasteryNode,
        nodeOutputs: Map<String, Map<String, String>>,
        blockedSources: Set<String>,
    ): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        resolved.putAll(node.staticConfig)

        for ((targetKey, source) in node.inputMapping) {
            val dotIdx = source.indexOf('.')
            if (dotIdx == -1) {
                resolved[targetKey] = source
                continue
            }
            resolveMappedInput(targetKey, source, node, nodeOutputs, blockedSources)
                ?.let { resolved[targetKey] = it }
        }

        return resolved
    }

    /**
     * Resolves one "SOURCE_NODE_ID.outputKey" mapping to its value, or null
     * when the source is withheld (all its incoming edges into [node] are
     * blocked) or has no output yet. The caller simply does not bind
     * [targetKey] then; the WARN log carries the reason either way.
     */
    private fun resolveMappedInput(
        targetKey: String,
        source: String,
        node: MasteryNode,
        nodeOutputs: Map<String, Map<String, String>>,
        blockedSources: Set<String>,
    ): String? {
        val sourceNodeId = source.substringBefore('.')
        if (sourceNodeId in blockedSources) {
            logger.warn(
                "Input '{}' of node '{}' withheld: every incoming edge from '{}' is blocked",
                targetKey,
                node.id,
                sourceNodeId,
            )
            return null
        }
        val sourceOutput =
            nodeOutputs[sourceNodeId] ?: run {
                logger.warn(
                    "Source node '{}' output not available when resolving input for node '{}'",
                    sourceNodeId,
                    node.id,
                )
                null
            }
        return sourceOutput?.get(source.substringAfter('.'))
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

    /**
     * The nodes of [level] that may execute now, in [level] order, each
     * paired with the sources whose data must not reach it (see
     * [admissionFor]). Each skipped node is reported through [emit] as
     * [MasteryProgress.NodeSkipped] carrying the same human-readable reason
     * written to the WARN log, so an execution watcher sees why a node did
     * not run instead of a silently missing NodeStarted. The reason is
     * capped at 2048 characters the way [MasteryProgress.NodeFailed.error]
     * is — a reason that embeds a failing condition is otherwise bounded
     * only by the definition size, and one is emitted per blocked target.
     */
    private suspend fun admit(
        level: List<MasteryNode>,
        edges: List<MasteryEdge>,
        nodeOutputs: Map<String, Map<String, String>>,
        emit: suspend (MasteryProgress) -> Unit,
    ): List<Admission> {
        val admitted = mutableListOf<Admission>()
        for (node in level) {
            val admission = admissionFor(node, edges, nodeOutputs)
            if (admission.skipReason == null) {
                admitted += admission
            } else {
                val reason = admission.skipReason.take(2048)
                logger.warn("Node '{}' skipped: {}", node.id, reason)
                emit(MasteryProgress.NodeSkipped(node.id, reason))
            }
        }
        return admitted
    }

    /**
     * Fan-in admission for one node: the node plus the sources whose mapped
     * data must not reach it, or a skip reason when it must not run.
     *
     * A node with no incoming edges is unconditional (pre-existing
     * behaviour). An incoming edge is blocked when its source produced no
     * output — the virtual INPUT node always has — or its condition,
     * evaluated against that output, does not hold; malformed conditions
     * fail closed ([MasteryEdgeCondition]). Because a skipped node never
     * records output, a guard also skips everything reachable from it
     * through its remaining edges.
     *
     * A node whose incoming edges are all blocked has no path in and is
     * skipped. When only some are blocked, admission depends on the node's
     * effect classification: the executor cannot inspect what a plugin
     * capability does, so every node is treated as destructive /
     * side-effecting unless it opts out with [MasteryNode.pure] — and one
     * blocked edge vetoes a side-effecting node even though another edge
     * is followed, since part of the data the DAG says it consumes cannot
     * reach it. Only `pure` nodes (the mastery-unlock pattern) keep
     * any-admit fan-in; their blocked sources' data is still withheld.
     */
    private fun admissionFor(
        node: MasteryNode,
        edges: List<MasteryEdge>,
        nodeOutputs: Map<String, Map<String, String>>,
    ): Admission {
        val incoming = edges.filter { it.toNode == node.id }
        // One verdict per edge occurrence: whether it may be followed given
        // the node outputs recorded so far. Verdicts are keyed by position in
        // a list, not by the MasteryEdge value — a data class, so keying a
        // map on it would collapse byte-identical duplicate edges into one
        // entry and let a node whose every incoming edge is blocked slip
        // past the all-blocked test below. A skipped source blocks its
        // outgoing edges — its output never existed, so the guard has
        // nothing to evaluate against.
        val verdicts =
            incoming.map { edge ->
                val sourceOutput = nodeOutputs[edge.fromNode]
                val verdict =
                    if (sourceOutput == null) {
                        MasteryEdgeCondition.Blocked(
                            "source node '${edge.fromNode}' produced no output (it was skipped)",
                        )
                    } else {
                        MasteryEdgeCondition.evaluate(edge.condition, sourceOutput)
                    }
                edge to verdict
            }
        val blocked = verdicts.map { it.second }.filterIsInstance<MasteryEdgeCondition.Blocked>()
        // Covers a node with no incoming edges as well: nothing is blocked,
        // nothing to evaluate, admitted with the full fan-in.
        if (blocked.isEmpty()) return Admission(node, emptySet(), null)

        // A source may contribute data when at least one of its edges into
        // this node is followed; otherwise its mapped output stays withheld.
        val followedSources =
            verdicts
                .filter { it.second == MasteryEdgeCondition.Followed }
                .map { it.first.fromNode }
                .toSet()
        val blockedSources = incoming.map { it.fromNode }.toSet() - followedSources
        val joined = blocked.joinToString("; ") { it.reason }

        return when {
            blocked.size == incoming.size -> Admission(node, blockedSources, joined)
            node.pure -> Admission(node, blockedSources, null)
            else -> Admission(node, blockedSources, "incoming edge blocked: $joined")
        }
    }

    /** One node's fan-in decision: whether it runs, and what data may reach it. */
    private class Admission(
        val node: MasteryNode,
        /** Sources whose every incoming edge into the node is blocked. */
        val blockedSources: Set<String>,
        /** Why the node may not run, or null when it may. */
        val skipReason: String?,
    )

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
