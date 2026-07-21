package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.NodeLoadingStateData

// For iOS, return mock data as file system access is restricted
actual fun scanDirectory(path: String): FileNodeData? {
    // iOS has restricted file system access
    // Return mock data for demonstration
    return FileNodeData(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = true,
        children = listOf(
            FileNodeData(
                name = "README.md",
                path = "$path/README.md",
                isDirectory = false,
                hasChildren = false,
                loadingState = NodeLoadingStateData.LOADED
            ),
            FileNodeData(
                name = "src",
                path = "$path/src",
                isDirectory = true,
                children = listOf(
                    FileNodeData(
                        name = "main.kt",
                        path = "$path/src/main.kt",
                        isDirectory = false,
                        hasChildren = false,
                        loadingState = NodeLoadingStateData.LOADED
                    )
                ),
                hasChildren = true,
                loadingState = NodeLoadingStateData.LOADED
            )
        ),
        hasChildren = true,
        loadingState = NodeLoadingStateData.LOADED
    )
}

actual suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int): FileNodeData? {
    // iOS has restricted file system access
    // Return the same mock data
    return scanDirectory(path)
}

/**
 * iOS mock implementation - always returns true for directories
 */
actual fun directoryHasChildren(path: String): Boolean {
    // Mock - assume directories have children
    return true
}

// showHidden variants — the iOS mock has no hidden entries, so they delegate

actual fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? = scanDirectory(path)

actual suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int, showHidden: Boolean): FileNodeData? =
    scanDirectoryWithDepth(path, maxDepth, startDepth)

actual fun directoryHasChildren(path: String, showHidden: Boolean): Boolean = directoryHasChildren(path)
