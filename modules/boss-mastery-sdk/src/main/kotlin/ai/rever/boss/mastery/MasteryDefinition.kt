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
     * `key == literal` / `key != literal`. A bare-key condition reading an
     * output key literally named `true` or `false` is interpreted as the
     * boolean literal; the `key == "true"` comparison form is the
     * unambiguous way to compare against those strings. Null or blank
     * conditions are unconditional. A malformed expression — or one that
     * cannot be established, such as a comparison against a key with no
     * output value — fails closed: the edge is not followed and the
     * dependent node is skipped rather than running unconditionally.
     */
    val condition: String? = null,
)
