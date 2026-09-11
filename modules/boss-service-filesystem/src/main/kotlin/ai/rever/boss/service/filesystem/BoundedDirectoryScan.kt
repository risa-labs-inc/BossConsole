package ai.rever.boss.service.filesystem

import ai.rever.boss.files.FileInfo
import ai.rever.boss.files.NativeDirectory
import ai.rever.boss.ipc.proto.services.FileEntry
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import kotlinx.coroutines.ensureActive
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

internal class BoundedDirectoryScan(
    private val request: ScanDirectoryRequest,
    private val context: CoroutineContext,
    private val policy: FilePathPolicy,
) {
    private val requestedDepth = if (!request.recursive) 1 else request.maxDepth.takeIf { it > 0 } ?: Int.MAX_VALUE
    private val depth = requestedDepth.coerceAtMost(FileSystemLimits.SCAN_DEPTH)
    private val extensions = request.extensionsList.toSet()
    private val entries = mutableListOf<FileEntry>()
    private var visited = 1
    private var responseBytes = 0

    fun scan(root: FileDirectoryHandle): List<FileEntry> {
        visit(root.directory, root.canonical, root.visible, 1)
        return entries
    }

    private fun visit(
        directory: NativeDirectory,
        canonical: Path,
        visible: Path,
        level: Int,
    ) {
        directory.entries { name ->
            context.ensureActive()
            enforceFileSystemLimit(++visited <= FileSystemLimits.SCAN_ENTRIES, "Directory scan entry limit reached")
            val path = canonical.resolve(name)
            if ((!request.includeHidden && name.startsWith('.')) || !policy.allowed(path)) return@entries true
            val info = directory.info(name) ?: return@entries true
            val isDirectory = info.isDirectory && !info.isLink
            if (isDirectory || extensions.isEmpty() || name.substringAfterLast('.', "") in extensions) {
                add(visible.resolve(name), info)
            }
            if (isDirectory) {
                try {
                    directory.child(name).use { child ->
                        if (level < depth) {
                            visit(child, path, visible.resolve(name), level + 1)
                        } else if (requestedDepth > depth) {
                            child.entries {
                                throw fileSystemLimit("Directory scan depth limit reached")
                            }
                        }
                    }
                } catch (_: NoSuchFileException) {
                    // A concurrent deletion needs no traversal; every other failure stays visible.
                }
            }
            true
        }
    }

    private fun add(
        path: Path,
        attributes: FileInfo,
    ) {
        val directory = attributes.isDirectory && !attributes.isLink
        val entry =
            FileEntry
                .newBuilder()
                .setPath(path.toString())
                .setName(path.fileName.toString())
                .setIsDirectory(directory)
                .setSizeBytes(if (directory) 0L else attributes.size)
                .setModifiedAt(attributes.modifiedMillis)
                .setIsHidden(path.fileName.toString().startsWith('.'))
                .build()
        responseBytes += entry.serializedSize + 8
        enforceFileSystemLimit(responseBytes <= FileSystemLimits.SCAN_BYTES, "Directory scan response limit reached")
        entries.add(entry)
    }
}
