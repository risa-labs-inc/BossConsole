package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.components.plugin.language.EditorLanguages
import ai.rever.boss.font.FontManager
import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.FileReadResult
import ai.rever.boss.plugin.api.MainFunctionInfo
import ai.rever.boss.plugin.run.DetectedMainFunction
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import ai.rever.boss.run.MainFunctionDetectorProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.Box
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
 * CodeEditorContent is required by the interface but has no caller: the
 * editor-tab plugin renders its own BossEditor, and no plugin in the workspace
 * invokes it. It used to render the host's own CodeEditorUI, which was worse
 * than nothing -- that editor accepted onSaveRequested, onModifiedStateChange
 * and onRunFunction and then discarded all three, so anything that HAD called
 * it would have got an editor that silently refused to save. It now renders a
 * line saying where the editor actually lives.
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
        showRunGutter: Boolean,
    ) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("The code editor is provided by the Code Editor plugin.")
        }
    }

    override fun readFileContent(
        filePath: String,
        maxSize: Long,
    ): FileReadResult =
        when (val result = readFileContentSafe(filePath, maxSize)) {
            is FileReadOutcome.Success -> FileReadResult.Success(result.content)
            is FileReadOutcome.FileTooLarge -> FileReadResult.FileTooLarge(result.sizeBytes, result.maxSizeBytes)
            is FileReadOutcome.Error -> FileReadResult.Error(result.message)
            is FileReadOutcome.FileNotFound -> FileReadResult.FileNotFound
        }

    override fun writeFileContent(
        filePath: String,
        content: String,
    ): Boolean = writeFileContent(filePath, content)

    /**
     * Language id for [filePath], as reported to plugins and to the
     * `editor_detect_language` MCP tool.
     *
     * Delegates to [EditorLanguages], whose mapping the editor-tab plugin
     * duplicates in its own `LanguageDetection` -- a separate artifact, so it
     * cannot share this one. EditorLanguageDetectionTest guards the pair
     * against drift.
     */
    override fun detectLanguage(filePath: String): String = EditorLanguages.detect(filePath)

    // ============ PSI Navigation APIs ============

    override fun isNavigationEnabled(): Boolean = navigationEnabled

    override fun setNavigationEnabled(enabled: Boolean) {
        navigationEnabled = enabled
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun navigateToDefinition(
        filePath: String,
        line: Int,
        column: Int,
    ) {
        GlobalScope.launch(Dispatchers.Main) {
            // Use empty string as sourceWindowId for plugin API calls where windowId is unknown
            // The event handler will use the active window in this case
            FileEventBus.openFile(filePath, line, column, sourceWindowId = "")
        }
    }

    // ============ Main Function Detection ============

    override fun detectMainFunctions(
        filePath: String,
        content: String,
    ): List<MainFunctionInfo> =
        try {
            val detector = MainFunctionDetectorProvider.get()
            val langEnum = Language.fromFileName(filePath)
            val detected = detector.detectInFile(filePath, content, langEnum)
            detected.map { it.toMainFunctionInfo() }
        } catch (e: Exception) {
            logger.warn(
                LogCategory.EDITOR,
                "Main-function detection failed",
                mapOf(
                    "filePath" to filePath,
                ),
                e,
            )
            emptyList()
        }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun executeMainFunction(
        mainFunction: MainFunctionInfo,
        projectPath: String,
        windowId: String?,
    ) {
        if (windowId == null) return

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val detector = MainFunctionDetectorProvider.get()
                val actualProjectRoot = detector.findProjectRoot(mainFunction.filePath)
                val langEnum = Language.fromExtension(mainFunction.language)

                // Create a DetectedMainFunction from MainFunctionInfo
                val detected =
                    DetectedMainFunction(
                        lineNumber = mainFunction.lineNumber,
                        functionName = mainFunction.functionName,
                        className = mainFunction.className,
                        packageName = null,
                        language = langEnum,
                        filePath = mainFunction.filePath,
                    )

                val command = detector.generateCommand(detected, actualProjectRoot)
                val configName = detected.toShortNameWithProject(actualProjectRoot)

                val config =
                    RunConfiguration(
                        id =
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                        name = configName,
                        type = RunConfigurationType.MAIN_FUNCTION,
                        filePath = mainFunction.filePath,
                        lineNumber = mainFunction.lineNumber,
                        language = langEnum,
                        command = command,
                        workingDirectory = actualProjectRoot,
                        isAutoDetected = true,
                    )

                RunEventBus.execute(config, sourceWindowId = windowId)
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.EDITOR,
                    "Failed to execute main function",
                    mapOf(
                        "filePath" to mainFunction.filePath,
                    ),
                    e,
                )
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
private fun DetectedMainFunction.toMainFunctionInfo(): MainFunctionInfo =
    MainFunctionInfo(
        filePath = this.filePath,
        lineNumber = this.lineNumber,
        functionName = this.functionName,
        language = this.language.name.lowercase(),
        className = this.className,
        metadata =
            mapOf(
                "packageName" to (this.packageName ?: ""),
            ),
    )
