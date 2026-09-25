package ai.rever.boss.search

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Background filename index for global search. Project discovery is shared with content search,
 * so ignore rules, default exclusions, link confinement, budgets, and cancellation agree.
 */
class FileIndexer(
    private val scan: (suspend (String) -> List<IndexedFile>)? = null,
) {
    private val logger = BossLogger.forComponent("FileIndexer")
    private val indexingMutex = Mutex()

    private val _indexedFiles = MutableStateFlow<List<IndexedFile>>(emptyList())
    val indexedFiles: StateFlow<List<IndexedFile>> = _indexedFiles.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexedPath = MutableStateFlow<String?>(null)
    val indexedPath: StateFlow<String?> = _indexedPath.asStateFlow()

    /** Non-null when indexing refused to publish a partial or otherwise failed snapshot. */
    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError.asStateFlow()

    suspend fun indexProject(
        projectPath: String,
        forceReindex: Boolean = false,
    ) {
        indexingMutex.withLock {
            try {
                if (!forceReindex && _indexedPath.value == projectPath && _indexedFiles.value.isNotEmpty()) {
                    _indexError.value = null
                    logger.debug(LogCategory.FILE, "Project already indexed", mapOf("path" to projectPath))
                    return@withLock
                }
                _isIndexing.value = true
                _indexError.value = null
                logger.info(LogCategory.FILE, "Starting file indexing", mapOf("path" to projectPath))
                val startTime = System.currentTimeMillis()
                val discovery =
                    scan?.invoke(projectPath)
                        ?: withContext(Dispatchers.IO) {
                            val result = ProjectFileDiscovery.discover(projectPath)
                            if (result.incompleteReason != null) {
                                throw ProjectDiscoveryIncompleteException(result.incompleteReason)
                            }
                            result.files
                                .map { IndexedFile(it.file.name, it.file.absolutePath, it.relativePath) }
                                .sortedBy { it.lowerName }
                        }
                _indexedFiles.value = discovery
                _indexedPath.value = projectPath
                logger.info(
                    LogCategory.FILE,
                    "File indexing complete",
                    mapOf(
                        "path" to projectPath,
                        "fileCount" to discovery.size,
                        "elapsedMs" to System.currentTimeMillis() - startTime,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _indexedFiles.value = emptyList()
                _indexedPath.value = null
                _indexError.value = e.message ?: "indexing failed"
                logger.error(LogCategory.FILE, "Error indexing project", error = e)
            } finally {
                _isIndexing.value = false
            }
        }
    }

    fun clearIndex() {
        _indexedFiles.value = emptyList()
        _indexedPath.value = null
        _indexError.value = null
        logger.debug(LogCategory.FILE, "Index cleared")
    }

    fun getFileCount(): Int = _indexedFiles.value.size
}
