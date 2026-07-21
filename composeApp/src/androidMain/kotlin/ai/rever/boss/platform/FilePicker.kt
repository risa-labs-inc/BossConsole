package ai.rever.boss.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberFilePicker(
    onFileSelected: (path: String?, content: String?) -> Unit,
    fileExtensions: List<String>
): FilePicker {
    // TODO: Implement Android file picker using ActivityResultContracts
    return remember { NoOpFilePicker() }
}
