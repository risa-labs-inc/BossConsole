package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.plugin.panels.left_top.directoryHasChildren
import ai.rever.boss.components.plugin.panels.left_top.scanDirectory
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.utils.PluginFileSystemSecurity
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.revealInFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
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
            try {
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "scanDirectory")
                ai.rever.boss.components.plugin.panels.left_top
                    .scanDirectory(validatedPath)
            } catch (e: SecurityException) {
                logger.warn(LogCategory.FILE, "Scan directory denied by security policy", mapOf("path" to path), e)
                null
            }
        }

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
    ): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "scanDirectoryWithDepth")
                platformScanDirectoryWithDepth(validatedPath, maxDepth, startDepth)
            } catch (e: SecurityException) {
                logger.warn(
                    LogCategory.FILE,
                    "Scan directory with depth denied by security policy",
                    mapOf("path" to path),
                    e,
                )
                null
            }
        }

    override fun directoryHasChildren(path: String): Boolean {
        return try {
            val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "directoryHasChildren")
            ai.rever.boss.components.plugin.panels.left_top
                .directoryHasChildren(validatedPath)
        } catch (e: SecurityException) {
            logger.warn(
                LogCategory.FILE,
                "Directory has children check denied by security policy",
                mapOf("path" to path),
                e,
            )
            false
        }
    }

    // This host honors the showHidden flag on the read-side scan overloads
    // (api >= 1.0.66, the first published release with the opt-in).
    // Plugins check this before relying on the flag.
    override val supportsHiddenEntries: Boolean get() = true

    override suspend fun scanDirectory(
        path: String,
        showHidden: Boolean,
    ): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "scanDirectory")
                ai.rever.boss.components.plugin.panels.left_top
                    .scanDirectory(validatedPath, showHidden)
            } catch (e: SecurityException) {
                logger.warn(LogCategory.FILE, "Scan directory denied by security policy", mapOf("path" to path), e)
                null
            }
        }

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
        showHidden: Boolean,
    ): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "scanDirectoryWithDepth")
                platformScanDirectoryWithDepth(validatedPath, maxDepth, startDepth, showHidden)
            } catch (e: SecurityException) {
                logger.warn(
                    LogCategory.FILE,
                    "Scan directory with depth denied by security policy",
                    mapOf("path" to path),
                    e,
                )
                null
            }
        }

    override fun directoryHasChildren(
        path: String,
        showHidden: Boolean,
    ): Boolean {
        return try {
            val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "directoryHasChildren")
            ai.rever.boss.components.plugin.panels.left_top
                .directoryHasChildren(validatedPath, showHidden)
        } catch (e: SecurityException) {
            logger.warn(
                LogCategory.FILE,
                "Directory has children check denied by security policy",
                mapOf("path" to path),
                e,
            )
            false
        }
    }

    override fun openFile(
        path: String,
        windowId: String,
    ) {
        try {
            val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "openFile")
            ioScope.launch {
                FileEventBus.openFile(validatedPath, sourceWindowId = windowId)
            }
        } catch (e: SecurityException) {
            logger.warn(LogCategory.FILE, "Open file denied by security policy", mapOf("path" to path), e)
        }
    }

    override suspend fun createFile(
        parentPath: String,
        fileName: String,
    ): Result<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val validatedPath = PluginFileSystemSecurity.validateChildPath(parentPath, fileName, "createFile")
                val newFile = java.io.File(validatedPath)

                val parentDir = newFile.parentFile
                if (parentDir != null && (!parentDir.exists() || !parentDir.isDirectory)) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Parent directory does not exist: $parentPath")
                    )
                }

                if (newFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("File already exists: ${newFile.absolutePath}")
                    )
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
                val validatedPath = PluginFileSystemSecurity.validateChildPath(parentPath, folderName, "createFolder")
                val newFolder = java.io.File(validatedPath)

                val parentDir = newFolder.parentFile
                if (parentDir != null && (!parentDir.exists() || !parentDir.isDirectory)) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Parent directory does not exist: $parentPath")
                    )
                }

                if (newFolder.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("Folder already exists: ${newFolder.absolutePath}")
                    )
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
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "delete")
                val file = java.io.File(validatedPath)

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
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "rename")
                val file = java.io.File(validatedPath)
                if (!file.exists()) {
                    return@withContext Result.failure(IllegalArgumentException("File or folder does not exist: $path"))
                }

                val parentDir =
                    file.parentFile
                        ?: return@withContext Result.failure(IllegalStateException("Cannot determine parent directory"))

                val validatedNewPath =
                    PluginFileSystemSecurity.validateChildPath(parentDir.absolutePath, newName, "rename")
                val newFile = java.io.File(validatedNewPath)

                if (newFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("A file or folder with that name already exists")
                    )
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

    override fun revealInFileManager(path: String): Result<Unit> {
        // Security validation is now handled in the revealInFileManager utility function
        return ai.rever.boss.utils.revealInFileManager(path)
    }

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
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "writeFile")
                val file = java.io.File(validatedPath)

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
                val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(path, "readFile")
                val file = java.io.File(validatedPath)

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
