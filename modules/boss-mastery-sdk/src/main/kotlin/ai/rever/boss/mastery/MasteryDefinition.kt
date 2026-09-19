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
     * `key == literal` / `key != literal`. Null or blank conditions are
     * unconditional. A malformed expression — or one that cannot be
     * established, such as a comparison against a key with no output value —
     * fails closed: the edge is not followed and the dependent node is
     * skipped rather than running unconditionally.
     *
     * **Literal-vs-key gotcha**: the bare-key form reads the output value at
     * `key` and tests it for truthiness, so a condition of `true` or `false`
     * always means the boolean literal — *never* the value of an output key
     * literally named `true` or `false`. To compare against those strings,
     * use the explicit equality form (`key == "true"` / `key != "false"`).
     * The grammar document at [MasteryEdgeCondition] says this; restating it
     * here is the one place an author actually looks.
     */
    val condition: String? = null,
)
