package ai.rever.boss.recovery.reconciliation

import ai.rever.boss.recovery.baseline.WorkspaceBaselineCapturer
import ai.rever.boss.recovery.models.CheckpointManifest
import ai.rever.boss.recovery.models.MissionBaseline
import ai.rever.boss.recovery.models.RecoveryPlan
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
 * - Planning/preview is non-destructive (performs ZERO writes).
 */
object WorkspaceReconciler {
    /**
     * Internal scan representation containing files and their SHA-256 hashes.
     */
    private data class WorkspaceScan(
        val files: Map<String, File>,
        val hashes: Map<String, String>,
    )

    private fun scanWorkspace(
        rootCanonical: File,
        excludedPatterns: Set<String>,
    ): WorkspaceScan {
        val files = mutableMapOf<String, File>()
        val hashes = mutableMapOf<String, String>()

        fun scan(
            dir: File,
            relDir: String,
        ) {
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
                        scan(child, normalizedRel)
                    }
                } else if (child.isFile) {
                    if (SafePathResolver.isContainedFile(child, rootCanonical)) {
                        files[normalizedRel] = child
                        hashes[normalizedRel] = WorkspaceBaselineCapturer.calculateSha256(child)
                    }
                }
            }
        }

        scan(rootCanonical, "")
        return WorkspaceScan(files, hashes)
    }

    /**
     * Generates a dry-run [RecoveryPlan] detailing what files will be restored, removed, preserved,
     * or flagged as conflicting.
     *
     * INVARIANT: Performs ZERO destructive filesystem writes.
     */
    @Suppress("LongMethod") // Plan classification is one cohesive pass; splitting obscures the phases.
    suspend fun createPlan(
        baseline: MissionBaseline,
        targetCheckpoint: WorkspaceCheckpoint,
        projectRoot: File,
        storage: WorkspaceCheckpointStorage,
        allowOverwriteConflicts: Boolean = false,
    ): RecoveryPlan =
        withContext(Dispatchers.IO) {
            val rootCanonical = SafePathResolver.canonicalRoot(projectRoot)
            val snapshotFilesDir =
                storage.getSnapshotFilesDir(targetCheckpoint.missionId, targetCheckpoint.checkpointId)

            if (!snapshotFilesDir.exists()) {
                return@withContext RecoveryPlan(
                    checkpointId = targetCheckpoint.checkpointId,
                    filesToRestore = emptyList(),
                    filesToRemove = emptyList(),
                    filesToPreserve = emptyList(),
                    conflicts = emptyList(),
                    canRewind = false,
                    blockingReason = "Snapshot files directory missing: ${snapshotFilesDir.path}",
                )
            }

            val targetManifest = targetCheckpoint.manifest
            val scan = scanWorkspace(rootCanonical, targetManifest.excludedPatterns)

            // Step 1: Detect conflicts
            val conflictingFiles = mutableListOf<String>()
            for ((relPath, currentHash) in scan.hashes) {
                val targetMeta = targetManifest.files[relPath]
                val baselineMeta = baseline.baselineFiles[relPath]

                if (baselineMeta != null && targetMeta == null && currentHash != baselineMeta.sha256) {
                    conflictingFiles.add(relPath)
                }
            }

            // Step 2: Classify untracked files vs preserved files
            val filesToRemove = mutableListOf<String>()
            val filesToPreserve = mutableListOf<String>()

            for ((relPath, _) in scan.files) {
                val inCheckpoint = targetManifest.files.containsKey(relPath)
                val inBaseline = baseline.baselineFiles.containsKey(relPath)

                if (!inCheckpoint && !inBaseline) {
                    filesToRemove.add(relPath)
                } else {
                    filesToPreserve.add(relPath)
                }
            }

            // Step 3: Classify restore targets
            val filesToRestore = mutableListOf<String>()
            for ((relPath, targetMeta) in targetManifest.files) {
                val currentHash = scan.hashes[relPath]
                if (currentHash != targetMeta.sha256) {
                    filesToRestore.add(relPath)
                } else {
                    filesToPreserve.add(relPath)
                }
            }

            val hasConflicts = conflictingFiles.isNotEmpty() && !allowOverwriteConflicts
            val blockingReason =
                if (hasConflicts) {
                    "Baseline file(s) modified on disk are missing from target checkpoint: " +
                        "${conflictingFiles.joinToString()}"
                } else {
                    null
                }

            RecoveryPlan(
                checkpointId = targetCheckpoint.checkpointId,
                filesToRestore = filesToRestore.sorted(),
                filesToRemove = filesToRemove.sorted(),
                filesToPreserve = filesToPreserve.distinct().sorted(),
                conflicts = conflictingFiles.sorted(),
                canRewind = !hasConflicts,
                blockingReason = blockingReason,
            )
        }

    /**
     * Executes a bounded rewind of [projectRoot] to [targetCheckpoint].
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
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
            val snapshotFilesDir =
                storage.getSnapshotFilesDir(targetCheckpoint.missionId, targetCheckpoint.checkpointId)

            if (!snapshotFilesDir.exists()) {
                return@withContext RecoveryResult.InvalidCheckpoint(
                    checkpointId = targetCheckpoint.checkpointId,
                    reason = "Snapshot files directory missing: ${snapshotFilesDir.path}",
                )
            }

            // Generate dry-run plan first
            val plan = createPlan(baseline, targetCheckpoint, projectRoot, storage, allowOverwriteConflicts)
            if (!plan.canRewind) {
                return@withContext RecoveryResult.Conflict(
                    checkpointId = targetCheckpoint.checkpointId,
                    conflictingFiles = plan.conflicts,
                    reason = plan.blockingReason ?: "Rewind blocked by conflicts",
                )
            }

            val scan = scanWorkspace(rootCanonical, targetCheckpoint.manifest.excludedPatterns)
            var removedCount = 0
            val failedFiles = mutableMapOf<String, String>()
            val restoredFiles = mutableListOf<String>()

            // Step 1: Remove mission-added untracked files identified in the plan
            for (relPath in plan.filesToRemove) {
                val currentFile = scan.files[relPath] ?: continue
                try {
                    if (currentFile.delete()) {
                        removedCount++
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

            // Step 2: Restore files identified in the plan
            for (relPath in plan.filesToRestore) {
                restoreSnapshotFile(relPath, snapshotFilesDir, rootCanonical, failedFiles, restoredFiles)
            }

            // Add unchanged target files to restored count
            val unchangedCount =
                targetCheckpoint.manifest.files.keys
                    .count { it !in plan.filesToRestore }
            val totalRestoredCount = restoredFiles.size + unchangedCount

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
                    restoredFilesCount = totalRestoredCount,
                    removedFilesCount = removedCount,
                    durationMs = durationMs,
                )
            }
        }

    @Suppress("TooGenericExceptionCaught") // Per-file fault isolation is deliberate.
    private fun restoreSnapshotFile(
        relPath: String,
        snapshotFilesDir: File,
        rootCanonical: File,
        failedFiles: MutableMap<String, String>,
        restoredFiles: MutableList<String>,
    ) {
        val sourceSnapshotFile =
            try {
                SafePathResolver.resolveSafeChild(snapshotFilesDir, relPath)
            } catch (e: Exception) {
                failedFiles[relPath] = "Security check failed for snapshot source path: ${e.message}"
                return
            }

        if (!sourceSnapshotFile.exists()) {
            failedFiles[relPath] = "Snapshot source blob missing from storage"
            return
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
}
