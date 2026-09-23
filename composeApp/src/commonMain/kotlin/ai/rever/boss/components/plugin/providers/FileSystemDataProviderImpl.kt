package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.plugin.panels.left_top.directoryHasChildren
import ai.rever.boss.components.plugin.panels.left_top.scanDirectory
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.revealInFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import ai.rever.boss.components.plugin.panels.left_top.scanDirectoryWithDepth as platformScanDirectoryWithDepth

/**
 * Implementation of FileSystemDataProvider that wraps platform-specific file operations.
 * This allows plugins to access file system without direct platform coupling.
 */
class FileSystemDataProviderImpl : FileSystemDataProvider {
    private val logger = BossLogger.forComponent("FileSystemDataProvider")
    private val ioScope = CoroutineScope(Dispatchers.IO)

    override suspend fun scanDirectory(path: String): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ai.rever.boss.components.plugin.panels.left_top
                .scanDirectory(path)
        }

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
    ): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            platformScanDirectoryWithDepth(path, maxDepth, startDepth)
        }

    override fun directoryHasChildren(path: String): Boolean =
        ai.rever.boss.components.plugin.panels.left_top
            .directoryHasChildren(path)

    // This host honors the showHidden flag on the read-side scan overloads
    // (api >= 1.0.66, the first published release with the opt-in).
    // Plugins check this before relying on the flag.
    override val supportsHiddenEntries: Boolean get() = true

    override suspend fun scanDirectory(
        path: String,
        showHidden: Boolean,
    ): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ai.rever.boss.components.plugin.panels.left_top
                .scanDirectory(path, showHidden)
        }

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
        showHidden: Boolean,
    ): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            platformScanDirectoryWithDepth(path, maxDepth, startDepth, showHidden)
        }

    override fun directoryHasChildren(
        path: String,
        showHidden: Boolean,
    ): Boolean =
        ai.rever.boss.components.plugin.panels.left_top
            .directoryHasChildren(path, showHidden)

    override fun openFile(
        path: String,
        windowId: String,
    ) {
        ioScope.launch {
            FileEventBus.openFile(path, sourceWindowId = windowId)
        }
    }

    override suspend fun createFile(
        parentPath: String,
        fileName: String,
    ): Result<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val parentDir = java.io.File(parentPath)
                if (!parentDir.exists() || !parentDir.isDirectory) {
                    return@withContext Result.failure(IllegalArgumentException("Parent directory does not exist: $parentPath"))
                }

                val newFile = java.io.File(parentDir, fileName)

                // Security: Ensure the new file is within the parent directory (prevent path traversal)
                val canonicalParent = parentDir.canonicalFile
                val canonicalNew = newFile.canonicalFile
                if (!canonicalNew.absolutePath.startsWith(canonicalParent.absolutePath + File.separator) &&
                    canonicalNew.absolutePath != canonicalParent.absolutePath
                ) {
                    return@withContext Result.failure(
                        SecurityException("Path traversal detected: file would be created outside parent directory"),
                    )
                }

                if (newFile.exists()) {
                    return@withContext Result.failure(IllegalStateException("File already exists: ${newFile.absolutePath}"))
                }

                val created = newFile.createNewFile()
                if (created) {
                    Result.success(newFile.absolutePath)
                } else {
                    Result.failure(IllegalStateException("Failed to create file: ${newFile.absolutePath}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun createFolder(
        parentPath: String,
        folderName: String,
    ): Result<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val parentDir = java.io.File(parentPath)
                if (!parentDir.exists() || !parentDir.isDirectory) {
                    return@withContext Result.failure(IllegalArgumentException("Parent directory does not exist: $parentPath"))
                }

                val newFolder = java.io.File(parentDir, folderName)

                // Security: Ensure the new folder is within the parent directory (prevent path traversal)
                val canonicalParent = parentDir.canonicalFile
                val canonicalNew = newFolder.canonicalFile
                if (!canonicalNew.absolutePath.startsWith(canonicalParent.absolutePath + File.separator) &&
                    canonicalNew.absolutePath != canonicalParent.absolutePath
                ) {
                    return@withContext Result.failure(
                        SecurityException("Path traversal detected: folder would be created outside parent directory"),
                    )
                }

                if (newFolder.exists()) {
                    return@withContext Result.failure(IllegalStateException("Folder already exists: ${newFolder.absolutePath}"))
                }

                val created = newFolder.mkdir()
                if (created) {
                    Result.success(newFolder.absolutePath)
                } else {
                    Result.failure(IllegalStateException("Failed to create folder: ${newFolder.absolutePath}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun delete(path: String): Result<Unit> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(path)
                val homeDir = File(System.getProperty("user.home")).canonicalFile

                // Security: refuse any path outside the user-home boundary. The check is
                // component-aware (homeDir + separator) so `/home/user` does not match
                // `/home/user2/...`. The homeDir-itself case is refused below so a request for
                // `System.getProperty("user.home")` is rejected; that was the original
                // containment gap behind #1118, where deleteRecursively() would then erase
                // the profile the guard is meant to protect.
                //
                // BOTH sides use Path.toRealPath() so the symlink target is fully resolved
                // before the boundary check runs. File.canonicalFile does NOT reliably resolve
                // reparse-point symlinks on Windows (measured 2026-09-22: `File.canonicalFile`
                // on a directory symlink returned the link path, while `Path.toRealPath()`
                // returned the target). Walking the unresolved path through Files.walk below
                // would then descend into the target and erase it - which is exactly the
                // #1118 escape this guard is meant to prevent.
                val canonicalFile = file.canonicalFile
                val realCanonicalPath =
                    runCatching { canonicalFile.toPath().toRealPath().toString() }
                        .getOrDefault(canonicalFile.absolutePath)
                val realHomePath =
                    runCatching { homeDir.toPath().toRealPath().toString() }
                        .getOrDefault(homeDir.absolutePath)
                val canonicalPath = realCanonicalPath.trimEnd('\\', '/')
                val homePath = realHomePath.trimEnd('\\', '/')
                if (!canonicalPath.startsWith(homePath + File.separator) &&
                    !canonicalPath.equals(homePath, ignoreCase = true)
                ) {
                    return@withContext Result.failure(
                        SecurityException("Access denied: file path outside user directory"),
                    )
                }

                // Security: refuse to delete the home directory itself even though the
                // component-aware check above admits it. Without this guard, the recursive
                // walk below would erase the entire profile.
                if (canonicalPath.equals(homePath, ignoreCase = true)) {
                    return@withContext Result.failure(
                        SecurityException("Access denied: cannot delete the user home directory"),
                    )
                }

                // The walk MUST operate on the SAME resolved path the containment check used.
                // The check above resolves `home/link/../<sibling>` through both lexical `..`
                // resolution and symlink resolution; the walk operating on the original input
                // would let the OS re-resolve through the link and reach a path the check never
                // saw. Use toRealPath() to keep the two paths byte-identical; fall back to the
                // canonical path when toRealPath() throws (file missing), which is still in scope
                // because the check above already admitted it.
                //
                // Recursive deletion must NEVER follow directory symlinks. A permitted directory
                // can contain a symlink to an external directory, and Files.walk without
                // NOFOLLOW_LINKS would traverse that link and remove entries outside the
                // intended target. Files.walk defaults to NOFOLLOW_LINKS, so a symlinked
                // child is visited as a symlink entry and Files.delete removes the link itself
                // rather than its target.
                //
                // A non-existent target must report success: the pre-fix delete used
                // File.deleteRecursively(), which returned true for a missing path, and that
                // contract is what plugins and the #1118 boundary test depend on. The existence
                // check is on the resolved path so a deleted-and-recreated entry cannot be
                // treated as the original target.
                val target =
                    runCatching { canonicalFile.toPath().toRealPath() }
                        .getOrElse { canonicalFile.toPath() }
                val deleted =
                    when {
                        !Files.exists(target, LinkOption.NOFOLLOW_LINKS) -> {
                            // Missing - nothing to delete, but treat as success.
                            true
                        }

                        Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> {
                            Files.walk(target).use { paths ->
                                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                            }
                            true
                        }

                        else -> {
                            Files.deleteIfExists(target)
                        }
                    }

                if (deleted) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Failed to delete (file may be locked): $path"))
                }
            } catch (e: java.nio.file.NoSuchFileException) {
                // Treat "not there" as success to match the pre-fix deleteRecursively contract.
                // Logged at debug so a security review can confirm the call was a no-op rather than
                // an unrelated failure being misclassified.
                logger.debug(
                    LogCategory.FILE,
                    "Delete target already absent",
                    mapOf("path" to path, "exception" to e::class.qualifiedName),
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun rename(
        path: String,
        newName: String,
    ): Result<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    return@withContext Result.failure(IllegalArgumentException("File or folder does not exist: $path"))
                }

                val parentDir =
                    file.parentFile
                        ?: return@withContext Result.failure(IllegalStateException("Cannot determine parent directory"))

                val newFile = java.io.File(parentDir, newName)

                // Security: Ensure the renamed file stays within the parent directory (prevent path traversal)
                val canonicalParent = parentDir.canonicalFile
                val canonicalNew = newFile.canonicalFile
                if (!canonicalNew.absolutePath.startsWith(canonicalParent.absolutePath + File.separator) &&
                    canonicalNew.absolutePath != canonicalParent.absolutePath
                ) {
                    return@withContext Result.failure(
                        SecurityException("Path traversal detected: file would be moved outside parent directory"),
                    )
                }

                if (newFile.exists()) {
                    return@withContext Result.failure(IllegalStateException("A file or folder with that name already exists"))
                }

                val renamed = file.renameTo(newFile)
                if (renamed) {
                    Result.success(newFile.absolutePath)
                } else {
                    Result.failure(IllegalStateException("Failed to rename: $path"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun revealInFileManager(path: String): Result<Unit> = revealInFileManager(path)

    override fun copyToClipboard(text: String): Result<Unit> =
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(text), null)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to copy to clipboard", error = e)
            Result.failure(e)
        }

    override suspend fun writeFile(
        path: String,
        content: String,
    ): Result<Unit> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(path)

                // Security: Validate path is within user's home directory (prevent path traversal)
                val canonicalFile = file.canonicalFile
                val homeDir = File(System.getProperty("user.home")).canonicalFile
                if (!canonicalFile.absolutePath.startsWith(homeDir.absolutePath + File.separator) &&
                    canonicalFile.absolutePath != homeDir.absolutePath
                ) {
                    return@withContext Result.failure(SecurityException("Access denied: file path outside user directory"))
                }

                // Ensure parent directory exists
                val parentDir = file.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }

                file.writeText(content)
                Result.success(Unit)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to write file", mapOf("path" to path), error = e)
                Result.failure(e)
            }
        }
    }

    override suspend fun readFile(path: String): Result<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(path)

                // Security: Validate path is within user's home directory (prevent path traversal)
                val canonicalFile = file.canonicalFile
                val homeDir = File(System.getProperty("user.home")).canonicalFile
                if (!canonicalFile.absolutePath.startsWith(homeDir.absolutePath + File.separator) &&
                    canonicalFile.absolutePath != homeDir.absolutePath
                ) {
                    return@withContext Result.failure(SecurityException("Access denied: file path outside user directory"))
                }

                if (!file.exists()) {
                    return@withContext Result.failure(IllegalArgumentException("File does not exist: $path"))
                }
                Result.success(file.readText())
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to read file", mapOf("path" to path), error = e)
                Result.failure(e)
            }
        }
    }

    override fun getDownloadsDirectory(): String {
        val homeDir = System.getProperty("user.home")
        val downloadsDir = java.io.File(homeDir, "Downloads")
        return if (downloadsDir.exists()) downloadsDir.absolutePath else homeDir
    }

    override fun getHomeDirectory(): String = System.getProperty("user.home")
}
