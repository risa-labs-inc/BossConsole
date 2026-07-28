package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.components.plugin.tab_types.CodeEditorUI
import ai.rever.boss.components.plugin.tab_types.readFileContentSafe
import ai.rever.boss.components.plugin.tab_types.writeFileContent
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
        showRunGutter: Boolean,
    ) {
        CodeEditorUI(
            content = content,
            onContentChange = onContentChange,
            language = language,
            filePath = filePath,
            projectPath = projectPath,
            modifier = modifier,
        )
    }

    override fun readFileContent(
        filePath: String,
        maxSize: Long,
    ): FileReadResult =
        when (val result = readFileContentSafe(filePath, maxSize)) {
            is InternalFileReadResult.Success -> FileReadResult.Success(result.content)
            is InternalFileReadResult.FileTooLarge -> FileReadResult.FileTooLarge(result.sizeBytes, result.maxSizeBytes)
            is InternalFileReadResult.Error -> FileReadResult.Error(result.message)
            is InternalFileReadResult.FileNotFound -> FileReadResult.FileNotFound
        }

    override fun writeFileContent(
        filePath: String,
        content: String,
    ): Boolean = writeFileContent(filePath, content)

    /**
     * Language id for [filePath], as reported to plugins and to the
     * `editor_detect_language` MCP tool.
     *
     * File *name* patterns are checked before the extension: the files that most
     * need identifying have no extension at all, and an extension-only lookup can
     * never match `Dockerfile` or `Makefile` (`substringAfterLast('.')` yields `""`).
     *
     * The extension is read from the file name rather than the whole path, because
     * reading it from the path let a dot in a parent directory leak into the answer —
     * `/srv/v1.2/Makefile` produced the "extension" `2/Makefile`.
     *
     * Kept in step with `LanguageDetection` in the editor-tab plugin, which is what
     * actually selects the lexer; a disagreement between the two shows up as the
     * tool reporting one language while the editor highlights another.
     */
    override fun detectLanguage(filePath: String): String {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        languageForFileName(fileName)?.let { return it }
        val extension = fileName.substringAfterLast('.', "").lowercase()
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

            // Languages the editor has lexers for but this map never named.
            "dockerfile" -> "dockerfile"

            "mk", "mak" -> "makefile"

            "properties", "ini", "cfg", "env" -> "properties"

            "diff", "patch" -> "diff"

            "bat", "cmd" -> "batch"

            "clj", "cljs", "cljc", "edn" -> "clojure"

            "tex", "sty", "cls", "bib" -> "latex"

            "lisp", "lsp", "el", "scm" -> "lisp"

            "tcl" -> "tcl"

            "f", "f90", "f95", "f03", "for" -> "fortran"

            "d" -> "d"

            "pas", "dpr", "dfm" -> "delphi"

            "vb", "vbs" -> "visualbasic"

            "as" -> "actionscript"

            "jsp", "jspx" -> "jsp"

            else -> "text"
        }
    }

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

/**
 * Languages identified by file name rather than extension, so that `Dockerfile.dev`
 * is a Dockerfile rather than whatever `.dev` might otherwise suggest.
 *
 * A top-level function rather than a member: it needs nothing from the provider, and
 * as a method it pushed the class past detekt's TooManyFunctions threshold.
 */
private fun languageForFileName(fileName: String): String? {
    val lower = fileName.lowercase()
    return when {
        lower == "dockerfile" || lower.startsWith("dockerfile.") -> "dockerfile"
        lower == "containerfile" || lower.startsWith("containerfile.") -> "dockerfile"
        lower == "makefile" || lower == "gnumakefile" || lower.startsWith("makefile.") -> "makefile"
        lower == "cmakelists.txt" -> "makefile"
        lower == ".env" || lower.startsWith(".env.") -> "properties"
        lower == "gemfile" || lower == "rakefile" -> "ruby"
        else -> null
    }
}
