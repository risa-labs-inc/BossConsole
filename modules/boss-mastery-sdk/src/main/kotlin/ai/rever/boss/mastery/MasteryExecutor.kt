package ai.rever.boss.mastery

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
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
                    val levelResults: List<Pair<String, Map<String, String>>> =
                        coroutineScope {
                            level
                                .map { node ->
                                    async {
                                        slots.withPermit {
                                            executeNode(node, snapshot, outputBudget) { progress ->
                                                this@channelFlow.send(progress)
                                            }
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
        val output = invokeWithRetries(node, resolvedInput, emit)
        reserveOutput(node.id, output, outputBudget)
        emit(MasteryProgress.NodeCompleted(node.id, output, System.currentTimeMillis() - nodeStart))
        return node.id to output
    }

    private suspend fun invokeWithRetries(
        node: MasteryNode,
        resolvedInput: Map<String, String>,
        emit: suspend (MasteryProgress) -> Unit,
    ): Map<String, String> {
        var lastError: String? = null

        for (attempt in 0..node.maxRetries) {
            try {
                return withTimeout(node.timeoutMs) {
                    capabilityResolver.invoke(node.pluginId, node.action, resolvedInput)
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

    data class Completed(
        val output: Map<String, String>,
        val totalDurationMs: Long,
    ) : MasteryProgress()

    data class Failed(
        val error: String,
        val failedNodeId: String,
    ) : MasteryProgress()
}
