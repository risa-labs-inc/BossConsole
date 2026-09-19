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
 * Delete a path below [homeDirectory] without permitting the home root itself or following
 * directory symlinks encountered during recursion.
 *
 * Canonical paths enforce the containment boundary. Deletion deliberately uses the original path
 * with NIO's default no-follow walk so a nested link is removed as an entry, never traversed into.
 */
internal fun deleteUserPath(
    file: File,
    homeDirectory: File,
): Result<Unit> =
    runCatching {
        val canonicalFile = file.canonicalFile.toPath()
        val canonicalHome = homeDirectory.canonicalFile.toPath()
        if (canonicalFile == canonicalHome) {
            throw SecurityException("Access denied: refusing to delete the user home directory")
        }
        if (!canonicalFile.startsWith(canonicalHome)) {
            throw SecurityException("Access denied: file path outside user directory")
        }

        val target = file.toPath().toAbsolutePath().normalize()
        val deleted =
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(target).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
                true
            } else {
                Files.deleteIfExists(target)
            }

        check(deleted) { "Failed to delete (file may not exist or is locked): $file" }
    }

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

    override suspend fun delete(path: String): Result<Unit> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            deleteUserPath(
                file = File(path),
                homeDirectory = File(System.getProperty("user.home")),
            )
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
