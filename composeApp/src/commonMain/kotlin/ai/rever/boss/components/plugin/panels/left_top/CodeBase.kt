package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.project.RecentProjectHistory
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import kotlinx.coroutines.flow.StateFlow

// Global project state with persistence - manages shared recent projects list
// Note: Selected project is per-window via WindowProjectState. This object only manages recent projects.
object ProjectState {
    private val logger = BossLogger.forComponent("ProjectState")
    private const val RECENT_PROJECTS_FILE = "recent-projects.json"
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    private val history = RecentProjectHistory(ioScope, ::loadRecentProjects, ::saveRecentProjects)
    val recentProjects: StateFlow<List<Project>> = history.recentProjects

    fun removeRecentProject(projectPath: String) = history.removeRecentProject(projectPath)

    fun updateRecentProjects(project: Project) = history.updateRecentProjects(project)

    private fun getRecentProjectsFile(): java.io.File {
        val bossDir = ai.rever.boss.plugin.pathutils.BossDirectories.rootDir
        if (!bossDir.exists()) bossDir.mkdirs()
        return java.io.File(bossDir, RECENT_PROJECTS_FILE)
    }

    private suspend fun loadRecentProjects(): List<Project>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = getRecentProjectsFile()
                if (file.exists()) {
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

                    logger.debug(
                        LogCategory.FILE,
                        "Loaded recent projects from disk",
                        mapOf(
                            "count" to validProjects.size,
                            "removed" to (projects.size - validProjects.size),
                        ),
                    )

                    validProjects
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to load recent projects", error = e)
                null
            }
        }

    private suspend fun saveRecentProjects(projects: List<Project>) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = getRecentProjectsFile()
                val json =
                    kotlinx.serialization.json.Json
                        .encodeToString(projects)
                file.writeText(json)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to save recent projects", error = e)
            }
        }
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
