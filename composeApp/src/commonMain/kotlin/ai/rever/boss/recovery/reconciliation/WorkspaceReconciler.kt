package ai.rever.boss.recovery.reconciliation

import ai.rever.boss.recovery.baseline.WorkspaceBaselineCapturer
import ai.rever.boss.recovery.models.CheckpointManifest
import ai.rever.boss.recovery.models.MissionBaseline
import ai.rever.boss.recovery.models.RecoveryResult
import ai.rever.boss.recovery.models.WorkspaceCheckpoint
import ai.rever.boss.recovery.paths.SafePathResolver
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Reconciles the active workspace against a target [WorkspaceCheckpoint].
 *
 * INVARIANTS:
 * - Pre-existing user files captured in [MissionBaseline] are preserved.
 * - Files created by the mission after [targetCheckpoint] are safely removed.
 * - Files modified by the mission are restored to their [targetCheckpoint] state.
 * - Concurrent external modifications that conflict with the rollback are flagged as [RecoveryResult.Conflict].
 * - Partial failures (e.g., Windows file locks) produce [RecoveryResult.PartialFailure] instead of silent success.
 */
object WorkspaceReconciler {

    /**
     * Executes a bounded rewind of [projectRoot] to [targetCheckpoint].
     */
    suspend fun rewind(
        baseline: MissionBaseline,
        targetCheckpoint: WorkspaceCheckpoint,
        projectRoot: File,
        storage: WorkspaceCheckpointStorage,
        allowOverwriteConflicts: Boolean = false,
    ): RecoveryResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val rootCanonical = SafePathResolver.canonicalRoot(projectRoot)
            val snapshotFilesDir = storage.getSnapshotFilesDir(targetCheckpoint.missionId, targetCheckpoint.checkpointId)

            if (!snapshotFilesDir.exists()) {
                return@withContext RecoveryResult.InvalidCheckpoint(
                    checkpointId = targetCheckpoint.checkpointId,
                    reason = "Snapshot files directory missing: ${snapshotFilesDir.path}",
                )
            }

            val targetManifest = targetCheckpoint.manifest
            val excludedPatterns = targetManifest.excludedPatterns

            // Step 1: Scan current workspace files
            val currentFiles = mutableMapOf<String, File>()
            val currentHashes = mutableMapOf<String, String>()

            fun scanCurrent(dir: File, relDir: String) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    val childName = child.name
                    if (SafePathResolver.isExcluded(childName, excludedPatterns)) {
                        continue
                    }
                    val relPath = if (relDir.isEmpty()) childName else "$relDir/$childName"
                    val normalizedRel = SafePathResolver.normalizeRelativePath(relPath)

                    if (child.isDirectory) {
                        if (SafePathResolver.isSafeDirectoryToRecurse(child, rootCanonical)) {
                            scanCurrent(child, normalizedRel)
                        }
                    } else if (child.isFile) {
                        if (SafePathResolver.isContainedFile(child, rootCanonical)) {
                            currentFiles[normalizedRel] = child
                            currentHashes[normalizedRel] = WorkspaceBaselineCapturer.calculateSha256(child)
                        }
                    }
                }
            }
            scanCurrent(rootCanonical, "")

            // Step 2: Conflict Detection
            // Detect unresolvable conflicts (e.g. baseline files modified on disk that are missing from the target checkpoint)
            if (!allowOverwriteConflicts) {
                val conflictingFiles = mutableListOf<String>()
                for ((relPath, currentHash) in currentHashes) {
                    val targetMeta = targetManifest.files[relPath]
                    val baselineMeta = baseline.baselineFiles[relPath]

                    // Baseline file missing in target checkpoint, but modified on disk compared to baseline
                    if (baselineMeta != null && targetMeta == null && currentHash != baselineMeta.sha256) {
                        conflictingFiles.add(relPath)
                    }
                }
                if (conflictingFiles.isNotEmpty()) {
                    return@withContext RecoveryResult.Conflict(
                        checkpointId = targetCheckpoint.checkpointId,
                        conflictingFiles = conflictingFiles,
                        reason = "Baseline file(s) modified on disk are missing from target checkpoint: ${conflictingFiles.joinToString()}",
                    )
                }
            }

            // Step 3: Remove untracked files added by the mission after checkpoint
            // Files in current workspace that are NOT in targetCheckpoint AND NOT in baseline
            var removedCount = 0
            val failedFiles = mutableMapOf<String, String>()
            val restoredFiles = mutableListOf<String>()

            for ((relPath, currentFile) in currentFiles) {
                val inCheckpoint = targetManifest.files.containsKey(relPath)
                val inBaseline = baseline.baselineFiles.containsKey(relPath)

                if (!inCheckpoint && !inBaseline) {
                    // This is an untracked file created by the agent during the mission after baseline
                    try {
                        if (currentFile.delete()) {
                            removedCount++
                            // Clean up empty parent directories
                            var parent = currentFile.parentFile
                            while (parent != null && parent != rootCanonical && (parent.listFiles()?.isEmpty() == true)) {
                                parent.delete()
                                parent = parent.parentFile
                            }
                        } else {
                            failedFiles[relPath] = "Failed to delete mission-added file (file may be locked)"
                        }
                    } catch (e: Exception) {
                        failedFiles[relPath] = "Exception while deleting: ${e.message}"
                    }
                }
            }

            // Step 4: Restore files from target checkpoint
            for ((relPath, targetMeta) in targetManifest.files) {
                val currentHash = currentHashes[relPath]
                if (currentHash == targetMeta.sha256) {
                    // File is already identical, no I/O needed
                    restoredFiles.add(relPath)
                    continue
                }

                val sourceSnapshotFile = File(snapshotFilesDir, relPath)
                if (!sourceSnapshotFile.exists()) {
                    failedFiles[relPath] = "Snapshot source blob missing from storage"
                    continue
                }

                try {
                    val destinationFile = SafePathResolver.resolveSafeChild(rootCanonical, relPath)
                    destinationFile.parentFile?.mkdirs()

                    sourceSnapshotFile.copyTo(destinationFile, overwrite = true)
                    restoredFiles.add(relPath)
                } catch (e: Exception) {
                    failedFiles[relPath] = "Failed to restore file: ${e.message}"
                }
            }

            val durationMs = System.currentTimeMillis() - startTime

            return@withContext if (failedFiles.isNotEmpty()) {
                RecoveryResult.PartialFailure(
                    checkpointId = targetCheckpoint.checkpointId,
                    restoredFiles = restoredFiles,
                    failedFiles = failedFiles,
                    reason = "Rewind failed on ${failedFiles.size} file(s)",
                )
            } else {
                RecoveryResult.Success(
                    checkpointId = targetCheckpoint.checkpointId,
                    restoredFilesCount = restoredFiles.size,
                    removedFilesCount = removedCount,
                    durationMs = durationMs,
                )
            }
        }
}
