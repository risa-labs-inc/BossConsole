package ai.rever.boss.mastery

data class MasteryExecution(
    val executionId: String,
    val masteryId: String,
    val input: Map<String, String>,
    val state: String,
    val checkpoints: List<NodeCheckpoint> = emptyList(),
)
