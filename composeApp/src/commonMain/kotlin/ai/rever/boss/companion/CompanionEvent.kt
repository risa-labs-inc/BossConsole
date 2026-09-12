package ai.rever.boss.companion

sealed class CompanionEvent {
    abstract val taskId: String
    abstract val taskName: String
    abstract val sourceWindowId: String?
    abstract val context: CompanionTaskContext?

    data class Started(
        override val taskId: String,
        override val taskName: String,
        override val sourceWindowId: String? = null,
        override val context: CompanionTaskContext? = null,
    ) : CompanionEvent()

    data class WaitingForInput(
        override val taskId: String,
        override val taskName: String,
        override val sourceWindowId: String? = null,
        override val context: CompanionTaskContext? = null,
    ) : CompanionEvent()

    data class Completed(
        override val taskId: String,
        override val taskName: String,
        override val sourceWindowId: String? = null,
        override val context: CompanionTaskContext? = null,
    ) : CompanionEvent()

    data class Failed(
        override val taskId: String,
        override val taskName: String,
        override val sourceWindowId: String? = null,
        override val context: CompanionTaskContext? = null,
    ) : CompanionEvent()

    data class Stopped(
        override val taskId: String,
        override val taskName: String,
        override val sourceWindowId: String? = null,
        override val context: CompanionTaskContext? = null,
    ) : CompanionEvent()
}
