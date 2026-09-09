package ai.rever.boss.recovery.paths

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

        val isContained = ancestorPathStr == rootPathStr ||
            ancestorPathStr.startsWith(rootPathStr + File.separator) ||
            ancestorPathStr.startsWith(rootPathStr + "/")

        if (!isContained) {
            throw SecurityException("Path traversal outside project root: '$relativePath' resolved to '$ancestorPathStr'")
        }

        return targetFile
    }

    /**
     * Returns whether [fileOrDir] should be excluded from checkpointing based on [excludedPatterns].
     */
    fun isExcluded(
        name: String,
        excludedPatterns: Set<String>,
    ): Boolean {
        return excludedPatterns.contains(name) || name.startsWith(".git")
    }

    /**
     * Checks if [dir] is a safe directory to recurse into during scanning.
     * Prevents infinite loops from cyclical symlinks and prevents traversing outside [rootCanonical].
     */
    fun isSafeDirectoryToRecurse(
        dir: File,
        rootCanonical: File,
    ): Boolean {
        if (java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return false
        }
        val canonical =
            try {
                dir.canonicalFile
            } catch (_: Exception) {
                return false
            }
        val rootPathStr = rootCanonical.path
        val childPathStr = canonical.path
        return childPathStr == rootPathStr ||
            childPathStr.startsWith(rootPathStr + File.separator) ||
            childPathStr.startsWith(rootPathStr + "/")
    }

    /**
     * Checks if [file] is contained within [rootCanonical] and is not an escaping symlink.
     */
    fun isContainedFile(
        file: File,
        rootCanonical: File,
    ): Boolean {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return false
        }
        val canonical =
            try {
                file.canonicalFile
            } catch (_: Exception) {
                return false
            }
        val rootPathStr = rootCanonical.path
        val childPathStr = canonical.path
        return childPathStr.startsWith(rootPathStr + File.separator) ||
            childPathStr.startsWith(rootPathStr + "/")
    }
}
