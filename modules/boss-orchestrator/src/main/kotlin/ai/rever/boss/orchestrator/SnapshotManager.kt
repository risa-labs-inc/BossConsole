package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.IpcAddressResolver
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Manages state snapshots for process recovery.
 *
 * Layout: $dataDir/snapshots/{processId}/{timestamp}-{uuid}.snapshot
 * Optional description: $dataDir/snapshots/{processId}/{timestamp}-{uuid}.desc
 *
 * On POSIX filesystems, construction and writes fail closed when owner-only permissions cannot be applied.
 */
class SnapshotManager(
    private val dataDir: File,
) {
    private val snapshotsRoot: File =
        File(dataDir, "snapshots").also {
            Files.createDirectories(it.toPath())
            applyPosixOwnerPermissions(it.toPath(), isDirectory = true)
        }
    private val canonicalSnapshotsRoot: Path = snapshotsRoot.toPath().toRealPath()

    private fun validateProcessId(processId: String) {
        IpcAddressResolver.validateProcessIdentifier(processId)
        val reserved = processId.substringBefore('.').matches(Regex("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
        val validFormat = processId.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}"))
        require(validFormat && !processId.endsWith('.') && !reserved) {
            "Invalid process ID: $processId"
        }
    }

    private fun snapshotDir(
        processId: String,
        create: Boolean,
    ): File? {
        validateProcessId(processId)
        val dir = File(snapshotsRoot, processId)
        val path = dir.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null
            try {
                Files.createDirectory(path)
            } catch (_: FileAlreadyExistsException) {
                // A concurrent first save may have created it; verify the occupant below.
            }
        }
        require(!Files.isSymbolicLink(path)) { "Process directory must not be a symbolic link" }
        val realPath = path.toRealPath()
        require(realPath.parent == canonicalSnapshotsRoot && Files.isDirectory(realPath, LinkOption.NOFOLLOW_LINKS)) {
            "Process directory escapes snapshots root or is not a directory"
        }
        if (create) applyPosixOwnerPermissions(realPath, isDirectory = true)
        return dir
    }

    /** Persist [data] for [processId] and return the new snapshot ID. */
    fun save(
        processId: String,
        data: ByteArray,
        description: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val dir = checkNotNull(snapshotDir(processId, create = true))
        val snapshotFile = File(dir, "$timestamp-$id.snapshot")
        atomicWriteFile(snapshotFile, data)
        if (description.isNotBlank()) {
            val descFile = File(dir, "$timestamp-$id.desc")
            atomicWriteFile(descFile, description.toByteArray(Charsets.UTF_8))
        }
        return id
    }

    /**
     * Return the bytes of the most recent snapshot, or null if none exist.
     *
     * @throws IllegalArgumentException if the process ID is invalid or its directory escapes the snapshot root.
     */
    fun loadLatest(processId: String): ByteArray? {
        val dir = snapshotDir(processId, create = false) ?: return null
        return dir
            .listFiles { f -> f.extension == "snapshot" && f.isRegularFileNoFollow() }
            ?.maxByOrNull { it.nameWithoutExtension.substringBefore("-").toLongOrNull() ?: 0L }
            ?.readBytes()
    }

    /**
     * List all snapshots for [processId], most recent first.
     *
     * @throws IllegalArgumentException if the process ID is invalid or its directory escapes the snapshot root.
     */
    fun listSnapshots(processId: String): List<SnapshotInfo> {
        val dir = snapshotDir(processId, create = false) ?: return emptyList()
        return dir
            .listFiles { f -> f.extension == "snapshot" && f.isRegularFileNoFollow() }
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
                    description = if (descFile.isRegularFileNoFollow()) descFile.readText() else "",
                )
            }?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Delete all but the [keepLast] most recent snapshots for [processId].
     *
     * @throws IllegalArgumentException if the process ID is invalid or its directory escapes the snapshot root.
     */
    fun cleanup(
        processId: String,
        keepLast: Int = 5,
    ) {
        val dir = snapshotDir(processId, create = false) ?: return
        val snapshots =
            dir
                .listFiles { f -> f.extension == "snapshot" && f.isRegularFileNoFollow() }
                ?.sortedByDescending { it.nameWithoutExtension.substringBefore("-").toLongOrNull() ?: 0L }
                ?: return
        snapshots.drop(keepLast).forEach { file ->
            file.delete()
            File(dir, "${file.nameWithoutExtension}.desc").takeIf { it.isRegularFileNoFollow() }?.delete()
        }
    }

    private fun atomicWriteFile(
        target: File,
        content: ByteArray,
    ) {
        val parent = target.parentFile ?: throw IOException("Missing parent directory for $target")
        val tmp = Files.createTempFile(parent.toPath(), ".${target.name}.", ".tmp").toFile()
        try {
            applyPosixOwnerPermissions(tmp.toPath(), isDirectory = false)
            tmp.writeBytes(content)
            moveFile(tmp.toPath(), target.toPath())
        } finally {
            tmp.delete()
        }
    }

    private fun moveFile(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun applyPosixOwnerPermissions(
        path: Path,
        isDirectory: Boolean,
    ) {
        if (!hasPosix(path)) return
        val perms =
            if (isDirectory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                )
            }
        Files.setPosixFilePermissions(path, perms)
    }

    private fun hasPosix(path: Path): Boolean = path.fileSystem.supportedFileAttributeViews().contains("posix")
}

private fun File.isRegularFileNoFollow(): Boolean = Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

data class SnapshotInfo(
    val id: String,
    val processId: String,
    val timestamp: Long,
    val sizeBytes: Long,
    val description: String,
)
