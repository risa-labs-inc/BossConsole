package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.FileNodeData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * LRU cache for file system nodes with dynamic loading
 */
class FileIndexCache(
    private val maxSize: Int = 1000,
    private val maxDepthInitial: Int = 2,
) {
    private val cache = mutableMapOf<String, CachedNode>()
    private val accessOrder = mutableListOf<String>()
    private val mutex = Mutex()

    data class CachedNode(
        val node: FileNodeData,
        var lastAccessed: Long = Clock.System.now().epochSeconds,
        var isFullyLoaded: Boolean = false,
        var loadDepth: Int = 0,
    )

    suspend fun getNode(
        path: String,
        forceReload: Boolean = false,
    ): FileNodeData? =
        mutex.withLock {
            if (!forceReload) {
                cache[path]?.let { cached ->
                    // Update access order
                    accessOrder.remove(path)
                    accessOrder.add(0, path)
                    cached.lastAccessed = Clock.System.now().epochSeconds
                    return cached.node
                }
            }

            // Load node from file system
            val node = scanDirectory(path)

            node?.let {
                addToCache(path, it, maxDepthInitial)
            }

            return node
        }

    private fun addToCache(
        path: String,
        node: FileNodeData,
        depth: Int,
    ) {
        // Evict old entries if needed
        while (cache.size >= maxSize && accessOrder.isNotEmpty()) {
            val oldestPath = accessOrder.removeLast()
            cache.remove(oldestPath)
        }

        cache[path] = CachedNode(node, loadDepth = depth)
        accessOrder.add(0, path)

        // Also cache child directories for quick access
        if (node.isDirectory && depth > 0) {
            node.children.forEach { child ->
                if (child.isDirectory && !cache.containsKey(child.path)) {
                    addToCache(child.path, child, depth - 1)
                }
            }
        }
    }

    suspend fun clearCache() =
        mutex.withLock {
            cache.clear()
            accessOrder.clear()
        }
}

// Platform-specific implementation with depth control - uses plugin types
expect suspend fun scanDirectoryWithDepth(
    path: String,
    maxDepth: Int,
    startDepth: Int,
): FileNodeData?

/**
 * [scanDirectoryWithDepth] variant that can include hidden (dot) entries.
 */
expect suspend fun scanDirectoryWithDepth(
    path: String,
    maxDepth: Int,
    startDepth: Int,
    showHidden: Boolean,
): FileNodeData?
