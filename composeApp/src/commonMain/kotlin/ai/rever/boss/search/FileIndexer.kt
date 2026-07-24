package ai.rever.boss.search

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
// Note: Using java.io.File in commonMain is acceptable here because BOSS is a desktop-only
// application (see CLAUDE.md). This avoids unnecessary abstraction for a single-platform target.
import java.io.File

private val logger = BossLogger.forComponent("FileIndexer")

/**
 * Background file indexer for the global search feature.
 *
 * Scans project directories and maintains a flat list of indexed files
 * for fast searching. Excludes common non-essential directories like
 * build outputs, node_modules, and hidden files.
 *
 * Thread-safe: Uses a mutex to prevent concurrent indexing operations.
 */
class FileIndexer {

    /** Mutex to ensure only one indexing operation runs at a time. */
    private val indexingMutex = Mutex()

    private val _indexedFiles = MutableStateFlow<List<IndexedFile>>(emptyList())
    val indexedFiles: StateFlow<List<IndexedFile>> = _indexedFiles.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexedPath = MutableStateFlow<String?>(null)
    val indexedPath: StateFlow<String?> = _indexedPath.asStateFlow()

    /**
     * Directories to exclude from indexing.
     */
    private val excludedDirectories = setOf(
        ".git",
        ".idea",
        ".gradle",
        ".svn",
        ".hg",
        "build",
        "out",
        "target",
        "node_modules",
        "vendor",
        "__pycache__",
        ".cache",
        "dist",
        "coverage",
        ".next",
        ".nuxt",
        ".venv",
        "venv",
        "env",
        ".env"
    )

    /**
     * File extensions to include in indexing.
     * If empty, all non-hidden files are included.
     */
    private val includedExtensions = emptySet<String>() // Include all files

    /**
     * Maximum directory depth for recursive scanning.
     * Reduced from 20 to 15 to balance coverage vs performance for deeply nested projects.
     * Most source files are within 10 levels; 15 provides buffer for monorepos.
     */
    private val maxDepth = 15

    /**
     * Index all files in the given project path.
     *
     * Thread-safe: Uses mutex to prevent concurrent indexing operations.
     *
     * @param projectPath The root directory to index
     * @param forceReindex If true, re-index even if already indexed
     */
    suspend fun indexProject(projectPath: String, forceReindex: Boolean = false) {
        // Try to acquire lock without blocking - if already indexing, skip
        if (!indexingMutex.tryLock()) {
            logger.debug(LogCategory.FILE, "Index already in progress, skipping")
            return
        }

        try {
            // Check if already indexed (inside lock to prevent race)
            if (!forceReindex && _indexedPath.value == projectPath && _indexedFiles.value.isNotEmpty()) {
                logger.debug(LogCategory.FILE, "Project already indexed", mapOf("path" to projectPath))
                return
            }

            _isIndexing.value = true

            logger.info(LogCategory.FILE, "Starting file indexing", mapOf("path" to projectPath))
            val startTime = System.currentTimeMillis()

            val files = withContext(Dispatchers.IO) {
                scanProjectFiles(projectPath)
            }

            _indexedFiles.value = files
            _indexedPath.value = projectPath

            val elapsed = System.currentTimeMillis() - startTime
            logger.info(
                LogCategory.FILE,
                "File indexing complete",
                mapOf(
                    "path" to projectPath,
                    "fileCount" to files.size,
                    "elapsedMs" to elapsed
                )
            )
        } catch (e: Exception) {
            logger.error(LogCategory.FILE, "Error indexing project", error = e)
        } finally {
            _isIndexing.value = false
            indexingMutex.unlock()
        }
    }

    /**
     * Clear the current index.
     */
    fun clearIndex() {
        _indexedFiles.value = emptyList()
        _indexedPath.value = null
        logger.debug(LogCategory.FILE, "Index cleared")
    }

    /**
     * Scan all files in the project directory recursively.
     */
    private fun scanProjectFiles(projectPath: String): List<IndexedFile> {
        val rootDir = File(projectPath)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            logger.warn(LogCategory.FILE, "Invalid project path", mapOf("path" to projectPath))
            return emptyList()
        }

        val files = mutableListOf<IndexedFile>()
        // Use canonical path for consistent path comparison and symlink resolution
        val rootCanonicalPath = rootDir.canonicalPath
        val rootPathLength = rootCanonicalPath.length + 1 // +1 for trailing separator

        scanDirectory(rootDir, rootCanonicalPath, rootPathLength, files)

        return files.sortedBy { it.lowerName }
    }

    /**
     * Recursively scan a directory and add files to the list.
     *
     * Security: Validates that all indexed files remain within the project root
     * to prevent path traversal via symlinks.
     */
    private fun scanDirectory(
        dir: File,
        rootCanonicalPath: String,
        rootPathLength: Int,
        files: MutableList<IndexedFile>,
        depth: Int = 0
    ) {
        // Limit depth to prevent extremely deep traversal
        if (depth > maxDepth) return

        val children = dir.listFiles() ?: return

        for (child in children) {
            val name = child.name

            // Skip hidden files and directories
            if (name.startsWith(".")) continue

            // Skip excluded directories
            if (child.isDirectory && name in excludedDirectories) continue

            // Security: Ensure file/directory is within project root (prevents symlink escape)
            val childCanonicalPath = try {
                child.canonicalPath
            } catch (e: Exception) {
                // Skip files we can't resolve (broken symlinks, permission issues)
                logger.debug(
                    LogCategory.FILE,
                    "Skipping unresolvable path",
                    mapOf("path" to child.path, "error" to e.toString()),
                )
                continue
            }
            if (!childCanonicalPath.startsWith(rootCanonicalPath)) {
                logger.debug(LogCategory.FILE, "Skipping path outside project root",
                    mapOf("path" to child.path, "canonical" to childCanonicalPath))
                continue
            }

            if (child.isDirectory) {
                // Recurse into subdirectory
                scanDirectory(child, rootCanonicalPath, rootPathLength, files, depth + 1)
            } else {
                // Check extension filter if enabled
                if (includedExtensions.isNotEmpty()) {
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension !in includedExtensions) continue
                }

                // Add file to index
                val absolutePath = child.absolutePath
                val relativePath = if (absolutePath.length > rootPathLength) {
                    absolutePath.substring(rootPathLength)
                } else {
                    name
                }

                files.add(
                    IndexedFile(
                        name = name,
                        path = absolutePath,
                        relativePath = relativePath
                    )
                )
            }
        }
    }

    /**
     * Get the count of indexed files.
     */
    fun getFileCount(): Int = _indexedFiles.value.size
}
