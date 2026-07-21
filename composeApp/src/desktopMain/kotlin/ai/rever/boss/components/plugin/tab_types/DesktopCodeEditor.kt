package ai.rever.boss.components.plugin.tab_types

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import java.io.File

private val codeEditorLogger = BossLogger.forComponent("DesktopCodeEditor")

actual fun readFileContent(filePath: String): String? {
    return try {
        val file = File(filePath)
        if (file.exists() && file.isFile) {
            file.readText()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Reads file content with size validation.
 * Files larger than maxSize will return FileTooLarge instead of loading.
 */
actual fun readFileContentSafe(filePath: String, maxSize: Long): FileReadResult {
    return try {
        val file = File(filePath)
        when {
            !file.exists() || !file.isFile -> FileReadResult.FileNotFound
            file.length() > maxSize -> FileReadResult.FileTooLarge(file.length(), maxSize)
            else -> {
                try {
                    FileReadResult.Success(file.readText())
                } catch (e: OutOfMemoryError) {
                    FileReadResult.Error("File too large to load into memory: ${e.message}")
                }
            }
        }
    } catch (e: Exception) {
        FileReadResult.Error(e.message ?: "Unknown error reading file")
    }
}

actual fun writeFileContent(filePath: String, content: String): Boolean {
    return try {
        val file = File(filePath)
        // Create parent directories if they don't exist
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    } catch (e: Exception) {
        codeEditorLogger.warn(LogCategory.EDITOR, "Error writing file", error = e)
        false
    }
}

// Hardcoded defaults matching non-desktop actuals (these are only used by the
// commonMain CodeEditorUI composable which is never rendered on desktop).
actual fun getCodeEditorFontSize(): Int = 14
actual fun getCodeEditorFontFamily(): FontFamily = FontFamily.Monospace
actual fun getCodeEditorBackgroundColor(): Color = Color(0xFF_1E1E1E)
actual fun getCodeEditorTextColor(): Color = Color(0xFF_D4D4D4)
actual fun getCodeEditorLineNumberColor(): Color = Color(0xFF_858585)
actual fun getCodeEditorLineNumberBgColor(): Color = Color(0xFF_2D2D30)
actual fun getCodeEditorKeywordColor(): Color = Color(0xFF_569CD6)
actual fun getCodeEditorCommentColor(): Color = Color(0xFF_6A9955)

/**
 * Desktop delegates to the shared basic editor, like the other platforms.
 * The real editor experience is the editor-tab plugin, which registers the
 * "editor" tab type and renders its own BossEditor — this composable is a
 * fallback that is not reached while the plugin is installed.
 */
@Composable
actual fun PlatformCodeEditorUI(
    content: String,
    onContentChange: (String) -> Unit,
    language: String,
    filePath: String,
    projectPath: String,
    modifier: Modifier,
    onModifiedStateChange: (Boolean) -> Unit,
    onSaveRequested: suspend () -> Boolean
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
