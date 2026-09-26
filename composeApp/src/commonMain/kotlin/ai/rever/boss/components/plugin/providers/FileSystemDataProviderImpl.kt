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
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import ai.rever.boss.components.plugin.panels.left_top.scanDirectoryWithDepth as platformScanDirectoryWithDepth

/**
 * Implementation of FileSystemDataProvider that wraps platform-specific file operations.
 * This allows plugins to access file system without direct platform coupling.
 *
 * Security: All filesystem operations are scoped to allowed directories:
 * - User home directory (always allowed)
 * - Downloads directory (with symlink/junction resolution)
 * - Plugin storage directory (if provided)
 * - Current project directory (if provided)
 *
 * The security boundary is enforced per-instance through the [allowedRoots] parameter,
 * preventing cross-plugin authorization leaks.
 */
class FileSystemDataProviderImpl(
    private val downloadsDirectory: () -> String = ::getDefaultDownloadsDirectory,
    private val allowedRoots: Set<File> = emptySet(),
) : FileSystemDataProvider {
    private val logger = BossLogger.forComponent("FileSystemDataProvider")
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * Default allowed roots: home directory and Downloads directory.
     * These are always available for backwards compatibility.
     */
    private val defaultRoots: Set<File> by lazy {
        val home = File(System.getProperty("user.home")).canonicalFile
        val downloads = runCatching { File(downloadsDirectory()).canonicalFile }.getOrNull()
        setOfNotNull(home, downloads)
    }

    /**
     * Combined allowed roots: explicit roots + default roots.
     * Exposed for scoped validation in reveal operations.
     */
    val effectiveRoots: Set<File> by lazy {
        (allowedRoots + defaultRoots).map { it.canonicalFile }.toSet()
    }

    override suspend fun scanDirectory(path: String): FileNodeData? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val validatedPath = validatePath(path, "scanDirectory")
                ai.rever.boss.components.plugin.panels.left_top
                    .scanDirectory(validatedPath)
            } catch (e: SecurityException) {
                logger.warn(
                    LogCategory.FILE,
                    "Scan directory denied by security policy",
                    mapOf("path" to path),
                    e,
                )
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
                val validatedPath = validatePath(path, "scanDirectoryWithDepth")
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

    override fun directoryHasChildren(path: String): Boolean =
        try {
            val validatedPath = validatePath(path, "directoryHasChildren")
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
                val validatedPath = validatePath(path, "scanDirectory")
                ai.rever.boss.components.plugin.panels.left_top
                    .scanDirectory(validatedPath, showHidden)
            } catch (e: SecurityException) {
                logger.warn(
                    LogCategory.FILE,
                    "Scan directory denied by security policy",
                    mapOf("path" to path),
                    e,
                )
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
                val validatedPath = validatePath(path, "scanDirectoryWithDepth")
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
    ): Boolean =
        try {
            val validatedPath = validatePath(path, "directoryHasChildren")
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

    override fun openFile(
        path: String,
        windowId: String,
    ) {
        try {
            val validatedPath = validatePath(path, "openFile")
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
                val validatedParentPath = validatePath(parentPath, "createFile")
                val validatedChildPath = validateChildPath(validatedParentPath, fileName, "createFile")
                val newFile = java.io.File(validatedChildPath)

                val parentDir = newFile.parentFile
                if (parentDir != null && (!parentDir.exists() || !parentDir.isDirectory)) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Parent directory does not exist: $parentPath"),
                    )
                }

                if (newFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("File already exists: ${newFile.absolutePath}"),
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
                val validatedParentPath = validatePath(parentPath, "createFolder")
                val validatedChildPath = validateChildPath(validatedParentPath, folderName, "createFolder")
                val newFolder = java.io.File(validatedChildPath)

                val parentDir = newFolder.parentFile
                if (parentDir != null && (!parentDir.exists() || !parentDir.isDirectory)) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Parent directory does not exist: $parentPath"),
                    )
                }

                if (newFolder.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("Folder already exists: ${newFolder.absolutePath}"),
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
                val validatedPath = validatePath(path, "delete")
                val file = java.io.File(validatedPath)

                // Note: We don't check exists() first to avoid race conditions.
                // delete() and deleteRecursively() handle non-existent files gracefully.
                // For recursive delete, we walk the tree explicitly to avoid following symlinks
                // outside the allowed boundary.
                val deleted =
                    if (file.isDirectory) {
                        deleteDirectorySafely(file)
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

    /**
     * Safely delete a directory without following symlinks outside the allowed boundary.
     *
     * This walks the tree explicitly and validates each child before deletion,
     * preventing symlink escape attacks during recursive delete.
     */
    private fun deleteDirectorySafely(dir: File): Boolean {
        if (!dir.isDirectory) return dir.delete()

        val children = dir.listFiles() ?: return dir.delete()
        var allDeleted = true

        for (child in children) {
            try {
                // Validate each child is still within allowed roots before deletion
                // This prevents TOCTOU attacks where a symlink is swapped after validation
                val childCanonical = child.canonicalFile
                if (!isPathWithinAllowedRoots(childCanonical)) {
                    logger.warn(
                        LogCategory.FILE,
                        "Skipping symlink/escape during delete: child outside allowed roots",
                        mapOf("child" to child.absolutePath),
                    )
                    allDeleted = false
                    continue
                }

                if (child.isDirectory) {
                    if (!deleteDirectorySafely(child)) {
                        allDeleted = false
                    }
                } else {
                    if (!child.delete()) {
                        allDeleted = false
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to delete child during recursive delete", mapOf("child" to child.absolutePath), error = e)
                allDeleted = false
            }
        }

        return allDeleted && dir.delete()
    }

    override suspend fun rename(
        path: String,
        newName: String,
    ): Result<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val validatedPath = validatePath(path, "rename")
                val file = java.io.File(validatedPath)
                if (!file.exists()) {
                    return@withContext Result.failure(IllegalArgumentException("File or folder does not exist: $path"))
                }

                val parentDir =
                    file.parentFile
                        ?: return@withContext Result.failure(IllegalStateException("Cannot determine parent directory"))

                val validatedNewPath = validateChildPath(parentDir.absolutePath, newName, "rename")
                val newFile = java.io.File(validatedNewPath)

                if (newFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("A file or folder with that name already exists"),
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
        // Base implementation passes no allowed roots (host-initiated, trusted)
        return ai.rever.boss.utils.revealInFileManager(path, allowedRoots = null)
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
                val validatedPath = validatePath(path, "writeFile")
                val file = java.io.File(validatedPath)

                // Additional check: if the path is a symlink/junction, verify its target is within allowed roots
                if (file.exists() && Files.isSymbolicLink(file.toPath())) {
                    val targetPath = file.toPath().toRealPath()
                    if (!isPathWithinAllowedRoots(File(targetPath.toString()))) {
                        throw SecurityException("Access denied: symlink target is outside allowed roots")
                    }
                }

                // Ensure parent directory exists
                val parentDir = file.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }

                file.writeText(content)
                Result.success(Unit)
            } catch (e: SecurityException) {
                logger.warn(LogCategory.FILE, "Write file denied by security policy", mapOf("path" to path), e)
                Result.failure(e)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to write file", mapOf("path" to path), error = e)
                Result.failure(e)
            }
        }
    }

    override suspend fun readFile(path: String): Result<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val validatedPath = validatePath(path, "readFile")
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

    // Shared with the browser's save location and with FileSystemDataProviderProxy, which
    // answers this locally for out-of-process plugins: a plugin must not be told a different
    // folder because of the process it happened to be loaded in. This used to hand back the
    // home folder when ~/Downloads was absent, dropping saved files loose in the home dir.
    override fun getDownloadsDirectory(): String = downloadsDirectory()

    override fun getHomeDirectory(): String = System.getProperty("user.home")

    /**
     * Validates and normalizes a filesystem path for plugin access.
     *
     * This method:
     * - Rejects null bytes and excessively long paths
     * - Normalizes the path to its canonical form
     * - Ensures the path is within at least one allowed root
     * - Prevents path traversal through canonical comparison
     * - Resolves symlinks to prevent symlink escapes
     *
     * @param rawPath The raw path string from the plugin
     * @param operation The operation being performed (for error messages)
     * @return The canonical path if valid
     * @throws SecurityException if the path is invalid or outside all allowed roots
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun validatePath(
        rawPath: String,
        operation: String = "filesystem access",
    ): String {
        val validationError =
            when {
                rawPath.isBlank() -> "Path cannot be blank for $operation"
                rawPath.length > MAX_PATH_LENGTH -> "Path exceeds maximum length of $MAX_PATH_LENGTH characters for $operation"
                rawPath.contains('\u0000') -> "Path contains null byte - possible directory traversal attack for $operation"
                else -> null
            }

        if (validationError != null) {
            throw SecurityException(validationError)
        }

        return try {
            val path = Paths.get(rawPath)
            val normalizedPath = path.normalize()
            val absolutePath = normalizedPath.toAbsolutePath()
            val canonicalPath = resolvePathWithNearestExistingAncestor(absolutePath, rawPath, operation)

            if (!isPathWithinAllowedRoots(File(canonicalPath.toString()))) {
                throw createAccessDeniedSecurityException(canonicalPath, operation)
            }

            canonicalPath.toString()
        } catch (e: InvalidPathException) {
            throw SecurityException("Invalid filesystem path for $operation: ${e.message}", e)
        } catch (e: SecurityException) {
            throw e
        } catch (e: java.io.IOException) {
            logger.warn(LogCategory.FILE, "Path validation failed", mapOf("path" to rawPath, "error" to e.toString()))
            throw SecurityException("Path validation failed for $operation: ${e.message}", e)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Path validation failed", mapOf("path" to rawPath, "error" to e.toString()))
            throw SecurityException("Path validation failed for $operation: ${e.message}", e)
        }
    }

    /**
     * Validates that a child path is within a parent directory boundary.
     *
     * This is used for operations like createFile/createFolder where the plugin
     * specifies a parent directory and a child name.
     *
     * @param parentPath The parent directory path (already validated)
     * @param childName The child file/folder name
     * @param operation The operation being performed (for error messages)
     * @return The canonical path of the child if valid
     * @throws SecurityException if the child would be outside the parent boundary
     */
    @Suppress("ThrowsCount")
    private fun validateChildPath(
        parentPath: String,
        childName: String,
        operation: String = "create operation",
    ): String {
        val validationError =
            when {
                childName.isBlank() -> "Child name cannot be blank for $operation"
                childName.contains('\u0000') -> "Child name contains null byte for $operation"
                childName.contains(File.separator) || childName.contains("/") || childName.contains("\\") ->
                    "Child name contains path separators for $operation"
                else -> null
            }

        if (validationError != null) {
            throw SecurityException(validationError)
        }

        // Construct the full child path
        val childPath = Paths.get(parentPath, childName)
        val normalizedChild = childPath.normalize()
        val normalizedParent = Paths.get(parentPath).normalize()

        if (!isPathWithinBoundary(normalizedChild, normalizedParent)) {
            throw SecurityException(
                "Path traversal detected: child would be created outside parent directory for $operation",
            )
        }

        return normalizedChild.toString()
    }

    /**
     * Resolves a path using the nearest-existing-ancestor pattern.
     *
     * For existing paths: uses toRealPath() to resolve symlinks
     * For non-existent paths: finds nearest existing ancestor, resolves it with toRealPath(),
     * then re-resolves the remaining path components
     */
    private fun resolvePathWithNearestExistingAncestor(
        absolutePath: Path,
        rawPath: String,
        operation: String,
    ): Path {
        if (Files.exists(absolutePath)) {
            return try {
                absolutePath.toRealPath()
            } catch (e: java.nio.file.FileSystemException) {
                throw SecurityException("Path validation failed for $operation: ${e.message}", e)
            }
        }

        var ancestor: Path? = absolutePath.parent
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.parent
        }

        return if (ancestor != null) {
            try {
                val resolvedAncestor = ancestor.toRealPath()
                val remainingPath = ancestor.relativize(absolutePath)
                resolvedAncestor.resolve(remainingPath).normalize()
            } catch (e: java.nio.file.FileSystemException) {
                throw SecurityException("Path validation failed for $operation: ${e.message}", e)
            }
        } else {
            logger.debug(
                LogCategory.FILE,
                "No existing ancestor found for path, using normalized path",
                mapOf("path" to rawPath),
            )
            absolutePath.normalize()
        }
    }

    private fun createAccessDeniedSecurityException(
        canonicalPath: Path,
        operation: String,
    ): SecurityException {
        val errorMessage =
            "Access denied: path '$canonicalPath' is outside all allowed filesystem roots. " +
                "Use FilePickerProvider for user-mediated file access, work within your project directory, " +
                "or use your plugin's storage directory."
        logger.warn(
            LogCategory.FILE,
            "Plugin filesystem access denied: path outside all allowed roots",
            mapOf(
                "path" to canonicalPath.toString(),
                "allowedRoots" to effectiveRoots.map { it.absolutePath },
                "operation" to operation,
            ),
        )
        return SecurityException(errorMessage)
    }

    /**
     * Checks if a path is within any allowed root.
     */
    private fun isPathWithinAllowedRoots(file: File): Boolean {
        val canonicalFile = file.canonicalFile
        for (root in effectiveRoots) {
            if (isPathWithinBoundary(canonicalFile.toPath(), root.toPath())) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if a path is within a boundary directory.
     *
     * Uses Path.startsWith() which is component-aware to handle symlinks and platform differences.
     */
    private fun isPathWithinBoundary(
        path: Path,
        boundary: Path,
    ): Boolean {
        val normalizedPath = path.normalize()
        val normalizedBoundary = boundary.normalize()

        if (normalizedPath == normalizedBoundary) {
            return true
        }

        return normalizedPath.startsWith(normalizedBoundary)
    }

    /**
     * Whether plugins may read and write [file], which must be canonical: inside the home
     * folder, as before, or inside the Downloads folder this provider hands out, which the user
     * may have moved outside home (another drive on Windows, an absolute XDG dir on Linux).
     * Nothing else. [delete] stays home-only: it is recursive, so admitting Downloads would let
     * one call empty it.
     *
     * This is preserved for backwards compatibility but the primary security enforcement
     * is now through [validatePath] and [isPathWithinAllowedRoots].
     */
    private fun isReadableAndWritable(file: File): Boolean {
        val home = File(System.getProperty("user.home")).canonicalFile
        val inHome = file.path == home.path || file.path.startsWith(home.path + File.separator)

        return inHome || isInsideDownloads(file)
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

    companion object {
        /**
         * Maximum path length to prevent DoS through excessively long paths.
         */
        private const val MAX_PATH_LENGTH = 32_768
    }
}

/**
 * Scoped wrapper for FileSystemDataProvider that binds it to specific allowed roots.
 *
 * This is similar to ScopedPluginStorageFactory and DownloadCenterProviderImpl.forPlugin:
 * it binds the security boundary to a specific plugin's capabilities rather than using
 * process-global state.
 *
 * @param pluginId The plugin ID this provider is scoped to
 * @param pluginStorageDir The plugin's storage directory (always allowed)
 * @param currentProjectDir The current project directory (if available)
 * @param delegate The underlying FileSystemDataProvider
 */
class ScopedFileSystemDataProvider(
    private val pluginId: String,
    private val pluginStorageDir: File,
    private val currentProjectDir: File?,
    private val delegate: FileSystemDataProvider,
) : FileSystemDataProvider {
    private val allowedRoots = buildAllowedRoots()
    private val scopedProvider = FileSystemDataProviderImpl(
        downloadsDirectory = { delegate.getDownloadsDirectory() },
        allowedRoots = allowedRoots,
    )

    private fun buildAllowedRoots(): Set<File> {
        val roots = mutableSetOf<File>()
        roots.add(pluginStorageDir.canonicalFile)
        currentProjectDir?.let { roots.add(it.canonicalFile) }
        return roots.toSet()
    }

    override suspend fun scanDirectory(path: String): FileNodeData? = scopedProvider.scanDirectory(path)

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
    ): FileNodeData? = scopedProvider.scanDirectoryWithDepth(path, maxDepth, startDepth)

    override fun directoryHasChildren(path: String): Boolean = scopedProvider.directoryHasChildren(path)

    override val supportsHiddenEntries: Boolean get() = scopedProvider.supportsHiddenEntries

    override suspend fun scanDirectory(
        path: String,
        showHidden: Boolean,
    ): FileNodeData? = scopedProvider.scanDirectory(path, showHidden)

    override suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
        showHidden: Boolean,
    ): FileNodeData? = scopedProvider.scanDirectoryWithDepth(path, maxDepth, startDepth, showHidden)

    override fun directoryHasChildren(
        path: String,
        showHidden: Boolean,
    ): Boolean = scopedProvider.directoryHasChildren(path, showHidden)

    override fun openFile(
        path: String,
        windowId: String,
    ) = scopedProvider.openFile(path, windowId)

    override suspend fun createFile(
        parentPath: String,
        fileName: String,
    ): Result<String> = scopedProvider.createFile(parentPath, fileName)

    override suspend fun createFolder(
        parentPath: String,
        folderName: String,
    ): Result<String> = scopedProvider.createFolder(parentPath, folderName)

    override suspend fun delete(path: String): Result<Unit> = scopedProvider.delete(path)

    override suspend fun rename(
        path: String,
        newName: String,
    ): Result<String> = scopedProvider.rename(path, newName)

    override fun revealInFileManager(path: String): Result<Unit> {
        // Pass the scoped allowed roots to revealInFileManager for security validation
        return ai.rever.boss.utils.revealInFileManager(path, allowedRoots)
    }

    override fun copyToClipboard(text: String): Result<Unit> = scopedProvider.copyToClipboard(text)

    override suspend fun writeFile(
        path: String,
        content: String,
    ): Result<Unit> = scopedProvider.writeFile(path, content)

    override suspend fun readFile(path: String): Result<String> = scopedProvider.readFile(path)

    override fun getDownloadsDirectory(): String = scopedProvider.getDownloadsDirectory()

    override fun getHomeDirectory(): String = scopedProvider.getHomeDirectory()
}
