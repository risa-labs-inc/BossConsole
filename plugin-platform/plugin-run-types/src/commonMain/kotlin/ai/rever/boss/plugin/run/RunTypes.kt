package ai.rever.boss.plugin.run

import kotlinx.serialization.Serializable

/**
 * Simple utility to extract file name from a path.
 * Duplicated here to avoid dependencies on composeApp utils.
 */
fun String.extractFileName(): String = this.substringAfterLast('/').substringAfterLast('\\')

// ============================================
// Run Configuration Types
// ============================================

/**
 * Represents a run configuration for executing code.
 */
@Serializable
data class RunConfiguration(
    val id: String,
    val name: String,
    val type: RunConfigurationType,
    val filePath: String,
    val lineNumber: Int,
    val language: Language,
    val command: String,
    val workingDirectory: String,
    val environmentVariables: Map<String, String> = emptyMap(),
    val arguments: String = "",
    val isAutoDetected: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Types of run configurations.
 */
@Serializable
enum class RunConfigurationType {
    MAIN_FUNCTION,
    SCRIPT,
    TEST,
    CUSTOM,
}

/**
 * Supported programming languages for run detection.
 */
@Serializable
enum class Language(
    val displayName: String,
    val extensions: List<String>,
) {
    KOTLIN("Kotlin", listOf("kt", "kts")),
    JAVA("Java", listOf("java")),
    PYTHON("Python", listOf("py")),
    JAVASCRIPT("JavaScript", listOf("js", "jsx", "mjs")),
    TYPESCRIPT("TypeScript", listOf("ts", "tsx")),
    GO("Go", listOf("go")),
    RUST("Rust", listOf("rs")),
    UNKNOWN("Unknown", emptyList()),
    ;

    companion object {
        fun fromExtension(extension: String): Language = entries.find { it.extensions.contains(extension.lowercase()) } ?: UNKNOWN

        fun fromFileName(fileName: String): Language {
            val extension = fileName.substringAfterLast('.', "")
            return fromExtension(extension)
        }
    }
}

/**
 * Represents a detected main function in source code.
 */
data class DetectedMainFunction(
    val lineNumber: Int,
    val functionName: String,
    val className: String?,
    val packageName: String?,
    val language: Language,
    val filePath: String,
) {
    /**
     * Creates a display name for this detected function.
     */
    fun toDisplayName(): String =
        when {
            className != null && packageName != null -> "$packageName.$className.$functionName"
            className != null -> "$className.$functionName"
            packageName != null -> "$packageName.$functionName"
            else -> functionName
        }

    /**
     * Creates a short display name for UI.
     */
    fun toShortName(): String {
        val fileName = filePath.extractFileName()
        return when {
            className != null -> "$className.$functionName ($fileName)"
            else -> "$functionName ($fileName)"
        }
    }

    /**
     * Creates a short display name including the project name.
     * Format: "main (main.kt [ProjectName])" or "ClassName.main (Main.kt [ProjectName])"
     *
     * @param projectRoot The project root directory path
     */
    fun toShortNameWithProject(projectRoot: String?): String {
        val fileName = filePath.extractFileName()
        val projectName = projectRoot?.extractFileName()?.takeIf { it.isNotBlank() }

        val nameWithFile =
            when {
                className != null -> "$className.$functionName"
                else -> functionName
            }

        return if (projectName != null) {
            "$nameWithFile ($fileName [$projectName])"
        } else {
            "$nameWithFile ($fileName)"
        }
    }
}

/**
 * Settings for run configurations, persisted to disk.
 */
@Serializable
data class RunConfigurationSettings(
    val configurations: List<RunConfiguration> = emptyList(),
    val lastUsedConfigId: String? = null,
    val recentConfigIds: List<String> = emptyList(),
    val maxRecentConfigs: Int = 10,
)

/**
 * Status of a running process.
 */
enum class ProcessStatus {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
}

/**
 * Represents a currently running process.
 */
data class RunningProcess(
    val id: String,
    val configId: String,
    val configName: String,
    val command: String,
    val startTime: Long,
    val status: ProcessStatus,
)

// ============================================
// Runner Settings Types
// ============================================

/**
 * Target panel for runner terminal output.
 *
 * Issue #347: Configurable terminal target for runner output
 */
@Serializable
enum class RunnerTerminalTarget {
    /**
     * Open runner terminals in the sidebar panel (left.bottom).
     * This is the default behavior, similar to VS Code's integrated terminal.
     */
    SIDEBAR_PANEL,

    /**
     * Open runner terminals in the main panel (center area).
     * This provides more space for output and is similar to IntelliJ's run window.
     */
    MAIN_PANEL,
}

/**
 * Settings for the runner system.
 *
 * Issue #347: Runner configuration settings
 */
@Serializable
data class RunnerSettings(
    /**
     * Where to open runner terminal tabs.
     * Default: SIDEBAR_PANEL
     */
    val terminalTarget: RunnerTerminalTarget = RunnerTerminalTarget.SIDEBAR_PANEL,
    /**
     * Whether to automatically focus the terminal when a runner starts.
     * Default: true
     */
    val focusOnRun: Boolean = true,
    /**
     * Whether to clear the terminal before re-running a configuration.
     * Note: Currently not supported - re-run creates a new terminal.
     * Default: true
     */
    val clearOnRerun: Boolean = true,
    /**
     * Whether to show a notification when a runner process exits.
     * Default: false
     */
    val notifyOnExit: Boolean = false,
    /**
     * Delay in milliseconds between sending Ctrl+C and the new command during re-run.
     * This gives the shell time to handle the interrupt and show its prompt.
     * Default: 1000ms. Range: 0-2000ms
     */
    val rerunDelayMs: Long = 1000,
)

// ============================================
// Run Event Types
// ============================================

/**
 * Event emitted when a run configuration should be executed.
 *
 * @property configuration The run configuration to execute
 * @property debug Whether to run in debug mode (future feature)
 * @property sourceWindowId The window that initiated the run (required for multi-window support)
 */
data class RunExecuteEvent(
    val configuration: RunConfiguration,
    val debug: Boolean = false,
    val sourceWindowId: String,
)

/**
 * Event emitted when running processes should be stopped.
 *
 * @property configId Optional config ID to stop, null means stop all
 * @property sourceWindowId The window that initiated the stop (required for multi-window support)
 */
data class RunStopEvent(
    val configId: String? = null,
    val sourceWindowId: String,
)

/**
 * Event emitted when a project should be scanned for run configurations.
 *
 * @property projectPath The path to the project to scan
 * @property sourceWindowId The window that initiated the scan (required for multi-window support)
 */
data class RunScanEvent(
    val projectPath: String,
    val sourceWindowId: String,
)

// ============================================
// Runner Terminal Event Types
// ============================================

/**
 * Event for opening a runner terminal.
 */
data class RunnerTerminalOpenEvent(
    val terminalId: String,
    val command: String,
    val configId: String,
    val configName: String,
    val workingDirectory: String?,
    val isRerun: Boolean,
    val sourceWindowId: String, // Window that initiated the run (Issue #498)
)

/**
 * Event for stopping a runner terminal (Ctrl+C request).
 * @property sourceWindowId Window that initiated the stop (required for multi-window support)
 */
data class RunnerTerminalStopEvent(
    val terminalId: String,
    val configId: String,
    val sourceWindowId: String,
)

/**
 * Event for closing a runner terminal tab.
 * @property sourceWindowId Window that initiated the close (required for multi-window support)
 */
data class RunnerTerminalCloseEvent(
    val terminalId: String,
    val sourceWindowId: String,
)
