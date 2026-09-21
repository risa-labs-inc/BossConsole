package ai.rever.boss.orchestrator

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Manages state snapshots for process recovery.
 *
 * Layout: $dataDir/snapshots/{processId}/{timestamp}-{uuid}.snapshot
 * Optional description: $dataDir/snapshots/{processId}/{timestamp}-{uuid}.desc
 */
class SnapshotManager(
    private val dataDir: File,
) {
    private val processIdRegex = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}\$")
    private val snapshotsBaseDir: File by lazy {
        File(dataDir, "snapshots").apply { mkdirs() }
    }

    private fun validateAndGetSnapshotDir(processId: String): File {
        require(processId.isNotBlank() && processIdRegex.matches(processId)) {
            "Invalid processId: '$processId'"
        }
        val targetDir = File(snapshotsBaseDir, processId)
        val canonicalBase = snapshotsBaseDir.canonicalFile.toPath()
        val canonicalTarget = targetDir.canonicalFile.toPath()
        require(canonicalTarget.startsWith(canonicalBase)) {
            "Invalid processId escaping snapshots directory: '$processId'"
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    @Suppress("TooGenericExceptionCaught")
    private fun atomicWrite(
        file: File,
        bytes: ByteArray,
    ) {
        val parent = file.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        val tempFile = File.createTempFile(".${file.name}", ".tmp", parent)
        try {
            setOwnerOnlyPermissions(tempFile)
            tempFile.writeBytes(bytes)
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun atomicWriteText(
        file: File,
        text: String,
    ) {
        atomicWrite(file, text.toByteArray(Charsets.UTF_8))
    }

    private fun setOwnerOnlyPermissions(file: File) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            } else {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                file.setExecutable(false, false)
            }
        } catch (_: Exception) {
            // Best effort on non-POSIX platforms
        }
    }

    /** Persist [data] for [processId] and return the new snapshot ID. */
    fun save(
        processId: String,
        data: ByteArray,
        description: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val dir = validateAndGetSnapshotDir(processId)
        val snapFile = File(dir, "$timestamp-$id.snapshot")
        atomicWrite(snapFile, data)
        if (description.isNotBlank()) {
            val descFile = File(dir, "$timestamp-$id.desc")
            atomicWriteText(descFile, description)
        }
        return id
    }

    /** Return the bytes of the most recent snapshot, or null if none exist. */
    fun loadLatest(processId: String): ByteArray? {
        val dir = validateAndGetSnapshotDir(processId)
        if (!dir.exists()) return null
        return dir
            .listFiles { f -> f.extension == "snapshot" }
            ?.maxByOrNull { it.nameWithoutExtension.substringBefore("-").toLongOrNull() ?: 0L }
            ?.readBytes()
    }

    /** List all snapshots for [processId], most recent first. */
    fun listSnapshots(processId: String): List<SnapshotInfo> {
        val dir = validateAndGetSnapshotDir(processId)
        if (!dir.exists()) return emptyList()
        return dir
            .listFiles { f -> f.extension == "snapshot" }
            ?.map { file ->
                val nameWithoutExt = file.nameWithoutExtension
                val dashIdx = nameWithoutExt.indexOf('-')
                val timestamp = if (dashIdx > 0) nameWithoutExt.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
                val id = if (dashIdx > 0) nameWithoutExt.substring(dashIdx + 1) else nameWithoutExt
                val descFile = File(dir, "$nameWithoutExt.desc")
                SnapshotInfo(
                    id = id,
                    processId = processId,
                    timestamp = timestamp,
                    sizeBytes = file.length(),
                    description = if (descFile.exists()) descFile.readText() else "",
                )
            }?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /** Delete all but the [keepLast] most recent snapshots for [processId]. */
    fun cleanup(
        processId: String,
        keepLast: Int = 5,
    ) {
        val dir = validateAndGetSnapshotDir(processId)
        if (!dir.exists()) return
        val snapshots =
            dir
                .listFiles { f -> f.extension == "snapshot" }
                ?.sortedByDescending { it.nameWithoutExtension.substringBefore("-").toLongOrNull() ?: 0L }
                ?: return
        snapshots.drop(keepLast).forEach { file ->
            file.delete()
            File(dir, "${file.nameWithoutExtension}.desc").takeIf { it.exists() }?.delete()
        }
    }
}

data class SnapshotInfo(
    val id: String,
    val processId: String,
    val timestamp: Long,
    val sizeBytes: Long,
    val description: String,
)
