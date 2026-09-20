package ai.rever.boss.mastery

data class NodeCheckpoint(
    val checkpointId: String,
    val executionId: String,
    val nodeId: String,
    val attempt: Int,

    val input: Map<String, String>,
    val output: Map<String, String>,

    val startedAt: Long,
    val completedAt: Long,

    val feedback: String? = null,
    val parentCheckpointId: String? = null,
    val humanEdited: Boolean = false,
)
