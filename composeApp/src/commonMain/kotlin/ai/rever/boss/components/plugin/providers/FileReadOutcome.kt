package ai.rever.boss.components.plugin.providers

/** The outcome of a size-validated read by [readFileContentSafe]. */
sealed class FileReadOutcome {
    data class Success(
        val content: String,
    ) : FileReadOutcome()

    data class FileTooLarge(
        val sizeBytes: Long,
        val maxSizeBytes: Long,
    ) : FileReadOutcome()

    data class Error(
        val message: String,
    ) : FileReadOutcome()

    object FileNotFound : FileReadOutcome()
}
