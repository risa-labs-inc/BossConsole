package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.plugin.tab_types.CodeEditorUI
import ai.rever.boss.components.plugin.tab_types.readFileContentSafe
import ai.rever.boss.components.plugin.tab_types.writeFileContent
import ai.rever.boss.font.FontManager
import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.FileReadResult
import ai.rever.boss.plugin.api.MainFunctionInfo
import ai.rever.boss.plugin.run.DetectedMainFunction
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfigurationType
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.run.MainFunctionDetectorProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import ai.rever.boss.components.plugin.tab_types.FileReadResult as InternalFileReadResult

private val logger = BossLogger.forComponent("EditorContentProvider")

/**
 * Desktop implementation of EditorContentProvider.
 *
 * Exposes the editor capabilities the HOST still owns after BossEditor moved
 * into the editor-tab plugin: file I/O, language detection, file-open routing,
 * run-configuration integration, and font enumeration. Everything editor-
 * internal (settings, themes, search state, completion, undo/redo) lives in
 * the plugin's bundled BossEditor now — those interface methods fall back to
 * their plugin-api defaults here.
 *
 * CodeEditorContent only backs the basic shared fallback editor; the editor-tab
 * plugin renders its own BossEditor and doesn't call it. Note the fallback is
 * effectively VIEW-ONLY: CodeEditorUI has no save/modified/run-gutter wiring,
 * so the onSaveRequested/onModifiedStateChange/onRunFunction callbacks are
 * accepted but discarded.
 */
class EditorContentProviderImpl : EditorContentProvider {

    @Composable
    override fun CodeEditorContent(
        content: String,
        onContentChange: (String) -> Unit,
        language: String,
        filePath: String,
        projectPath: String,
        modifier: Modifier,
        onModifiedStateChange: (Boolean) -> Unit,
        onSaveRequested: suspend () -> Boolean,
        onCursorPositionChange: ((line: Int, column: Int) -> Unit)?,
        onRunFunction: ((MainFunctionInfo) -> Unit)?,
        onNavigate: ((filePath: String, line: Int, column: Int) -> Unit)?,
        showRunGutter: Boolean
    ) {
        CodeEditorUI(
            content = content,
            onContentChange = onContentChange,
            language = language,
            filePath = filePath,
            projectPath = projectPath,
            modifier = modifier
        )
    }

    override fun readFileContent(filePath: String, maxSize: Long): FileReadResult {
        return when (val result = readFileContentSafe(filePath, maxSize)) {
            is InternalFileReadResult.Success -> FileReadResult.Success(result.content)
            is InternalFileReadResult.FileTooLarge -> FileReadResult.FileTooLarge(result.sizeBytes, result.maxSizeBytes)
            is InternalFileReadResult.Error -> FileReadResult.Error(result.message)
            is InternalFileReadResult.FileNotFound -> FileReadResult.FileNotFound
        }
    }

    override fun writeFileContent(filePath: String, content: String): Boolean {
        return writeFileContent(filePath, content)
    }

    override fun detectLanguage(filePath: String): String {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "json" -> "json"
            "xml" -> "xml"
            "html", "htm" -> "html"
            "css" -> "css"
            "md" -> "markdown"
            "toml" -> "toml"
            "gradle" -> "groovy"
            "swift" -> "swift"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "rs" -> "rust"
            "go" -> "go"
            "rb" -> "ruby"
            "php" -> "php"
            "sh", "bash" -> "bash"
            "yml", "yaml" -> "yaml"
            "sql" -> "sql"
            "r" -> "r"
            "scala" -> "scala"
            else -> "text"
        }
    }

    // ============ PSI Navigation APIs ============

    override fun isNavigationEnabled(): Boolean = navigationEnabled

    override fun setNavigationEnabled(enabled: Boolean) {
        navigationEnabled = enabled
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun navigateToDefinition(filePath: String, line: Int, column: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            // Use empty string as sourceWindowId for plugin API calls where windowId is unknown
            // The event handler will use the active window in this case
            FileEventBus.openFile(filePath, line, column, sourceWindowId = "")
        }
    }

    // ============ Main Function Detection ============

    override fun detectMainFunctions(filePath: String, content: String): List<MainFunctionInfo> {
        return try {
            val detector = MainFunctionDetectorProvider.get()
            val langEnum = Language.fromFileName(filePath)
            val detected = detector.detectInFile(filePath, content, langEnum)
            detected.map { it.toMainFunctionInfo() }
        } catch (e: Exception) {
            logger.warn(LogCategory.EDITOR, "Main-function detection failed", mapOf(
                "filePath" to filePath
            ), e)
            emptyList()
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun executeMainFunction(mainFunction: MainFunctionInfo, projectPath: String, windowId: String?) {
        if (windowId == null) return

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val detector = MainFunctionDetectorProvider.get()
                val actualProjectRoot = detector.findProjectRoot(mainFunction.filePath)
                val langEnum = Language.fromExtension(mainFunction.language)

                // Create a DetectedMainFunction from MainFunctionInfo
                val detected = DetectedMainFunction(
                    lineNumber = mainFunction.lineNumber,
                    functionName = mainFunction.functionName,
                    className = mainFunction.className,
                    packageName = null,
                    language = langEnum,
                    filePath = mainFunction.filePath
                )

                val command = detector.generateCommand(detected, actualProjectRoot)
                val configName = detected.toShortNameWithProject(actualProjectRoot)

                val config = RunConfiguration(
                    id = java.util.UUID.randomUUID().toString(),
                    name = configName,
                    type = RunConfigurationType.MAIN_FUNCTION,
                    filePath = mainFunction.filePath,
                    lineNumber = mainFunction.lineNumber,
                    language = langEnum,
                    command = command,
                    workingDirectory = actualProjectRoot,
                    isAutoDetected = true
                )

                RunEventBus.execute(config, sourceWindowId = windowId)
            } catch (e: Exception) {
                logger.warn(LogCategory.EDITOR, "Failed to execute main function", mapOf(
                    "filePath" to mainFunction.filePath
                ), e)
            }
        }
    }

    // ============ Font Enumeration ============

    override fun getAvailableFonts(): List<String> = FontManager.getAvailableMonospaceFonts()

    companion object {
        // Runtime toggle for PSI navigation (not an editor setting)
        private var navigationEnabled: Boolean = true
    }
}

/**
 * Extension function to convert DetectedMainFunction to MainFunctionInfo.
 */
private fun DetectedMainFunction.toMainFunctionInfo(): MainFunctionInfo {
    return MainFunctionInfo(
        filePath = this.filePath,
        lineNumber = this.lineNumber,
        functionName = this.functionName,
        language = this.language.name.lowercase(),
        className = this.className,
        metadata = mapOf(
            "packageName" to (this.packageName ?: "")
        )
    )
}
