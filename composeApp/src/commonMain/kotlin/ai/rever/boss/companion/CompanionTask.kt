package ai.rever.boss.companion

data class CompanionTask(
    val id: String,
    val name: String,
    val status: CompanionTaskStatus,
    val sourceWindowId: String? = null,
    val context: CompanionTaskContext? = null,
)

enum class CompanionTaskStatus {
    WORKING,
    WAITING_FOR_INPUT,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class CompanionTaskContext(
    val windowId: String? = null,
    val workspaceId: String? = null,
    val tabId: String? = null,
    val resultAvailable: Boolean = false,
    val changesAvailable: Boolean = false,
)
