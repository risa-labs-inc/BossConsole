package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.NodeLoadingStateData

// For WebAssembly, return mock data as file system access is not available
actual fun scanDirectory(path: String): FileNodeData? {
    // Browser doesn't have direct file system access
    // Return mock data for demonstration
    return FileNodeData(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = true,
        children = listOf(
            FileNodeData(
                name = "index.html",
                path = "$path/index.html",
                isDirectory = false,
                hasChildren = false,
                loadingState = NodeLoadingStateData.LOADED
            ),
            FileNodeData(
                name = "js",
                path = "$path/js",
                isDirectory = true,
                children = listOf(
                    FileNodeData(
                        name = "app.js",
                        path = "$path/js/app.js",
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
    // Browser doesn't have direct file system access
    // Return the same mock data
    return scanDirectory(path)
}

/**
 * Wasm mock implementation - always returns true for directories
 */
actual fun directoryHasChildren(path: String): Boolean {
    // Mock - assume directories have children
    return true
}

// showHidden variants — the Wasm mock has no hidden entries, so they delegate

actual fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? = scanDirectory(path)

actual suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int, showHidden: Boolean): FileNodeData? =
    scanDirectoryWithDepth(path, maxDepth, startDepth)

actual fun directoryHasChildren(path: String, showHidden: Boolean): Boolean = directoryHasChildren(path)
