package ai.rever.boss.components.plugin.tab_types

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.FileChangeEvent
import ai.rever.boss.plugin.api.FileChangeType
import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.run.DetectedMainFunction
import ai.rever.boss.run.Language
import ai.rever.boss.run.MainFunctionDetectorProvider
import ai.rever.boss.run.RunConfiguration
import ai.rever.boss.run.RunConfigurationType
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private val codeEditorLogger = BossLogger.forComponent("CodeEditor")

// Simple syntax highlighting for common keywords
private val kotlinKeywords = setOf(
    "abstract", "annotation", "as", "break", "by", "catch", "class", "companion",
    "const", "constructor", "continue", "crossinline", "data", "do", "else", "enum",
    "expect", "external", "false", "final", "finally", "for", "fun", "if", "import",
    "in", "infix", "init", "inline", "inner", "interface", "internal", "is", "lateinit",
    "noinline", "null", "object", "open", "operator", "out", "override", "package",
    "private", "protected", "public", "reified", "return", "sealed", "super", "suspend",
    "tailrec", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "vararg", "when", "where", "while"
)

private val types = setOf(
    "Boolean", "Byte", "Char", "Double", "Float", "Int", "Long", "Short", "String",
    "Unit", "Any", "Nothing", "List", "Map", "Set", "Array", "MutableList", "MutableMap",
    "MutableSet"
)

@Composable
fun CodeEditorUI(
    content: String,
    onContentChange: (String) -> Unit,
    language: String = "kotlin",
    filePath: String = "",
    projectPath: String = "",
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val windowId = LocalWindowId.current  // Issue #506: Get window ID for multi-window support
    val density = LocalDensity.current

    // Get settings from platform-specific implementation
    val fontSize = getCodeEditorFontSize()
    val fontFamily = getCodeEditorFontFamily()
    val backgroundColor = getCodeEditorBackgroundColor()
    val textColor = getCodeEditorTextColor()
    val lineNumberColor = getCodeEditorLineNumberColor()
    val lineNumberBgColor = getCodeEditorLineNumberBgColor()

    // Calculate consistent line height in dp
    val lineHeightSp = (fontSize * 1.5f).sp
    val lineHeightDp = with(density) { lineHeightSp.toDp() }

    val textStyle = LocalTextStyle.current.copy(
        fontFamily = fontFamily,
        fontSize = fontSize.sp,
        lineHeight = lineHeightSp
    )

    // Use TextFieldValue to maintain cursor position
    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }

    // State for detected main functions (runnable lines)
    var detectedMainFunctions by remember { mutableStateOf<List<DetectedMainFunction>>(emptyList()) }
    val runnableLineNumbers = remember(detectedMainFunctions) {
        detectedMainFunctions.map { it.lineNumber }.toSet()
    }

    // Update TextFieldValue when content changes externally
    LaunchedEffect(content) {
        if (content != textFieldValue.text) {
            textFieldValue = TextFieldValue(content)
        }
    }

    // Detect main functions when content or language changes
    LaunchedEffect(content, language, filePath) {
        if (filePath.isNotEmpty()) {
            try {
                val detector = MainFunctionDetectorProvider.get()
                val langEnum = Language.fromFileName(filePath)
                detectedMainFunctions = detector.detectInFile(filePath, content, langEnum)
            } catch (e: Exception) {
                codeEditorLogger.warn(LogCategory.EDITOR, "Error detecting main functions", error = e)
                detectedMainFunctions = emptyList()
            }
        }
    }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    Surface(
        modifier = modifier,
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // Line numbers with run icons - synchronized with content
            val lines = textFieldValue.text.lines()
            Column(
                modifier = Modifier
                    .background(lineNumberBgColor)
                    .padding(start = 4.dp, end = 4.dp)
                    .verticalScroll(verticalScrollState)
                    .padding(top = 8.dp) // Match content padding
            ) {
                lines.forEachIndexed { index, _ ->
                    Row(
                        modifier = Modifier.height(lineHeightDp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Run icon (if line has main function)
                        if (runnableLineNumbers.contains(index)) {
                            val detected = detectedMainFunctions.find { it.lineNumber == index }
                            if (detected != null) {
                                GutterRunIcon(
                                    detected = detected,
                                    onRun = { detectedFunc ->
                                        scope.launch {
                                            executeDetectedMainFunction(detectedFunc, projectPath, windowId)
                                        }
                                    }
                                )
                            }
                        } else {
                            GutterRunIconSpacer()
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Line number - right aligned with fixed width
                        val lineNumWidth = (lines.size.toString().length * 10).dp
                        Box(
                            modifier = Modifier.width(lineNumWidth),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = textStyle.copy(color = lineNumberColor),
                            )
                        }
                    }
                }
            }

            // Divider between line numbers and content
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(BossTheme.colors.line)
            )

            // Editor content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
                    .verticalScroll(verticalScrollState)
                    .padding(8.dp)
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onContentChange(newValue.text)
                    },
                    textStyle = textStyle.copy(color = textColor),
                    cursorBrush = SolidColor(BossTheme.colors.signal),
                    modifier = Modifier.fillMaxSize(),
                    visualTransformation = SyntaxHighlightTransformation(
                        language,
                        getCodeEditorKeywordColor(),
                        getCodeEditorCommentColor()
                    )
                )
            }
        }
    }
}

/**
 * Execute a detected main function by creating a run configuration and sending it to the event bus.
 *
 * @param detected The detected main function to execute
 * @param projectPath The project path
 * @param windowId The window ID for multi-window support (Issue #506)
 */
suspend fun executeDetectedMainFunction(detected: DetectedMainFunction, projectPath: String, windowId: String? = null) {
    if (windowId == null) {
        codeEditorLogger.warn(LogCategory.EDITOR, "Cannot execute main function: no window ID")
        return
    }
    try {
        val detector = MainFunctionDetectorProvider.get()
        // Find the actual project root for the working directory
        val actualProjectRoot = detector.findProjectRoot(detected.filePath)
        val command = detector.generateCommand(detected, actualProjectRoot)

        val configName = detected.toShortNameWithProject(actualProjectRoot)

        val config = RunConfiguration(
            id = UUID.randomUUID().toString(),
            name = configName,
            type = RunConfigurationType.MAIN_FUNCTION,
            filePath = detected.filePath,
            lineNumber = detected.lineNumber,
            language = detected.language,
            command = command,
            workingDirectory = actualProjectRoot,
            isAutoDetected = true
        )

        RunEventBus.execute(config, sourceWindowId = windowId)
    } catch (e: Exception) {
        codeEditorLogger.error(LogCategory.EDITOR, "Error executing main function", error = e)
    }
}

// Custom VisualTransformation for syntax highlighting
class SyntaxHighlightTransformation(
    private val language: String,
    private val keywordColor: Color,
    private val commentColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = when (language) {
            "kotlin" -> highlightKotlinSyntax(text.text)
            "toml" -> highlightTomlSyntax(text.text)
            else -> text
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
    
    private fun highlightKotlinSyntax(text: String): AnnotatedString {
        return buildAnnotatedString {
            append(text)
            
            // Highlight keywords with word boundaries
            kotlinKeywords.forEach { keyword ->
                val pattern = "\\b$keyword\\b".toRegex()
                pattern.findAll(text).forEach { match ->
                    addStyle(
                        SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold),
                        match.range.first,
                        match.range.last + 1
                    )
                }
            }
            
            // Highlight types
            types.forEach { type ->
                val pattern = "\\b$type\\b".toRegex()
                pattern.findAll(text).forEach { match ->
                    addStyle(
                        SpanStyle(color = EditorSyntaxColors.type),
                        match.range.first,
                        match.range.last + 1
                    )
                }
            }
            
            // Highlight single-line comments
            "//.*$".toRegex(RegexOption.MULTILINE).findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = commentColor),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // Highlight strings
            "\".*?\"".toRegex().findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = EditorSyntaxColors.string),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // Highlight numbers
            "\\b\\d+(\\.\\d+)?\\b".toRegex().findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = EditorSyntaxColors.number),
                    match.range.first,
                    match.range.last + 1
                )
            }
        }
    }
    
    private fun highlightTomlSyntax(text: String): AnnotatedString {
        return buildAnnotatedString {
            append(text)
            
            // Highlight section headers [section]
            "\\[.*?]".toRegex().findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = EditorSyntaxColors.type, fontWeight = FontWeight.Bold),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // Highlight keys (before =)
            "^\\s*([\\w.-]+)\\s*=".toRegex(RegexOption.MULTILINE).findAll(text).forEach { match ->
                match.groupValues.getOrNull(1)?.let { key ->
                    val keyStart = match.range.first + match.value.indexOf(key)
                    addStyle(
                        SpanStyle(color = EditorSyntaxColors.property),
                        keyStart,
                        keyStart + key.length
                    )
                }
            }
            
            // Highlight strings
            "\".*?\"".toRegex().findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = EditorSyntaxColors.string),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // Highlight comments
            "#.*$".toRegex(RegexOption.MULTILINE).findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = commentColor),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // Highlight numbers
            "\\b\\d+(\\.\\d+)?\\b".toRegex().findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = EditorSyntaxColors.number),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // Highlight booleans
            "\\b(true|false)\\b".toRegex().findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(color = EditorSyntaxColors.keyword),
                    match.range.first,
                    match.range.last + 1
                )
            }
        }
    }
}


// Platform-specific file reading
expect fun readFileContent(filePath: String): String?

/**
 * Result of attempting to read a file with size validation.
 */
sealed class FileReadResult {
    data class Success(val content: String) : FileReadResult()
    data class FileTooLarge(val sizeBytes: Long, val maxSizeBytes: Long) : FileReadResult()
    data class Error(val message: String) : FileReadResult()
    object FileNotFound : FileReadResult()
}

/**
 * Reads file content with size validation.
 * Files larger than maxSize will return FileTooLarge instead of loading.
 *
 * @param filePath Path to the file
 * @param maxSize Maximum allowed file size in bytes (default: 100MB)
 * @return FileReadResult indicating success, size limit exceeded, or error
 */
expect fun readFileContentSafe(
    filePath: String,
    maxSize: Long = 100 * 1024 * 1024 // 100 MB default
): FileReadResult

// Platform-specific file writing
expect fun writeFileContent(filePath: String, content: String): Boolean

// Platform-specific settings
expect fun getCodeEditorFontSize(): Int
expect fun getCodeEditorFontFamily(): FontFamily
expect fun getCodeEditorBackgroundColor(): Color
expect fun getCodeEditorTextColor(): Color
expect fun getCodeEditorLineNumberColor(): Color
expect fun getCodeEditorLineNumberBgColor(): Color
expect fun getCodeEditorKeywordColor(): Color
expect fun getCodeEditorCommentColor(): Color

/**
 * Platform-specific code editor UI.
 * Desktop uses BossEditor, other platforms use BasicTextField.
 */
@Composable
expect fun PlatformCodeEditorUI(
    content: String,
    onContentChange: (String) -> Unit,
    language: String,
    filePath: String,
    projectPath: String,
    modifier: Modifier,
    onModifiedStateChange: (Boolean) -> Unit,
    onSaveRequested: suspend () -> Boolean
)

class CodeEditorTabComponent(
    override val config: TabInfo,
    componentContext: ComponentContext
) : TabComponentWithUI, ComponentContext by componentContext {

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content
    
    private val _language = MutableStateFlow("kotlin")
    val language: StateFlow<String> = _language

    override val tabTypeInfo = CodeEditorTabType
    
    init {
        // Load file content if path is provided
        if (config is EditorTabInfo && config.filePath.isNotEmpty()) {
            loadFile(config.filePath)
        } else {
            // Default content if no file path
            _content.value = """// New file
// Start typing...
""".trimIndent()
        }
    }
    
    private fun loadFile(filePath: String) {
        val fileContent = readFileContent(filePath)
        if (fileContent != null) {
            _content.value = fileContent
            // Update language based on file extension
            updateLanguageFromPath(filePath)
        } else {
            _content.value = "// File not found or error loading: $filePath"
        }
    }
    
    private fun updateLanguageFromPath(path: String) {
        val extension = path.substringAfterLast('.', "")
        _language.value = when (extension) {
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
            else -> "text"
        }
    }

    @Composable
    override fun Content() {
        val currentContent by content.collectAsState()
        val currentLanguage by language.collectAsState()
        val currentFilePath = (config as? EditorTabInfo)?.filePath ?: ""
        val projectPath = currentFilePath.substringBeforeLast('/')

        PlatformCodeEditorUI(
            content = currentContent,
            onContentChange = { _content.value = it },
            language = currentLanguage,
            filePath = currentFilePath,
            projectPath = projectPath,
            modifier = Modifier.fillMaxSize(),
            onModifiedStateChange = { /* TODO: Update EditorTabInfo.isModified */ },
            onSaveRequested = {
                val saved = writeFileContent(currentFilePath, _content.value)
                if (saved) {
                    publishSystemEvent(
                        FileChangeEvent(
                            filePath = currentFilePath,
                            changeType = FileChangeType.MODIFIED,
                            projectPath = projectPath,
                        )
                    )
                }
                saved
            }
        )
    }

}

fun DefaultPlugin.registerCodeEditor() = tabRegistry.registerTabType(CodeEditorTabType) {
    tabInfo, ctx -> CodeEditorTabComponent(tabInfo, ctx)
}
