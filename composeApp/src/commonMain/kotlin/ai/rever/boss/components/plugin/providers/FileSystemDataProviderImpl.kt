package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.plugin.panels.left_top.directoryHasChildren
import ai.rever.boss.components.plugin.panels.left_top.scanDirectory
import ai.rever.boss.components.plugin.tab_types.fluck.getDefaultDownloadsDirectory
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
import java.nio.file.Path
import ai.rever.boss.components.plugin.panels.left_top.scanDirectoryWithDepth as platformScanDirectoryWithDepth

/**
 * Implementation of FileSystemDataProvider that wraps platform-specific file operations.
 * This allows plugins to access file system without direct platform coupling.
 */
class FileSystemDataProviderImpl(
    private val downloadsDirectory: () -> String = ::getDefaultDownloadsDirectory,
) : FileSystemDataProvider {
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

                // Security: Validate path is within user's home directory (prevent path traversal)
                val canonicalFile = file.canonicalFile
                val homeDir = File(System.getProperty("user.home")).canonicalFile
                if (!canonicalFile.absolutePath.startsWith(homeDir.absolutePath + File.separator) &&
                    canonicalFile.absolutePath != homeDir.absolutePath
                ) {
                    return@withContext Result.failure(SecurityException("Access denied: file path outside user directory"))
                }

                // Note: We don't check exists() first to avoid race conditions.
                // delete() and deleteRecursively() handle non-existent files gracefully.
                val deleted =
                    if (file.isDirectory) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }

                if (deleted) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Failed to delete (file may not exist or is locked): $path"))
                }
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

                // Security: Validate path is within the home or Downloads folder (prevent path traversal)
                if (!isReadableAndWritable(file.canonicalFile)) {
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

                // Security: Validate path is within the home or Downloads folder (prevent path traversal)
                if (!isReadableAndWritable(file.canonicalFile)) {
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

    // Shared with the browser's save location and with FileSystemDataProviderProxy, which
    // answers this locally for out-of-process plugins: a plugin must not be told a different
    // folder because of the process it happened to be loaded in. This used to hand back the
    // home folder when ~/Downloads was absent, dropping saved files loose in the home dir.
    override fun getDownloadsDirectory(): String = downloadsDirectory()

    override fun getHomeDirectory(): String = System.getProperty("user.home")

    /**
     * Whether plugins may read and write [file], which must be canonical: inside the home
     * folder, as before, or inside the Downloads folder this provider hands out, which the user
     * may have moved outside home (another drive on Windows, an absolute XDG dir on Linux).
     * Nothing else. [delete] stays home-only: it is recursive, so admitting Downloads would let
     * one call empty it.
     */
    private fun isReadableAndWritable(file: File): Boolean {
        val home = File(System.getProperty("user.home")).canonicalFile
        val inHome = file.path == home.path || file.path.startsWith(home.path + File.separator)
        val validLocation = inHome || isInsideDownloads(file)

        return validLocation && !isSystemDirectory(file.canonicalFile.path, home)
    }

    private fun isSystemDirectory(
        canonicalPath: String,
        home: File,
    ): Boolean {
        val bossDir = File(home, ".boss").canonicalFile.path
        val bossDebugDir = File(home, ".boss_debug").canonicalFile.path
        val isBoss = canonicalPath == bossDir || canonicalPath.startsWith(bossDir + File.separator)
        val isBossDebug = canonicalPath == bossDebugDir || canonicalPath.startsWith(bossDebugDir + File.separator)
        return isBoss || isBossDebug
    }

    /**
     * Compared on real paths, so neither `..` nor a symlink or junction inside the folder can
     * lead into a sibling. A Downloads folder at a filesystem root admits nothing, since that
     * would admit the whole drive. A path that cannot be resolved, such as a dangling link or a
     * Downloads share that has gone away, is refused rather than reported as an I/O failure.
     */
    private fun isInsideDownloads(file: File): Boolean {
        val downloads =
            runCatching { File(downloadsDirectory()).canonicalFile }
                .getOrNull()
                ?.let(::realPath)
                ?.takeIf { it.parent != null }

        return downloads != null && realPath(file)?.startsWith(downloads) == true
    }

    /**
     * [file] with every link resolved, including Windows junctions, which `canonicalFile` keeps
     * as written, or null if that fails. The climb stops at the first part that exists as an
     * entry, a link included even when its target does not, so a dangling link is resolved
     * (and fails) rather than appended as a plain name. Parts below it do not exist yet and
     * cannot be links, so they are appended as they are.
     */
    private fun realPath(file: File): Path? =
        runCatching {
            val existing =
                generateSequence(file) { it.parentFile }
                    .firstOrNull { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

            existing?.toPath()?.toRealPath()?.resolve(existing.toPath().relativize(file.toPath()))
        }.getOrNull()
}
