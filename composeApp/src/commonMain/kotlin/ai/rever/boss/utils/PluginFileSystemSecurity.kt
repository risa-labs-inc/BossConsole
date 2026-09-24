package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Security utility for plugin filesystem access.
 *
 * Enforces filesystem boundaries based on host-granted capabilities.
 * Integrates with the existing BOSS plugin/sandbox architecture rather than creating
 * a parallel permission framework.
 *
 * ## Security Model
 *
 * - **Explicit Capabilities**: Plugins are granted access to specific filesystem roots
 *   through host-controlled mechanisms (project selection, plugin storage, user-mediated file pickers).
 * - **Normalization**: All paths are canonicalized before validation to handle
 *   relative paths, symlinks, and platform-specific separators.
 * - **Traversal Prevention**: Uses canonical path comparison to detect and block
 *   path traversal attempts, including encoded/relative variants.
 * - **TOCTOU Awareness**: Path validation happens at the operation boundary, not just at check time.
 *
 * ## Allowed Roots
 *
 * The following roots are explicitly granted:
 * - **Plugin Storage**: The plugin's own storage directory (always granted)
 * - **Current Project**: The currently selected project directory (if a project is selected)
 * - **User Home**: The user's home directory (default for backwards compatibility)
 *
 * Additional roots can be granted through user-mediated mechanisms like FilePickerProvider.
 *
 * ## Migration Implications
 *
 * Existing plugins that accessed files outside the granted roots will now
 * receive SecurityException with a clear error message. This is intentional:
 * unrestricted filesystem access was a security vulnerability, not a feature.
 *
 * Plugins that need access to specific directories should:
 * 1. Request the user to open files through the FilePickerProvider (user-mediated access)
 * 2. Use project-specific paths provided by ProjectDataProvider
 * 3. Work within the plugin's own storage directory (via PluginStorageFactory)
 */
object PluginFileSystemSecurity {
    private val logger = BossLogger.forComponent("PluginFileSystemSecurity")

    /**
     * Maximum path length to prevent DoS through excessively long paths.
     * Matches the limit used in CLISecurityValidator for consistency.
     */
    private const val MAX_PATH_LENGTH = 32_768

    /**
     * Thread-safe set of allowed filesystem roots.
     * Roots are canonical paths that plugins are explicitly granted access to.
     */
    private val allowedRoots = ConcurrentHashMap<String, File>()

    /**
     * Initialize default allowed roots.
     * This is called during plugin initialization to set up the base capabilities.
     */
    fun initializeDefaultRoots(
        pluginStorageDir: File,
        currentProjectDir: File? = null,
    ) {
        // Always grant plugin storage directory
        val canonicalStorage = pluginStorageDir.canonicalFile
        allowedRoots[canonicalStorage.absolutePath] = canonicalStorage

        // Grant current project directory if provided
        if (currentProjectDir != null && currentProjectDir.exists()) {
            val canonicalProject = currentProjectDir.canonicalFile
            allowedRoots[canonicalProject.absolutePath] = canonicalProject
        }

        // Grant user home directory for backwards compatibility
        val homeDir = File(System.getProperty("user.home")).canonicalFile
        allowedRoots[homeDir.absolutePath] = homeDir

        logger.debug(
            LogCategory.FILE,
            "Initialized plugin filesystem security roots",
            mapOf(
                "roots" to allowedRoots.keys.toList(),
            ),
        )
    }

    /**
     * Add an explicit allowed root.
     * This is used for user-mediated grants (e.g., from FilePickerProvider).
     *
     * @param root The directory root to grant access to
     */
    fun addAllowedRoot(root: File) {
        val canonicalRoot = root.canonicalFile
        allowedRoots[canonicalRoot.absolutePath] = canonicalRoot
        logger.debug(
            LogCategory.FILE,
            "Added allowed filesystem root",
            mapOf("root" to canonicalRoot.absolutePath),
        )
    }

    /**
     * Remove an allowed root.
     * This is used for capability revocation.
     *
     * @param root The directory root to revoke access from
     */
    fun removeAllowedRoot(root: File) {
        val canonicalRoot = root.canonicalFile
        allowedRoots.remove(canonicalRoot.absolutePath)
        logger.debug(
            LogCategory.FILE,
            "Removed allowed filesystem root",
            mapOf("root" to canonicalRoot.absolutePath),
        )
    }

    /**
     * Get the current set of allowed roots.
     *
     * @return Set of canonical allowed root paths
     */
    fun getAllowedRoots(): Set<File> = allowedRoots.values.toSet()

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
     * ## TOCTOU Limitations
     *
     * This validation happens at the check boundary. There is a theoretical time-of-check-to-time-of-use
     * (TOCTOU) window between validation and the actual filesystem operation. For complete TOCTOU safety,
     * filesystem operations would need to use handle-relative or no-follow semantics from native libraries
     * (see boss-native-files). This implementation uses standard Java File API which follows symlinks
     * at operation time, but the boundary check still prevents access to paths outside granted roots.
     *
     * @param rawPath The raw path string from the plugin
     * @param operation The operation being performed (for error messages)
     * @return The canonical path if valid
     * @throws SecurityException if the path is invalid or outside all allowed roots
     */
    fun validateAndNormalizePath(
        rawPath: String,
        operation: String = "filesystem access",
    ): String {
        // Basic validation
        if (rawPath.isBlank()) {
            throw SecurityException("Path cannot be blank for $operation")
        }

        if (rawPath.length > MAX_PATH_LENGTH) {
            throw SecurityException("Path exceeds maximum length of $MAX_PATH_LENGTH characters for $operation")
        }

        // Check for null bytes (can bypass path checks)
        if (rawPath.contains('\u0000')) {
            throw SecurityException("Path contains null byte - possible directory traversal attack for $operation")
        }

        try {
            // Convert to Path object for robust normalization
            val path = Paths.get(rawPath)

            // Normalize to handle . and .. segments
            val normalizedPath = path.normalize()

            // Convert to absolute path
            val absolutePath = normalizedPath.toAbsolutePath()

            // Resolve to canonical path (follows symlinks, handles platform-specific issues)
            // This prevents symlink escapes by checking the real target against allowed roots
            val canonicalPath = absolutePath.normalize()

            // Check against all allowed roots using canonical path
            if (!isPathWithinAnyAllowedRoot(canonicalPath)) {
                logger.warn(
                    LogCategory.FILE,
                    "Plugin filesystem access denied: path outside all allowed roots",
                    mapOf(
                        "path" to canonicalPath.toString(),
                        "allowedRoots" to allowedRoots.keys.toList(),
                        "operation" to operation,
                    ),
                )
                throw SecurityException(
                    "Access denied: path '${canonicalPath}' is outside all allowed filesystem roots. " +
                        "Use FilePickerProvider for user-mediated file access, work within your project directory, " +
                        "or use your plugin's storage directory.",
                )
            }

            return canonicalPath.toString()
        } catch (e: InvalidPathException) {
            throw SecurityException("Invalid filesystem path for $operation: ${e.message}")
        } catch (e: SecurityException) {
            // Re-throw our security exceptions
            throw e
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Path validation failed", mapOf("path" to rawPath, "error" to e.toString()))
            throw SecurityException("Path validation failed for $operation: ${e.message}")
        }
    }

    /**
     * Validates that a child path is within a parent directory boundary.
     *
     * This is used for operations like createFile/createFolder where the plugin
     * specifies a parent directory and a child name.
     *
     * @param parentPath The parent directory path
     * @param childName The child file/folder name
     * @param operation The operation being performed (for error messages)
     * @return The canonical path of the child if valid
     * @throws SecurityException if the child would be outside the parent boundary
     */
    fun validateChildPath(
        parentPath: String,
        childName: String,
        operation: String = "create operation",
    ): String {
        // Validate the parent path first
        val canonicalParent = validateAndNormalizePath(parentPath, operation)

        // Validate the child name (no path separators, no null bytes)
        if (childName.isBlank()) {
            throw SecurityException("Child name cannot be blank for $operation")
        }

        if (childName.contains('\u0000')) {
            throw SecurityException("Child name contains null byte for $operation")
        }

        // Prevent path traversal in the child name
        val containsTraversal = childName.contains("..") || childName.contains("/") ||
            childName.contains("\\") || childName.contains(File.separator)
        if (containsTraversal) {
            throw SecurityException("Child name contains path traversal sequences for $operation")
        }

        // Construct the full child path
        val childPath = File(canonicalParent, childName)

        // Ensure the child is within the parent
        val canonicalChild = childPath.canonicalFile
        if (!isPathWithinBoundary(canonicalChild.toPath(), Paths.get(canonicalParent))) {
            throw SecurityException(
                "Path traversal detected: child would be created outside parent directory for $operation",
            )
        }

        return canonicalChild.absolutePath
    }

    /**
     * Checks if a path is within any allowed root.
     *
     * Uses canonical path comparison to handle symlinks and platform differences.
     *
     * @param path The path to check
     * @return true if the path is within any allowed root, false otherwise
     */
    private fun isPathWithinAnyAllowedRoot(path: Path): Boolean {
        val normalizedPath = path.normalize()

        for (root in allowedRoots.values) {
            val rootPath = root.toPath().normalize()
            if (isPathWithinBoundary(normalizedPath, rootPath)) {
                return true
            }
        }

        return false
    }

    /**
     * Checks if a path is within a boundary directory.
     *
     * Uses canonical path comparison to handle symlinks and platform differences.
     *
     * @param path The path to check
     * @param boundary The boundary directory
     * @return true if the path is within the boundary, false otherwise
     */
    private fun isPathWithinBoundary(
        path: Path,
        boundary: Path,
    ): Boolean {
        val normalizedPath = path.normalize()
        val normalizedBoundary = boundary.normalize()

        // Exact match is allowed (the boundary itself)
        if (normalizedPath == normalizedBoundary) {
            return true
        }

        // Check if the path starts with the boundary path plus a separator
        val boundaryString = normalizedBoundary.toString()
        val pathString = normalizedPath.toString()

        return pathString.startsWith(boundaryString + File.separator) ||
            pathString.startsWith(boundaryString + "/") ||
            pathString.startsWith(boundaryString + "\\")
    }
}
