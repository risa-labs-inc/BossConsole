package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val recentFilesLogger = BossLogger.forComponent("RecentFilesManager")

/**
 * Data class representing a recently opened file.
 */
@Serializable
data class RecentFile(
    val path: String,
    val name: String,
    val lastOpened: Long,
    val projectPath: String? = null,
)

/**
 * Container for recent files data with serialization support.
 */
@Serializable
data class RecentFilesData(
    val files: List<RecentFile> = emptyList(),
)

/**
 * Manages recently opened files for the Dashboard.
 * Persists to ~/.boss/recent-files.json
 *
 * Thread-safe: All file I/O operations run on Dispatchers.IO.
 * Uses StateFlow for reactive UI updates.
 */
object RecentFilesManager {
    private const val MAX_FILES = 20
    private const val SAVE_DEBOUNCE_MS = 5000L // Debounce saves to max once per 5 seconds
    private val settingsFile = BossDirectories.resolve("recent-files.json")
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null

    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()

    init {
        scope.launch {
            loadAsync()
        }
    }

    /**
     * Load recent files from disk asynchronously.
     */
    private suspend fun loadAsync() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()

                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    val data = json.decodeFromString<RecentFilesData>(content)
                    _recentFiles.value = data.files
                    recentFilesLogger.debug(LogCategory.FILE, "Loaded recent files", mapOf("count" to data.files.size))
                }
            } catch (e: Exception) {
                recentFilesLogger.warn(LogCategory.FILE, "Error loading recent files", error = e)
            }
        }

    /**
     * Save recent files to disk with debouncing.
     * Cancels any pending save and schedules a new one after SAVE_DEBOUNCE_MS.
     */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveImmediately()
            }
    }

    /**
     * Immediately save recent files to disk (bypasses debounce).
     */
    private suspend fun saveImmediately() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                val data = RecentFilesData(files = _recentFiles.value)
                val content = json.encodeToString(RecentFilesData.serializer(), data)
                settingsFile.writeText(content)
            } catch (e: Exception) {
                recentFilesLogger.warn(LogCategory.FILE, "Error saving recent files", error = e)
            }
        }

    /**
     * Record a file open event.
     * Moves the file to the top if already present, otherwise adds it.
     * Maintains max file limit.
     *
     * @param filePath Absolute path to the file
     * @param projectPath Optional project path the file belongs to
     */
    fun recordFileOpen(
        filePath: String,
        projectPath: String? = null,
    ) {
        scope.launch {
            val fileName = filePath.extractFileName()
            val newFile =
                RecentFile(
                    path = filePath,
                    name = fileName,
                    lastOpened = System.currentTimeMillis(),
                    projectPath = projectPath,
                )

            // Remove existing entry for this path and add to front
            val currentFiles = _recentFiles.value.toMutableList()
            currentFiles.removeAll { it.path == filePath }
            currentFiles.add(0, newFile)

            // Trim to max size
            _recentFiles.value = currentFiles.take(MAX_FILES)
            scheduleSave()
        }
    }

    /**
     * Remove a specific file from recent history.
     */
    fun removeFile(filePath: String) {
        scope.launch {
            _recentFiles.value = _recentFiles.value.filter { it.path != filePath }
            scheduleSave()
        }
    }

    /**
     * Clear all recent files.
     */
    fun clearAll() {
        scope.launch {
            _recentFiles.value = emptyList()
            scheduleSave()
        }
    }

    /**
     * Check if a file still exists on disk.
     */
    fun fileExists(filePath: String): Boolean = File(filePath).exists()
}
