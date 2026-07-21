package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.NodeLoadingStateData
import java.io.File

actual fun scanDirectory(path: String): FileNodeData? = scanDirectory(path, showHidden = false)

actual fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? {
    val file = File(path)
    if (!file.exists()) return null

    // Initial scan is shallow - only immediate children
    return scanFileRecursively(file, maxDepth = 1, showHidden = showHidden)
}

actual suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int): FileNodeData? =
    scanDirectoryWithDepth(path, maxDepth, startDepth, showHidden = false)

actual suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int, showHidden: Boolean): FileNodeData? {
    val file = File(path)
    if (!file.exists()) return null

    return scanFileRecursively(file, currentDepth = startDepth, maxDepth = maxDepth, showHidden = showHidden)
}

/**
 * IntelliJ's isAlwaysShowPlus() pattern implementation.
 * Quick check if a directory has any visible children without loading them all.
 */
actual fun directoryHasChildren(path: String): Boolean = directoryHasChildren(path, showHidden = false)

actual fun directoryHasChildren(path: String, showHidden: Boolean): Boolean {
    val file = File(path)
    if (!file.exists() || !file.isDirectory) return false

    val children = file.listFiles() ?: return false
    return children.any { child -> isVisibleScanEntry(child.name, showHidden) }
}

private fun scanFileRecursively(file: File, currentDepth: Int = 0, maxDepth: Int = 5, showHidden: Boolean = false): FileNodeData {
    val isDirectory = file.isDirectory
    val shouldLoadChildren = isDirectory && currentDepth < maxDepth

    val children: List<FileNodeData> = if (shouldLoadChildren) {
        file.listFiles()
            ?.filter { isVisibleScanEntry(it.name, showHidden) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { childFile ->
                if (childFile.isDirectory && currentDepth + 1 >= maxDepth) {
                    val hasKids = directoryHasChildren(childFile.absolutePath, showHidden)
                    FileNodeData(
                        name = childFile.name,
                        path = childFile.absolutePath,
                        isDirectory = true,
                        children = emptyList(),
                        hasChildren = hasKids,
                        loadingState = NodeLoadingStateData.UNKNOWN,
                        loadDepth = currentDepth + 1
                    )
                } else {
                    scanFileRecursively(childFile, currentDepth + 1, maxDepth, showHidden)
                }
            }
            ?: emptyList()
    } else {
        emptyList()
    }

    val loadingState = when {
        !isDirectory -> NodeLoadingStateData.LOADED
        currentDepth >= maxDepth - 1 -> NodeLoadingStateData.UNKNOWN
        else -> NodeLoadingStateData.LOADED
    }

    return FileNodeData(
        name = file.name,
        path = file.absolutePath,
        isDirectory = isDirectory,
        children = children,
        hasChildren = if (isDirectory) children.isNotEmpty() || directoryHasChildren(file.absolutePath, showHidden) else false,
        loadingState = loadingState,
        loadDepth = currentDepth
    )
}
