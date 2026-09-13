package ai.rever.boss.plugin.browser

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal data class TemporaryProfileCleanupOperations(
    val isProfileInUse: (Path) -> Boolean,
    val deleteProfile: (Path) -> Unit,
)

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
    private val temporaryName = Regex("^${Regex.escape(PREFIX)}[0-9]{1,19}$")
    private val legacyTemporaryName = Regex("^browser-profile-[0-9]{13}$")
    private val defaultOperations =
        TemporaryProfileCleanupOperations(
            isProfileInUse = ::hasLiveChromiumOwner,
            deleteProfile = ::deleteTreeWithoutFollowingLinks,
        )

    data class CleanupFailure(
        val profile: String,
        val reason: String,
    )

    data class CleanupResult(
        val deleted: Int,
        val failed: Int,
        val firstFailure: CleanupFailure? = null,
    )

    private sealed interface CleanupOutcome {
        data object Skipped : CleanupOutcome

        data object Deleted : CleanupOutcome

        data class Failed(
            val failure: CleanupFailure,
        ) : CleanupOutcome
    }

    private class CleanupAccumulator {
        private var deleted = 0
        private var failed = 0
        private var firstFailure: CleanupFailure? = null

        fun record(outcome: CleanupOutcome) {
            when (outcome) {
                CleanupOutcome.Skipped -> {
                    return
                }

                CleanupOutcome.Deleted -> {
                    deleted++
                }

                is CleanupOutcome.Failed -> {
                    failed++
                    if (firstFailure == null) firstFailure = outcome.failure
                }
            }
        }

        fun result(): CleanupResult = CleanupResult(deleted, failed, firstFailure)
    }

    fun newName(epochMillis: Long): String {
        require(epochMillis >= 0) { "Temporary profile timestamp cannot be negative" }
        return "$PREFIX$epochMillis"
    }

    /**
     * Removes direct child directories that are known temporary profiles.
     *
     * [olderThanMillis] is exclusive. Passing null removes every eligible temporary profile,
     * which is appropriate only before this process has started an engine.
     * [legacyProfileNamesTrusted] must be true before an ambiguous legacy timestamp-shaped name is
     * eligible. Registered, current, and active fallback names belong in [protectedProfiles].
     * The configured root is trusted and resolved once; child symlinks are never followed, and a
     * candidate with a live Chromium owner is retained regardless of its age.
     */
    fun cleanup(
        root: Path,
        protectedProfiles: Set<String>,
        legacyProfileNamesTrusted: Boolean,
        olderThanMillis: Long?,
        operations: TemporaryProfileCleanupOperations = defaultOperations,
    ): CleanupResult {
        if (!Files.isDirectory(root)) return CleanupResult(0, 0)
        val resolvedRoot = root.toRealPath()

        val accumulator = CleanupAccumulator()

        Files.newDirectoryStream(resolvedRoot).use { children ->
            children.forEach { child ->
                accumulator.record(
                    cleanupCandidate(
                        child = child,
                        protectedProfiles = protectedProfiles,
                        legacyProfileNamesTrusted = legacyProfileNamesTrusted,
                        olderThanMillis = olderThanMillis,
                        operations = operations,
                    ),
                )
            }
        }

        return accumulator.result()
    }

    private fun cleanupCandidate(
        child: Path,
        protectedProfiles: Set<String>,
        legacyProfileNamesTrusted: Boolean,
        olderThanMillis: Long?,
        operations: TemporaryProfileCleanupOperations,
    ): CleanupOutcome =
        try {
            if (!shouldDelete(child, protectedProfiles, legacyProfileNamesTrusted, olderThanMillis, operations)) {
                CleanupOutcome.Skipped
            } else {
                operations.deleteProfile(child)
                CleanupOutcome.Deleted
            }
        } catch (_: NoSuchFileException) {
            CleanupOutcome.Skipped
        } catch (e: IOException) {
            CleanupOutcome.Failed(child.failure(e))
        } catch (e: SecurityException) {
            CleanupOutcome.Failed(child.failure(e))
        }

    private fun shouldDelete(
        child: Path,
        protectedProfiles: Set<String>,
        legacyProfileNamesTrusted: Boolean,
        olderThanMillis: Long?,
        operations: TemporaryProfileCleanupOperations,
    ): Boolean =
        hasTemporaryIdentity(child, protectedProfiles, legacyProfileNamesTrusted) &&
            isRealDirectory(child) &&
            !operations.isProfileInUse(child) &&
            isOldEnough(child, olderThanMillis)

    private fun hasTemporaryIdentity(
        child: Path,
        protectedProfiles: Set<String>,
        legacyProfileNamesTrusted: Boolean,
    ): Boolean {
        val name = child.fileName.toString()
        return name !in protectedProfiles &&
            (
                temporaryName.matches(name) ||
                    (legacyProfileNamesTrusted && legacyTemporaryName.matches(name))
            )
    }

    private fun isRealDirectory(path: Path): Boolean = isDirectory(path) && !Files.isSymbolicLink(path)

    private fun isDirectory(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    private fun isOldEnough(
        path: Path,
        olderThanMillis: Long?,
    ): Boolean =
        olderThanMillis == null ||
            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() < olderThanMillis

    private fun Path.failure(error: Exception): CleanupFailure =
        CleanupFailure(
            profile = fileName.toString(),
            reason = error.message ?: error::class.simpleName ?: "unknown error",
        )

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
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

private fun hasLiveChromiumOwner(profile: Path): Boolean {
    val singletonLock = profile.resolve("SingletonLock")
    if (!Files.exists(singletonLock, LinkOption.NOFOLLOW_LINKS)) return false

    return if (Files.isSymbolicLink(singletonLock)) {
        val ownerPid =
            Files
                .readSymbolicLink(singletonLock)
                .toString()
                .substringAfterLast('-')
                .toLongOrNull()
        ownerPid == null || ProcessHandle.of(ownerPid).map(ProcessHandle::isAlive).orElse(false)
    } else {
        regularFileLockIsHeld(singletonLock)
    }
}

private fun regularFileLockIsHeld(lockFile: Path): Boolean =
    try {
        FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
            val acquired =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (acquired == null) {
                true
            } else {
                acquired.release()
                false
            }
        }
    } catch (_: IOException) {
        true
    } catch (_: SecurityException) {
        true
    }
