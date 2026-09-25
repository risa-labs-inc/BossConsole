package ai.rever.boss.mastery

import kotlinx.serialization.Serializable

/**
 * A Mastery is a directed acyclic graph of plugin capability invocations
 * that automates a multi-step task (mirrors mastery.proto MasteryDefinition).
 */
@Serializable
data class MasteryDefinition(
    val id: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}",
    val outputSchemaJson: String = "{}",
    val nodes: List<MasteryNode>,
    val edges: List<MasteryEdge>,
    val author: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class MasteryNode(
    val id: String,
    val pluginId: String,
    val action: String,
    /** Maps this node's input keys to sources: "SOURCE_NODE_ID.outputKey" or "INPUT.key". */
    val inputMapping: Map<String, String> = emptyMap(),
    val staticConfig: Map<String, String> = emptyMap(),
    val isAgentCall: Boolean = false,
    val agentPrompt: String? = null,
    val maxRetries: Int = 0,
    val timeoutMs: Long = 300_000,
    val displayName: String = "",
    /**
     * Declares that this node's invocation has no external side effects —
     * its only observable effect is the output map it returns. This is the
     * mastery-unlock pattern: a node that exists to record or authorize
     * downstream progress rather than to act on external state.
     *
     * [MasteryExecutor] cannot inspect what a plugin capability does, so it
     * treats every node as side-effecting unless this flag opts out. A
     * side-effecting node is vetoed whenever *any* incoming edge is
     * blocked — even if another edge is followed — because part of the
     * data the DAG says it consumes cannot reach it; a `pure` node keeps
     * any-admit fan-in and still runs when at least one incoming edge is
     * followed, though a blocked edge's mapped data is withheld from its
     * input all the same.
     */
    val pure: Boolean = false,
)

@Serializable
data class MasteryEdge(
    val fromNode: String,
    val toNode: String,
    val outputKey: String,
    val inputKey: String,
    /**
     * Optional expression that must be true for this edge to be followed.
     *
     * Evaluated by [MasteryExecutor] against the source node's output map (the
     * mastery input for the virtual `INPUT` node) with the bounded grammar of
     * [MasteryEdgeCondition]: `true`, `false`, a bare `key` (truthiness), or
     * `key == literal` / `key != literal`. A condition key is a **bare** key
     * of the source node's output map — not the `SOURCE_NODE.outputKey` form
     * [MasteryNode.inputMapping] uses on the same edge: a dotted key is
     * malformed, is rejected when the definition is created, and fails
     * closed if it ever reaches the evaluator. A bare-key condition reading
     * an output key literally named `true` or `false` is interpreted as the
     * boolean literal; the `key == "true"` comparison form is the
     * unambiguous way to compare against those strings. Null or blank
     * conditions are unconditional. A malformed expression — or one that
     * cannot be established, such as a comparison against a key with no
     * output value — fails closed: the edge is not followed, its source's
     * mapped data never reaches the dependent node, and the dependent node
     * is skipped (vetoed by any one blocked incoming edge unless it is
     * [MasteryNode.pure]) rather than running unconditionally. Malformed
     * syntax is additionally rejected when the definition is created.
     */
    val condition: String? = null,
)
