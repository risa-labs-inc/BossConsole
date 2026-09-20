package ai.rever.boss.mastery

data class NodeExecutionContext(
    val input: Map<String, String>,
    val previousOutput: Map<String, String>?,
    val humanFeedback: String?,
)
