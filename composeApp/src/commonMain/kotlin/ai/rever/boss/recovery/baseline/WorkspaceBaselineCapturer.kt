package ai.rever.boss.recovery.baseline

import ai.rever.boss.recovery.models.CheckpointManifest
import ai.rever.boss.recovery.models.FileSnapshotMeta
import ai.rever.boss.recovery.models.MissionBaseline
import ai.rever.boss.recovery.paths.SafePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Captures a baseline snapshot of the workspace before an agent begins work.
 * Preserves pre-existing user work and provides the invariant boundary for all subsequent mutations.
 */
object WorkspaceBaselineCapturer {
    /**
     * Scans [projectRoot] and builds a [MissionBaseline].
     */
    @Suppress("LongParameterList") // Each field is an optional mission-context attribute.
    suspend fun captureBaseline(
        missionId: String,
        projectRoot: File,
        excludedPatterns: Set<String> = CheckpointManifest.DEFAULT_EXCLUDED_PATTERNS,
        gitBranch: String? = null,
        gitHeadCommit: String? = null,
        gitUncommittedFiles: List<String> = emptyList(),
    ): MissionBaseline =
        withContext(Dispatchers.IO) {
            val rootCanonical = SafePathResolver.canonicalRoot(projectRoot)
            val filesMap = mutableMapOf<String, FileSnapshotMeta>()

            fun scanDir(
                currentDir: File,
                relativeDir: String,
            ) {
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
                            scanDir(child, normalizedRel)
                        }
                    } else if (child.isFile) {
                        if (SafePathResolver.isContainedFile(child, rootCanonical)) {
                            val sha256 = calculateSha256(child)
                            filesMap[normalizedRel] =
                                FileSnapshotMeta(
                                    relativePath = normalizedRel,
                                    sha256 = sha256,
                                    sizeBytes = child.length(),
                                    lastModified = child.lastModified(),
                                )
                        }
                    }
                }
            }

            scanDir(rootCanonical, "")

            MissionBaseline(
                missionId = missionId,
                projectRoot = rootCanonical.path,
                baselineFiles = filesMap,
                gitBranch = gitBranch,
                gitHeadCommit = gitHeadCommit,
                gitUncommittedFiles = gitUncommittedFiles,
            )
        }

    /**
     * Computes the SHA-256 hash string for a file.
     */
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
