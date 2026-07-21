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
    /** Optional expression that must be true for this edge to be followed. */
    val condition: String? = null,
)
