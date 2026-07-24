package ai.rever.boss.orchestrator

/**
 * Interface for AI-powered repair proposals.
 *
 * Implementations call an external language model (e.g. OpenAI-compatible API) to
 * generate code patches or configuration recommendations for failing processes.
 *
 * All methods return `null` when the AI service is unavailable, allowing callers
 * to fall back to static repair strategies gracefully.
 */
interface AiRepairClient {
    /**
     * Proposes a source-code fix for a process failure.
     *
     * @param rootCause Human-readable root cause from [CrashAnalyzer].
     * @param sourceFiles Map of file path → file content from the process manifest.
     * @param stackTrace Raw stack trace string (may be blank).
     * @param errorMessage Raw error message string.
     * @return A [SourceFixProposal] with file patches, or `null` if unavailable.
     */
    suspend fun proposeSourceFix(
        rootCause: String,
        sourceFiles: Map<String, String>,
        stackTrace: String,
        errorMessage: String,
    ): SourceFixProposal?

    /**
     * Proposes configuration changes for a process failure.
     *
     * @param processId Process identifier for context.
     * @param rootCause Human-readable root cause from [CrashAnalyzer].
     * @param suggestedFix Hint from the manifest's repair hint, or `null`.
     * @param errorMessage Raw error message string.
     * @return A [ConfigFixProposal] with key-value config changes, or `null` if unavailable.
     */
    suspend fun proposeConfigFix(
        processId: String,
        rootCause: String,
        suggestedFix: String?,
        errorMessage: String,
    ): ConfigFixProposal?
}

/** A proposed set of file patches for a source-code repair. */
data class SourceFixProposal(
    val explanation: String,
    val patches: List<FilePatch>,
) {
    fun toSummary(): String =
        buildString {
            appendLine(explanation)
            for (patch in patches) {
                appendLine("--- ${patch.filePath}")
                appendLine(patch.description)
            }
        }.trim()
}

/** A surgical replacement within a single source file. */
data class FilePatch(
    val filePath: String,
    /** Full original file content (before patch). */
    val originalContent: String,
    /** Full file content after the patch is applied. */
    val patchedContent: String,
    val description: String,
)

/** A proposed set of configuration key-value changes for a process. */
data class ConfigFixProposal(
    val explanation: String,
    /** Map of config key → new value to apply. */
    val configChanges: Map<String, String>,
) {
    fun toDescription(): String =
        buildString {
            appendLine(explanation)
            if (configChanges.isNotEmpty()) {
                appendLine("Suggested config changes:")
                configChanges.forEach { (k, v) -> appendLine("  $k = $v") }
            }
        }.trim()
}
