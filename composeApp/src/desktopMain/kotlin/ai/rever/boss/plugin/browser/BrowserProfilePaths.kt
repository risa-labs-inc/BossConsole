package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * Owns the filesystem boundary for browser profiles.
 *
 * Profile ids are persisted, so callers must not treat them as paths. Keeping validation,
 * resolution and deletion together makes a malformed settings file no more powerful than the
 * Settings UI. Recursive deletion deliberately does not follow links: a profile may contain a
 * symlink (or a Windows junction) to data outside the BOSS directory.
 */
internal object BrowserProfilePaths {
    const val DEFAULT_PROFILE_ID = "browser-profile"

    private const val PROFILE_PREFIX = "$DEFAULT_PROFILE_ID-"
    private const val MAX_PROFILE_ID_LENGTH = 96
    private val validProfileId = Regex("browser-profile(?:-[a-z0-9]+(?:[-_][a-z0-9]+)*)?")
    private val temporaryProfileId = Regex("browser-profile-[0-9]+")
    private val unsafeDisplayCharacters = Regex("[^a-z0-9]+")

    data class Selection(
        val current: String,
        val available: List<String>,
    )

    /** Converts a user-facing label into one bounded path component. */
    fun idForDisplayName(displayName: String): String? {
        val maximumSuffixLength = MAX_PROFILE_ID_LENGTH - PROFILE_PREFIX.length
        val suffix =
            displayName
                .trim()
                .lowercase(Locale.ROOT)
                .replace(unsafeDisplayCharacters, "-")
                .trim('-')
                .take(maximumSuffixLength)
                .trimEnd('-')
        return suffix.takeIf { it.isNotEmpty() }?.let { "$PROFILE_PREFIX$it" }
    }

    fun isValidId(profileId: String): Boolean =
        profileId.length <= MAX_PROFILE_ID_LENGTH && validProfileId.matches(profileId)

    /** Engine fallbacks use an epoch suffix; named profiles must survive temporary-profile cleanup. */
    fun isTemporaryId(profileId: String): Boolean =
        isValidId(profileId) && temporaryProfileId.matches(profileId)

    /** Filters persisted values and guarantees a usable current/default profile. */
    fun normalizeSelection(
        current: String,
        available: Iterable<String>,
    ): Selection {
        val safeCurrent = current.takeIf(::isValidId) ?: DEFAULT_PROFILE_ID
        val safeAvailable =
            buildList {
                add(DEFAULT_PROFILE_ID)
                available.filter(::isValidId).forEach { if (it !in this) add(it) }
                if (safeCurrent !in this) add(safeCurrent)
            }
        return Selection(safeCurrent, safeAvailable)
    }

    /** Resolves exactly one validated profile component beneath the BOSS data directory. */
    fun resolve(
        profileId: String,
        root: Path = BossDirectories.rootDir.toPath(),
    ): Path {
        require(isValidId(profileId)) { "Invalid browser profile identifier" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = normalizedRoot.resolve(profileId).normalize()
        require(target.parent == normalizedRoot) { "Browser profile path escapes the BOSS data directory" }
        return target
    }

    /**
     * Deletes a validated profile tree without following symbolic links or junctions.
     *
     * Returns false after a partial deletion if any entry could not be removed. Missing profiles
     * are already successfully deleted.
     */
    fun delete(
        profileId: String,
        root: Path = BossDirectories.rootDir.toPath(),
    ): Boolean {
        val target = resolve(profileId, root)
        if (!Files.exists(target, NOFOLLOW_LINKS)) return true

        var failed = false
        try {
            Files.walkFileTree(
                target,
                emptySet(),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        failed = !deleteEntry(file) || failed
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) failed = true
                        failed = !deleteEntry(dir) || failed
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        failed = true
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: IOException) {
            failed = true
        } catch (_: SecurityException) {
            failed = true
        }
        return !failed && !Files.exists(target, NOFOLLOW_LINKS)
    }

    private fun deleteEntry(path: Path): Boolean =
        try {
            Files.deleteIfExists(path)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
