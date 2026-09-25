package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Global project state with persistence - manages shared recent projects list
// Note: Selected project is per-window via WindowProjectState. This object only manages recent projects.
object ProjectState {
    private val logger = BossLogger.forComponent("ProjectState")
    private const val MAX_RECENT_PROJECTS = 10
    private const val RECENT_PROJECTS_FILE = "recent-projects.json"

    // Recent projects list - loaded from disk on init (shared across all windows)
    private val _recentProjects = MutableStateFlow<List<Project>>(emptyList())
    val recentProjects: StateFlow<List<Project>> = _recentProjects.asStateFlow()

    /**
     * Serialises read-modify-write over [_recentProjects]. Per-window project states both reach
     * this object from `WindowProjectStateRegistry.hostProjectCallback` and the same call lands
     * for every editor that restores at startup - two windows opening projects together used to
     * race on the read here, with the later write silently dropping the first project's entry.
     *
     * A `ReentrantLock`, not a `Mutex`: every public mutator is a non-suspending function that
     * runs the mutation under this lock and returns with the in-memory list updated, so a caller
     * (or the test that reads `recentProjects.value` immediately after `selectProject`) sees the
     * new entry on the next instruction. The lock is held only for the in-memory mutation; the
     * debounced disk save is scheduled OUTSIDE the lock so a slow writer cannot hold callers up.
     *
     * Mirrors the form `RecentFilesManager.mutationLock` already uses, so the two histories
     * cannot drift; `MutableStateFlow.update` is not usable because the CAS lambda is retried
     * and a mutation must read exactly once.
     */
    private val mutationLock = ReentrantLock()

    /**
     * Holds the scheduled save jobs so a remove/update racing a still-pending save cannot drop
     * it; the `saveJob?.cancel(); saveJob = scope.launch { ... }` form RecentFilesManager uses
     * works because it is fed from a single coroutine, here every per-window callback writes
     * one.
     */
    private val saveJobLock = Any()
    private var saveJob: kotlinx.coroutines.Job? = null

    /**
     * Process-wide scope for async persistence; `SupervisorJob` so a single failed save does
     * not cancel the load-on-init that races it. Owned for the lifetime of the JVM - this is
     * an `object`, so there is no caller that could cancel it.
     */
    private val ioScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
        )

    init {
        // Load recent projects from disk on startup (async to avoid blocking main thread)
        ioScope.launch {
            loadRecentProjects()
        }
    }

    /**
     * Reset manager state for hermetic unit testing and redirect [settingsFile] to [testFile].
     * Cancels the init load and any pending debounced save, clears the recorded list, and
     * re-runs the load so the state matches [testFile]. Tests must call this again with the
     * real path before finishing, so the singleton is left where the app and other tests
     * expect it. Mirrors [ai.rever.boss.dashboard.RecentFilesManager.resetForTesting].
     */
    internal suspend fun resetForTesting(testFile: java.io.File) {
        synchronized(saveJobLock) {
            saveJob?.cancel()
            saveJob = null
        }
        settingsFile = testFile
        mutationLock.withLock {
            _recentProjects.value = emptyList()
        }
        loadRecentProjects()
    }

    /**
     * Remove a project from the recent projects list.
     */
    fun removeRecentProject(projectPath: String) {
        val changed =
            mutationLock.withLock {
                val before = _recentProjects.value
                val after = before.filter { it.path != projectPath }
                if (after == before) {
                    false
                } else {
                    _recentProjects.value = after
                    true
                }
            }
        if (changed) scheduleSave()
    }

    /**
     * Update recent projects list without changing the global selected project.
     * Called by per-window project states when they select a project.
     */
    fun updateRecentProjects(project: Project) {
        val updatedProject = project.copy(lastOpened = System.currentTimeMillis())
        val changed =
            mutationLock.withLock {
                val before = _recentProjects.value
                val after =
                    before.toMutableList().apply {
                        removeAll { it.path == updatedProject.path }
                        add(0, updatedProject)
                        while (size > MAX_RECENT_PROJECTS) removeLast()
                    }
                if (after == before) {
                    false
                } else {
                    _recentProjects.value = after
                    true
                }
            }
        if (changed) scheduleSave()
    }

    /**
     * Redirected by [resetForTesting] for hermetic unit tests; production code never reassigns
     * it, the same way the sibling RecentFilesManager does.
     */
    internal var settingsFile: java.io.File =
        java.io.File(ai.rever.boss.plugin.pathutils.BossDirectories.rootDir, RECENT_PROJECTS_FILE)

    private fun getRecentProjectsFile(): java.io.File {
        val bossDir = settingsFile.parentFile
        if (bossDir != null && !bossDir.exists()) bossDir.mkdirs()
        return settingsFile
    }

    /**
     * Debounced save: cancel any pending save and schedule a fresh one. Coalesces a burst of
     * updates from concurrent windows into one disk write. The cancel-then-launch pair is
     * inside [saveJobLock] because callers arrive from concurrent coroutines, and an unsynchronised
     * pair could overwrite the reference to a still-pending job and leave a timer nothing will
     * cancel.
     */
    private fun scheduleSave() {
        synchronized(saveJobLock) {
            saveJob?.cancel()
            saveJob =
                ioScope.launch {
                    kotlinx.coroutines.delay(SAVE_DEBOUNCE_MS)
                    saveImmediately()
                }
        }
    }

    private suspend fun saveImmediately() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // The recorded list, never any local copy: a transform that ran under mutationLock
            // has just published it to _recentProjects.
            val snapshot = _recentProjects.value
            try {
                val json =
                    kotlinx.serialization.json.Json
                        .encodeToString(snapshot)
                // Atomic: `writeText` truncates the target and then streams into it, so a crash
                // or a second writer arriving mid-write leaves JSON that fails to parse and the
                // load path drops every entry rather than one. atomicWriteText writes a unique
                // sibling temp and moves it into place.
                getRecentProjectsFile().atomicWriteText(json)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to save recent projects", error = e)
            }
        }

    /**
     * Write any debounced save out now. Called from the shutdown sequence: a project opened or
     * removed within [SAVE_DEBOUNCE_MS] of quitting would otherwise be lost, the exact window
     * `RecentFilesManager.flushPendingSaves` was added for. Same idiom: cancel the pending job
     * under [saveJobLock], and only then write - a cancelled job can still be mid-flight, so
     * the lock is what makes "cancelled" mean "will not write after us".
     */
    suspend fun flushPendingSaves() {
        val pending =
            synchronized(saveJobLock) {
                val active = saveJob?.isActive == true
                saveJob?.cancel()
                saveJob = null
                active
            }
        if (pending) saveImmediately()
    }

    private const val SAVE_DEBOUNCE_MS = 5000L

    private suspend fun loadRecentProjects() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = getRecentProjectsFile()
                if (file.exists()) {
                    // Keep the disk snapshot and its publication in one critical section. Locking
                    // only the final assignment still lets an update land after read and before
                    // publish, where the disk snapshot would overwrite that newer in-memory entry.
                    val (validProjects, originalCount) =
                        mutationLock.withLock {
                            val json = file.readText()
                            val projects =
                                kotlinx.serialization.json.Json
                                    .decodeFromString<List<Project>>(json)

                            // Filter out projects whose directories no longer exist AND normalize names
                            val validProjects =
                                projects.mapNotNull { project ->
                                    val projectDir = java.io.File(project.path)
                                    val exists = projectDir.exists() && projectDir.isDirectory
                                    if (!exists) {
                                        logger.debug(
                                            LogCategory.FILE,
                                            "Removing deleted project from recent",
                                            mapOf(
                                                "name" to project.name,
                                                "path" to project.path,
                                            ),
                                        )
                                        null
                                    } else {
                                        // Normalize the name to handle any legacy full paths
                                        val normalizedName = project.path.extractFileName()
                                        if (normalizedName != project.name) {
                                            logger.debug(
                                                LogCategory.FILE,
                                                "Normalizing project name",
                                                mapOf("old" to project.name, "new" to normalizedName),
                                            )
                                        }
                                        project.copy(name = normalizedName)
                                    }
                                }

                            // A mutation that landed before this load must not be lost to the older
                            // disk snapshot (the debounced save has not flushed it yet): in-memory
                            // entries win, the disk fills in everything else. A removal in the same
                            // narrow window would resurrect its entry - accepted: it is re-removed by
                            // the next mutation, and losing an addition silently was worse.
                            _recentProjects.value =
                                (_recentProjects.value + validProjects)
                                    .distinctBy { it.path }
                                    .take(MAX_RECENT_PROJECTS)
                            validProjects to projects.size
                        }
                    logger.debug(
                        LogCategory.FILE,
                        "Loaded recent projects from disk",
                        mapOf(
                            "count" to validProjects.size,
                            "removed" to (originalCount - validProjects.size),
                        ),
                    )

                    // Save cleaned list if any projects were removed
                    if (validProjects.size < originalCount) {
                        saveRecentProjects()
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to load recent projects", error = e)
            }
        }

    private suspend fun saveRecentProjects() = saveImmediately()
}

// Note: CodeBaseComponent has been moved to plugin-panel-codebase module
// The legacy component code has been removed - use CodeBasePanelPlugin.registerWithProviders() instead

/** Directory names never shown in file trees, hidden toggle or not. */
internal val scannerSkippedDirectoryNames = setOf("build", "node_modules")

/**
 * Shared visibility filter for the platform scanners — hoisted so the
 * desktop/android actuals can't drift apart on the filter or skip-list.
 */
internal fun isVisibleScanEntry(
    name: String,
    showHidden: Boolean,
): Boolean = (showHidden || !name.startsWith(".")) && name !in scannerSkippedDirectoryNames

// Platform-specific file scanning - uses plugin types
expect fun scanDirectory(path: String): FileNodeData?

/**
 * [scanDirectory] variant that can include hidden (dot) entries.
 * `build`/`node_modules` stay skipped regardless of the flag.
 */
expect fun scanDirectory(
    path: String,
    showHidden: Boolean,
): FileNodeData?

/**
 * IntelliJ's isAlwaysShowPlus() pattern implementation.
 * Quick check if a directory has any children without loading them all.
 * This is much faster than scanning the full directory.
 */
expect fun directoryHasChildren(path: String): Boolean

/**
 * [directoryHasChildren] variant that can also count hidden (dot) entries.
 */
expect fun directoryHasChildren(
    path: String,
    showHidden: Boolean,
): Boolean
