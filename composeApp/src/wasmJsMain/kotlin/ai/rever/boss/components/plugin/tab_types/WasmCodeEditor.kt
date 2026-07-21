package ai.rever.boss.components.plugin.tab_types

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// For WebAssembly, file reading would need to use browser APIs
actual fun readFileContent(filePath: String): String? {
    // TODO: Implement browser-based file reading
    return null
}

// For WebAssembly, file writing would need to use browser APIs
actual fun writeFileContent(filePath: String, content: String): Boolean {
    // TODO: Implement browser-based file writing
    return false
}

// WASM implementations - using default values since settings persistence would be different in browser
actual fun getCodeEditorFontSize(): Int = 14
actual fun getCodeEditorFontFamily(): FontFamily = FontFamily.Monospace
actual fun getCodeEditorBackgroundColor(): Color = Color(0xFF_1E1E1E)
actual fun getCodeEditorTextColor(): Color = Color(0xFF_D4D4D4)
actual fun getCodeEditorLineNumberColor(): Color = Color(0xFF_858585)
actual fun getCodeEditorLineNumberBgColor(): Color = Color(0xFF_2D2D30)
actual fun getCodeEditorKeywordColor(): Color = Color(0xFF_569CD6)
actual fun getCodeEditorCommentColor(): Color = Color(0xFF_6A9955)

/**
 * WASM implementation uses BasicTextField fallback.
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
