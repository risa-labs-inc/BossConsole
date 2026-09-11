package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.FileEntry
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import kotlinx.coroutines.ensureActive
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext

internal class BoundedDirectoryScan(
    private val request: ScanDirectoryRequest,
    private val context: CoroutineContext,
) {
    private val requestedDepth = if (!request.recursive) 1 else request.maxDepth.takeIf { it > 0 } ?: Int.MAX_VALUE
    private val depth = requestedDepth.coerceAtMost(FileSystemLimits.SCAN_DEPTH)
    private val extensions = request.extensionsList.toSet()
    private val entries = mutableListOf<FileEntry>()
    private var visited = 0
    private var responseBytes = 0

    fun scan(root: Path): List<FileEntry> {
        val target = root.toRealPath()
        Files.walkFileTree(
            target,
            emptySet(),
            depth,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ) = visit(target, root, dir, attrs)

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ) = visit(target, root, file, attrs)
            },
        )
        return entries
    }

    private fun visit(
        root: Path,
        visibleRoot: Path,
        path: Path,
        attributes: BasicFileAttributes,
    ): FileVisitResult {
        context.ensureActive()
        enforceFileSystemLimit(++visited <= FileSystemLimits.SCAN_ENTRIES, "Directory scan entry limit reached")
        val name = path.fileName?.toString().orEmpty()
        return when {
            path == root -> {
                FileVisitResult.CONTINUE
            }

            !request.includeHidden && name.startsWith('.') -> {
                FileVisitResult.SKIP_SUBTREE
            }

            else -> {
                checkDepth(root, path, attributes)
                if (attributes.isDirectory || extensions.isEmpty() || name.substringAfterLast('.', "") in extensions) {
                    add(visibleRoot.resolve(root.relativize(path)), attributes)
                }
                FileVisitResult.CONTINUE
            }
        }
    }

    private fun checkDepth(
        root: Path,
        path: Path,
        attributes: BasicFileAttributes,
    ) {
        if (attributes.isDirectory && requestedDepth > depth && root.relativize(path).nameCount == depth) {
            Files.newDirectoryStream(path).use {
                enforceFileSystemLimit(!it.iterator().hasNext(), "Directory scan depth limit reached")
            }
        }
    }

    private fun add(
        path: Path,
        attributes: BasicFileAttributes,
    ) {
        val entry =
            FileEntry
                .newBuilder()
                .setPath(path.toString())
                .setName(path.fileName.toString())
                .setIsDirectory(attributes.isDirectory)
                .setSizeBytes(if (attributes.isDirectory) 0L else attributes.size())
                .setModifiedAt(attributes.lastModifiedTime().toMillis())
                .setIsHidden(path.fileName.toString().startsWith('.'))
                .build()
        responseBytes += entry.serializedSize + 8
        enforceFileSystemLimit(responseBytes <= FileSystemLimits.SCAN_BYTES, "Directory scan response limit reached")
        entries.add(entry)
    }
}
