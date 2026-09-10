package ai.rever.boss.recovery.storage

import ai.rever.boss.recovery.baseline.WorkspaceBaselineCapturer
import ai.rever.boss.recovery.models.CheckpointManifest
import ai.rever.boss.recovery.models.FileSnapshotMeta
import ai.rever.boss.recovery.models.WorkspaceCheckpoint
import ai.rever.boss.recovery.paths.SafePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Deterministic filesystem storage for workspace checkpoints.
 * Layout:
 *   ~/.boss/mission-recovery/{missionId}/{checkpointId}/
 *     checkpoint.json
 *     manifest.json
 *     files/
 *       <relative file tree>
 */
class WorkspaceCheckpointStorage(
    private val baseStorageDir: File = File(System.getProperty("user.home"), ".boss/mission-recovery"),
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    init {
        baseStorageDir.mkdirs()
    }

    private fun missionDir(missionId: String): File {
        val validMissionId = SafePathResolver.validateIdentifier(missionId, "missionId")
        return SafePathResolver.resolveSafeChild(baseStorageDir, validMissionId).also { it.mkdirs() }
    }

    private fun checkpointDir(missionId: String, checkpointId: String): File {
        val validCpId = SafePathResolver.validateIdentifier(checkpointId, "checkpointId")
        val mDir = missionDir(missionId)
        return SafePathResolver.resolveSafeChild(mDir, validCpId)
    }

    /**
     * Captures and persists a new workspace checkpoint for [projectRoot].
     */
    suspend fun createCheckpoint(
        missionId: String,
        checkpointId: String,
        label: String,
        projectRoot: File,
        excludedPatterns: Set<String> = CheckpointManifest.DEFAULT_EXCLUDED_PATTERNS,
        timestamp: Long = System.currentTimeMillis(),
    ): WorkspaceCheckpoint =
        withContext(Dispatchers.IO) {
            val rootCanonical = SafePathResolver.canonicalRoot(projectRoot)
            val cpDir = checkpointDir(missionId, checkpointId)
            val filesDir = File(cpDir, "files")
            filesDir.mkdirs()

            val filesMap = mutableMapOf<String, FileSnapshotMeta>()

            fun copyAndRecord(currentDir: File, relativeDir: String) {
                val children = currentDir.listFiles() ?: return
                for (child in children) {
                    val childName = child.name
                    if (SafePathResolver.isExcluded(childName, excludedPatterns)) {
                        continue
                    }

                    val relPath = if (relativeDir.isEmpty()) childName else "$relativeDir/$childName"
                    val normalizedRel = SafePathResolver.normalizeRelativePath(relPath)

                    if (child.isDirectory) {
                        if (SafePathResolver.isSafeDirectoryToRecurse(child, rootCanonical)) {
                            copyAndRecord(child, normalizedRel)
                        }
                    } else if (child.isFile) {
                        if (SafePathResolver.isContainedFile(child, rootCanonical)) {
                            val sha256 = WorkspaceBaselineCapturer.calculateSha256(child)
                            val meta =
                                FileSnapshotMeta(
                                    relativePath = normalizedRel,
                                    sha256 = sha256,
                                    sizeBytes = child.length(),
                                    lastModified = child.lastModified(),
                                )
                            filesMap[normalizedRel] = meta

                            // Copy file to snapshot store
                            val destFile = File(filesDir, normalizedRel)
                            destFile.parentFile?.mkdirs()
                            child.copyTo(destFile, overwrite = true)
                        }
                    }
                }
            }

            copyAndRecord(rootCanonical, "")

            val manifest =
                CheckpointManifest(
                    checkpointId = checkpointId,
                    missionId = missionId,
                    timestamp = timestamp,
                    files = filesMap,
                    excludedPatterns = excludedPatterns,
                )

            val checkpoint =
                WorkspaceCheckpoint(
                    checkpointId = checkpointId,
                    missionId = missionId,
                    label = label,
                    timestamp = timestamp,
                    manifest = manifest,
                )

            // Persist JSON metadata
            val manifestJsonFile = File(cpDir, "manifest.json")
            val checkpointJsonFile = File(cpDir, "checkpoint.json")

            manifestJsonFile.writeText(json.encodeToString(manifest))
            checkpointJsonFile.writeText(json.encodeToString(checkpoint))

            checkpoint
        }

    /**
     * Loads a persisted checkpoint by [missionId] and [checkpointId].
     */
    suspend fun loadCheckpoint(
        missionId: String,
        checkpointId: String,
    ): WorkspaceCheckpoint? =
        withContext(Dispatchers.IO) {
            val cpDir = checkpointDir(missionId, checkpointId)
            val checkpointJsonFile = File(cpDir, "checkpoint.json")
            if (!checkpointJsonFile.exists()) return@withContext null

            try {
                json.decodeFromString<WorkspaceCheckpoint>(checkpointJsonFile.readText())
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Returns the snapshot file directory containing stored file blobs for a checkpoint.
     */
    fun getSnapshotFilesDir(missionId: String, checkpointId: String): File {
        return File(checkpointDir(missionId, checkpointId), "files")
    }

    /**
     * Lists all checkpoints for a mission, sorted most recent first.
     */
    suspend fun listCheckpoints(missionId: String): List<WorkspaceCheckpoint> =
        withContext(Dispatchers.IO) {
            val mDir = missionDir(missionId)
            val cpDirs = mDir.listFiles { f -> f.isDirectory } ?: return@withContext emptyList()

            cpDirs.mapNotNull { dir ->
                val checkpointFile = File(dir, "checkpoint.json")
                if (checkpointFile.exists()) {
                    try {
                        json.decodeFromString<WorkspaceCheckpoint>(checkpointFile.readText())
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }.sortedByDescending { it.timestamp }
        }

    /**
     * Deletes a checkpoint from disk.
     */
    suspend fun deleteCheckpoint(missionId: String, checkpointId: String): Boolean =
        withContext(Dispatchers.IO) {
            val cpDir = checkpointDir(missionId, checkpointId)
            if (cpDir.exists()) {
                cpDir.deleteRecursively()
            } else {
                false
            }
        }
}
