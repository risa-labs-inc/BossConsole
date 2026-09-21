package ai.rever.boss.plugin.pathutils

import java.io.File
import java.util.logging.Logger

/**
 * Single source of truth for the BOSS data directory.
 *
 * Normal mode  → ~/.boss
 * Dev mode     → ~/.boss_debug  (set boss.dev.mode=true or BOSS_DEV_MODE=true)
 *
 * This prevents debug runs from clobbering production data and vice versa.
 *
 * The root directory is created automatically on first access. Callers do not
 * need to call mkdirs() themselves.
 *
 * **Note on dev-mode detection:** Kotlin's [String.toBoolean] only recognises
 * the exact string `"true"` (case-insensitive). Unix-style truthy values like
 * `"1"` or `"yes"` are also accepted here for developer convenience.
 */
object BossDirectories {
    private val logger: Logger = Logger.getLogger("BossDirectories")

    val isDevMode: Boolean =
        isTruthy(System.getProperty("boss.dev.mode")) ||
            isTruthy(System.getenv("BOSS_DEV_MODE"))

    private val rootDirName: String = if (isDevMode) ".boss_debug" else ".boss"

    /**
     * The BOSS data root directory. Created on first access.
     */
    val rootDir: File by lazy {
        File(System.getProperty("user.home"), rootDirName).also { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warning("Failed to create BOSS data directory: ${dir.absolutePath}")
            }
        }
    }

    fun resolve(relativePath: String): File = File(rootDir, relativePath)

    /**
     * Resolves [relativePath] ensuring the resulting file is strictly contained within [rootDir].
     * Throws [SecurityException] if path traversal outside [rootDir] is detected.
     */
    fun resolveContained(relativePath: String): File {
        val resolved = resolve(relativePath)
        val canonicalRoot = rootDir.canonicalFile
        val canonicalResolved = resolved.canonicalFile
        if (!canonicalResolved.absolutePath.startsWith(canonicalRoot.absolutePath + File.separator) &&
            canonicalResolved.absolutePath != canonicalRoot.absolutePath
        ) {
            throw SecurityException("Path traversal detected: '$relativePath' resolves outside BOSS root directory")
        }
        return resolved
    }

    /**
     * Validates whether [profileId] is a safe, single-component profile identifier contained directly in [rootDir].
     */
    fun isValidProfileIdentifier(profileId: String): Boolean {
        if (profileId.isBlank()) return false
        if (profileId.contains("/") || profileId.contains("\\") || profileId.contains("..")) return false
        if (profileId == "." || File(profileId).name != profileId) return false
        return try {
            val resolved = resolveContained(profileId)
            val canonicalRoot = rootDir.canonicalFile
            val canonicalResolved = resolved.canonicalFile
            canonicalResolved.parentFile == canonicalRoot ||
                (canonicalResolved.absolutePath.startsWith(canonicalRoot.absolutePath + File.separator) &&
                    canonicalResolved.absolutePath != canonicalRoot.absolutePath)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts an arbitrary display name or raw string into a safe, bounded profile identifier.
     */
    fun sanitizeProfileIdentifier(input: String): String {
        val cleaned =
            input.trim()
                .replace(Regex("[^a-zA-Z0-9_-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .lowercase()
        return when {
            cleaned.isBlank() -> "browser-profile"
            cleaned.startsWith("browser-profile") -> cleaned
            else -> "browser-profile-$cleaned"
        }
    }

    /**
     * Recursively deletes a directory or file without following symbolic links or junctions.
     * Prevents deletion of target files when nested symlinks exist inside the directory tree.
     */
    fun deleteSafelyWithoutFollowingLinks(dirFile: File): Boolean {
        val path = dirFile.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return true
        }
        return try {
            java.nio.file.Files.walkFileTree(
                path,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        java.nio.file.Files.delete(file)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        exc: java.io.IOException?,
                    ): java.nio.file.FileVisitResult {
                        if (exc != null) throw exc
                        java.nio.file.Files.delete(dir)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Accepts "true" (case-insensitive), "1", and "yes" as truthy.
     */
    private fun isTruthy(value: String?): Boolean {
        if (value == null) return false
        val v = value.trim().lowercase()
        return v == "true" || v == "1" || v == "yes"
    }
}
