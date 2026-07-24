package ai.rever.boss.orchestrator

import java.io.File
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
    private fun snapshotDir(processId: String): File = File(dataDir, "snapshots/$processId").also { it.mkdirs() }

    /** Persist [data] for [processId] and return the new snapshot ID. */
    fun save(
        processId: String,
        data: ByteArray,
        description: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val dir = snapshotDir(processId)
        File(dir, "$timestamp-$id.snapshot").writeBytes(data)
        if (description.isNotBlank()) {
            File(dir, "$timestamp-$id.desc").writeText(description)
        }
        return id
    }

    /** Return the bytes of the most recent snapshot, or null if none exist. */
    fun loadLatest(processId: String): ByteArray? {
        val dir = File(dataDir, "snapshots/$processId")
        if (!dir.exists()) return null
        return dir
            .listFiles { f -> f.extension == "snapshot" }
            ?.maxByOrNull { it.nameWithoutExtension.substringBefore("-").toLongOrNull() ?: 0L }
            ?.readBytes()
    }

    /** List all snapshots for [processId], most recent first. */
    fun listSnapshots(processId: String): List<SnapshotInfo> {
        val dir = File(dataDir, "snapshots/$processId")
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
        val dir = File(dataDir, "snapshots/$processId")
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
