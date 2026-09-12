package ai.rever.boss.plugin.browser

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Owns the on-disk identity and cleanup of browser profiles created only as engine fallbacks.
 *
 * Named profiles created in Settings use `browser-profile-<slug>`. Older engine fallbacks used
 * `browser-profile-<epochMillis>` too, so a prefix-only cleanup cannot distinguish durable user
 * data from crash leftovers. New fallbacks use a disjoint namespace. The exact legacy timestamp
 * shape remains recognized for migration, except when settings register that name as durable.
 */
internal object TemporaryBrowserProfiles {
    private const val PREFIX = "browser-temporary-profile-"
    private val temporaryName =
        Regex("^(?:${Regex.escape(PREFIX)}[0-9]{1,19}|browser-profile-[0-9]{13})$")

    data class CleanupResult(
        val deleted: Int,
        val failed: Int,
    )

    private enum class CleanupOutcome {
        SKIPPED,
        DELETED,
        FAILED,
    }

    fun newName(epochMillis: Long): String {
        require(epochMillis >= 0) { "Temporary profile timestamp cannot be negative" }
        return "$PREFIX$epochMillis"
    }

    /**
     * Removes direct child directories that are known temporary profiles.
     *
     * [olderThanMillis] is exclusive. Passing null removes every eligible temporary profile,
     * which is appropriate only before this process has started an engine. Registered and current
     * names are always protected, including legacy timestamp-shaped names a user created before
     * the namespaces became disjoint.
     */
    fun cleanup(
        root: Path,
        registeredProfiles: Set<String>,
        currentProfile: String,
        olderThanMillis: Long?,
    ): CleanupResult {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return CleanupResult(0, 0)

        val protectedNames = registeredProfiles + currentProfile
        var deleted = 0
        var failed = 0

        Files.newDirectoryStream(root).use { children ->
            children.forEach { child ->
                when (cleanup(child, protectedNames, olderThanMillis)) {
                    CleanupOutcome.SKIPPED -> Unit
                    CleanupOutcome.DELETED -> deleted++
                    CleanupOutcome.FAILED -> failed++
                }
            }
        }

        return CleanupResult(deleted, failed)
    }

    private fun isTemporaryName(name: String): Boolean = temporaryName.matches(name)

    private fun cleanup(
        child: Path,
        protectedNames: Set<String>,
        olderThanMillis: Long?,
    ): CleanupOutcome =
        try {
            if (!shouldDelete(child, protectedNames, olderThanMillis)) {
                CleanupOutcome.SKIPPED
            } else {
                deleteTreeWithoutFollowingLinks(child)
                CleanupOutcome.DELETED
            }
        } catch (_: IOException) {
            CleanupOutcome.FAILED
        } catch (_: SecurityException) {
            CleanupOutcome.FAILED
        }

    private fun shouldDelete(
        child: Path,
        protectedNames: Set<String>,
        olderThanMillis: Long?,
    ): Boolean =
        hasTemporaryIdentity(child, protectedNames) &&
            isRealDirectory(child) &&
            isOldEnough(child, olderThanMillis)

    private fun hasTemporaryIdentity(
        child: Path,
        protectedNames: Set<String>,
    ): Boolean {
        val name = child.fileName.toString()
        return name !in protectedNames && isTemporaryName(name)
    }

    private fun isRealDirectory(path: Path): Boolean = isDirectory(path) && !Files.isSymbolicLink(path)

    private fun isDirectory(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    private fun isOldEnough(
        path: Path,
        olderThanMillis: Long?,
    ): Boolean =
        olderThanMillis == null ||
            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() < olderThanMillis

    /** The default file-tree walk does not follow symbolic links. */
    private fun deleteTreeWithoutFollowingLinks(root: Path) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
