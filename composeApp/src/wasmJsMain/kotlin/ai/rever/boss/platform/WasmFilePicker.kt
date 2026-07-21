package ai.rever.boss.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberDirectoryPicker(
    onDirectorySelected: (path: String?) -> Unit
): DirectoryPicker {
    // Browser doesn't have access to file system
    // Return no-op implementation
    return remember { NoOpDirectoryPicker() }
}
