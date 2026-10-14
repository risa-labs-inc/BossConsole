package ai.rever.boss.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds the one Spotlight file index retained by a window between dialog sessions.
 *
 * File contents are derived solely from a project path, unlike a dialog's query, results, category,
 * and selection. Retaining one path-index pair keeps reopening Spotlight warm without retaining a growing
 * collection of projects: the bound is one index per open window, including separate indexes for
 * windows on the same project. Replacing the project cancels its scan and clears the old index.
 * Access is confined to the window's composition thread. Scans belong to the window scope, so
 * closing a dialog does not abandon a scan that a reopened dialog still needs.
 */
internal class SpotlightFileIndexOwner(
    private val scope: CoroutineScope,
    private val createIndexer: () -> FileIndexer = { FileIndexer() },
) {
    private var projectPath: String? = null
    private var fileIndexer: FileIndexer? = null
    private var indexingJob: Job? = null

    fun indexerFor(nextProjectPath: String): FileIndexer {
        fileIndexer?.takeIf { projectPath == nextProjectPath }?.let { return it }

        indexingJob?.cancel()
        indexingJob = null
        fileIndexer?.clearIndex()
        return createIndexer().also {
            projectPath = nextProjectPath
            fileIndexer = it
        }
    }

    fun ensureIndexed(projectPath: String) {
        if (projectPath.isBlank()) return
        val indexer = indexerFor(projectPath)
        if (indexingJob?.isActive == true) return
        indexingJob = scope.launch { indexer.indexProject(projectPath) }
    }
}
