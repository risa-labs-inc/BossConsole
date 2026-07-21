package ai.rever.boss.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberDirectoryPicker(
    onDirectorySelected: (path: String?) -> Unit
): DirectoryPicker {
    // TODO: Implement Android directory picker using Intent.ACTION_OPEN_DOCUMENT_TREE
    // For now, return no-op implementation
    return remember { NoOpDirectoryPicker() }
}
