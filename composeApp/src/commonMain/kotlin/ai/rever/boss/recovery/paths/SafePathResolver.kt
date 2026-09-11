package ai.rever.boss.recovery.paths

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException

/**
 * Validates and resolves filesystem paths with strict containment inside a target project root.
 *
 * INVARIANTS:
 * - No path may escape canonical [projectRoot].
 * - Paths containing null bytes or directory traversal sequences ("..") are rejected.
 * - Non-existent target files are safely resolved against existing parent directories.
 * - Relative paths in manifests are always normalized to POSIX style (forward slashes '/').
 */
object SafePathResolver {
    private val logger = BossLogger.forComponent("SafePathResolver")

    private val SAFE_IDENTIFIER_REGEX = Regex("^[a-zA-Z0-9_\\-\\.]+$")

    /**
     * Validates that an identifier (e.g., missionId, checkpointId) contains only safe characters
     * and cannot perform path traversal.
     *
     * @throws IllegalArgumentException if the identifier is blank.
     * @throws SecurityException if the identifier contains path separators, null bytes, '..', or illegal characters.
     */
    @Suppress("UseRequire", "ThrowsCount") // Contract: IAE for blank, SecurityException for traversal/charset.
    fun validateIdentifier(
        id: String,
        paramName: String = "identifier",
    ): String {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("$paramName must not be blank")
        }
        if (hasTraversalOrSeparator(trimmed)) {
            throw SecurityException("Path traversal sequence or path separator detected in $paramName: '$id'")
        }
        if (!SAFE_IDENTIFIER_REGEX.matches(trimmed)) {
            throw SecurityException(
                "Invalid characters in $paramName: '$id'. Only alphanumeric characters, '.', '_', and '-' are allowed.",
            )
        }
        return trimmed
    }

    /**
     * Resolves the canonical root path for [projectRoot].
     * @throws SecurityException or [IOException] if [projectRoot] does not exist or cannot be canonicalized.
     */
    fun canonicalRoot(projectRoot: File): File {
        if (!projectRoot.exists()) {
            throw IOException("Project root does not exist: ${projectRoot.path}")
        }
        if (!projectRoot.isDirectory) {
            throw IOException("Project root must be a directory: ${projectRoot.path}")
        }
        return projectRoot.canonicalFile
    }

    /**
     * Normalizes a relative path string by stripping leading slashes, converting backslashes to forward slashes,
     * and ensuring it contains no path traversal sequences.
     */
    fun normalizeRelativePath(relativePath: String): String {
        val sanitized = relativePath.trim().replace('\\', '/')
        val clean = sanitized.trimStart('/')

        if (clean.contains("\u0000")) {
            throw SecurityException("Path contains null byte: $relativePath")
        }

        val segments = clean.split('/')
        if (segments.any { it == ".." }) {
            throw SecurityException("Directory traversal '..' detected in path: $relativePath")
        }

        return clean
    }

    private fun hasTraversalOrSeparator(id: String): Boolean =
        id.contains("/") ||
            id.contains("\\") ||
            id.contains("..") ||
            id.contains("\u0000")

    /**
     * Validates that [relativePath] resolves strictly inside [projectRoot].
     *
     * Correctly handles non-existent targets by checking the nearest existing parent directory.
     *
     * @return The resolved target [File].
     * @throws SecurityException if the target path resolves outside [projectRoot].
     */
    fun resolveSafeChild(
        projectRoot: File,
        relativePath: String,
    ): File {
        val rootCanonical = canonicalRoot(projectRoot)
        val normalizedRel = normalizeRelativePath(relativePath)

        val targetFile = File(rootCanonical, normalizedRel)

        // Find nearest existing ancestor to check canonical containment
        var ancestor: File? = targetFile
        while (ancestor != null && !ancestor.exists()) {
            ancestor = ancestor.parentFile
        }

        val validAncestor = ancestor ?: rootCanonical
        val ancestorCanonical = validAncestor.canonicalFile

        val rootPathStr = rootCanonical.path
        val ancestorPathStr = ancestorCanonical.path

        val isContained =
            ancestorPathStr == rootPathStr ||
                ancestorPathStr.startsWith(rootPathStr + File.separator) ||
                ancestorPathStr.startsWith(rootPathStr + "/")

        if (!isContained) {
            throw SecurityException(
                "Path traversal outside project root: '$relativePath' resolved to '$ancestorPathStr'",
            )
        }

        return targetFile
    }

    /**
     * Returns whether [fileOrDir] should be excluded from checkpointing based on [excludedPatterns].
     */
    fun isExcluded(
        name: String,
        excludedPatterns: Set<String>,
    ): Boolean = excludedPatterns.contains(name) || name.startsWith(".git")

    /**
     * Checks if [dir] is a safe directory to recurse into during scanning.
     * Prevents infinite loops from cyclical symlinks and prevents traversing outside [rootCanonical].
     */
    fun isSafeDirectoryToRecurse(
        dir: File,
        rootCanonical: File,
    ): Boolean {
        if (java.nio.file.Files
                .isSymbolicLink(dir.toPath())
        ) {
            return false
        }
        return canonicalOrNull(dir)?.let { isContainedPath(it.path, rootCanonical.path, true) } ?: false
    }

    /**
     * Checks if [file] is contained within [rootCanonical] and is not an escaping symlink.
     */
    fun isContainedFile(
        file: File,
        rootCanonical: File,
    ): Boolean {
        if (java.nio.file.Files
                .isSymbolicLink(file.toPath())
        ) {
            return false
        }
        return canonicalOrNull(file)?.let { isContainedPath(it.path, rootCanonical.path, false) } ?: false
    }

    @Suppress("TooGenericExceptionCaught") // Uncanonicalizable path is logged and treated as unsafe.
    private fun canonicalOrNull(file: File): File? =
        try {
            file.canonicalFile
        } catch (e: Exception) {
            logger.warn(
                LogCategory.SYSTEM,
                "Path cannot be canonicalized",
                mapOf("path" to file.path, "error" to (e.message ?: e::class.simpleName)),
            )
            null
        }

    private fun isContainedPath(
        childCanonical: String,
        rootCanonical: String,
        allowRootItself: Boolean,
    ): Boolean {
        val inside =
            childCanonical.startsWith(rootCanonical + File.separator) ||
                childCanonical.startsWith(rootCanonical + "/")
        return inside || (allowRootItself && childCanonical == rootCanonical)
    }
}
