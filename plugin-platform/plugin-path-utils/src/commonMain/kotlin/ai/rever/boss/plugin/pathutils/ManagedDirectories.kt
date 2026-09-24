package ai.rever.boss.plugin.pathutils

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.Logger

private val logger = Logger.getLogger("ManagedDirectories")

private val OWNER_ONLY_DIR_PERMISSIONS =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

private val isWindows: Boolean
    get() = System.getProperty("os.name").lowercase().contains("win")

/**
 * Guards for the managed directories the BOSS data root feeds from.
 *
 * `File.mkdirs` creates with the default umask and `File.listFiles`/`isFile`
 * follow symlinks, so neither a planted symlink nor another user's writable
 * directory was ever noticed by the plugin scan path: a symlinked jar under
 * `~/.boss/plugins` was scanned and loaded verbatim. These helpers give every
 * managed directory owner-only permissions plus an ownership check, and give
 * every scan a NOFOLLOW stat plus canonical containment.
 */
object ManagedDirectories {
    /**
     * Create (or adopt) a managed directory that only the current user can
     * enter, and **fail closed** if that cannot be guaranteed.
     *
     * The directory's contents are later scanned and loaded (plugin jars,
     * browser binaries, update payloads), so a directory another user
     * pre-created, or a symlink someone planted, is not an environment quirk
     * to warn about - it is the attack this exists to stop. An existing
     * directory is adopted only when it is a real directory owned by the
     * current user that can be locked down to owner-only; anything else
     * throws.
     *
     * On Windows this is a plain `mkdirs()`: the profile directory is already
     * per-user and POSIX permissions do not apply.
     *
     * @throws SecurityException if an owner-only directory cannot be guaranteed.
     */
    fun createOwnerOnlyDir(dir: File): File {
        // The profile directory is already per-user on Windows, and POSIX
        // permissions do not apply.
        if (isWindows) return createWindowsDir(dir)

        val path = dir.toPath()
        try {
            path.parent?.let { Files.createDirectories(it) }
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR_PERMISSIONS))
        } catch (expected: FileAlreadyExistsException) {
            // Someone got there first - us on a previous run, or another user.
            // Which one it was is exactly what verifyOwnedDirectory decides.
            verifyOwnedDirectory(path)
        } catch (e: IOException) {
            throw SecurityException("Could not create owner-only managed directory ${dir.absolutePath}", e)
        }

        return dir
    }

    /**
     * Lists the entries directly inside [dir] that pass [accept] and are plain
     * regular files whose real path stays inside [dir]'s own real path.
     *
     * `File.isFile` follows links, so a symlinked jar planted in a managed
     * directory would pass every `extension == "jar"` scan and be loaded from
     * outside the root. Entries are therefore statted with
     * [LinkOption.NOFOLLOW_LINKS] and re-checked for canonical containment;
     * an entry that fails either check is skipped and logged rather than
     * silently loaded. An unsafe [dir] (missing, a symlink, or not a
     * directory) yields an empty list rather than an exception, matching
     * `listFiles`'s "no entries" contract.
     *
     * Matching `listFiles` again: entries come back under [dir]'s own path,
     * not the resolved real root, so callers can compare them against paths
     * they built from [dir] itself.
     */
    fun listContainedRegularFiles(
        dir: File,
        accept: (File) -> Boolean = { true },
    ): List<File> {
        val path = dir.toPath()
        val linked = Files.isSymbolicLink(path)
        if (linked) {
            logger.warning("Refusing to scan a symlinked managed directory: ${dir.absolutePath}")
        }
        val realRoot =
            runCatching {
                if (!linked && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    path.toRealPath()
                } else {
                    null
                }
            }.getOrNull() ?: return emptyList()
        // List through the validated real path, not the caller's `dir`: if the
        // directory path is swapped between the realRoot check above and the
        // listing, the swap lands on a path we never validated. Each entry is
        // still re-resolved by isContainedRegularFile before it is returned.
        return realRoot
            .toFile()
            .listFiles()
            ?.mapNotNull { file ->
                if (!accept(file)) {
                    null
                } else if (isContainedRegularFile(file, realRoot)) {
                    // Return the entry under the caller's `dir`, not realRoot:
                    // `dir` was validated non-symlink above so both name the
                    // same file, and callers compare the result against paths
                    // they built from `dir` (File equality and absolutePath in
                    // installed.json, keep-sets, persisted jarPath). A
                    // realRoot-rooted File breaks every one of those wherever
                    // `dir`'s path is not canonical - an 8.3 short name on
                    // Windows, /var on macOS, any symlinked ancestor.
                    File(dir, file.name)
                } else {
                    logger.warning(
                        "Skipping a managed-directory entry that is not a contained regular file: " +
                            file.absolutePath,
                    )
                    null
                }
            }
            ?: emptyList()
    }

    /**
     * True when [file] is a plain regular file (NOFOLLOW) whose real path stays
     * inside [root]'s real path. Use on candidates from nested scans that
     * [listContainedRegularFiles] does not cover, e.g. the dev-plugin staging
     * tree two levels under the managed root.
     */
    fun isContainedRegularFile(
        file: File,
        root: File,
    ): Boolean =
        runCatching {
            val path = file.toPath()
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                path.toRealPath().startsWith(root.toPath().toRealPath())
        }.getOrDefault(false)

    private fun isContainedRegularFile(
        file: File,
        realRoot: Path,
    ): Boolean =
        runCatching {
            val path = file.toPath()
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                path.toRealPath().startsWith(realRoot)
        }.getOrDefault(false)

    private fun createWindowsDir(dir: File): File {
        requireSecure(dir.isDirectory || dir.mkdirs()) { "Could not create managed directory: ${dir.absolutePath}" }
        return dir
    }

    /**
     * An existing directory is only usable if it is a real directory (not a
     * symlink someone else planted), owned by us, and reachable by nobody else.
     */
    private fun verifyOwnedDirectory(path: Path) {
        requireSecure(!Files.isSymbolicLink(path)) {
            "Managed directory is a symlink - refusing to use it: $path"
        }
        requireSecure(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Managed directory path is not a directory: $path"
        }

        val expectedOwner = System.getProperty("user.name")
        val actualOwner =
            try {
                Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)?.name
            } catch (e: IOException) {
                throw SecurityException("Could not read owner of managed directory $path", e)
            }
        requireSecure(expectedOwner == null || actualOwner == null || actualOwner == expectedOwner) {
            "Managed directory $path is owned by '$actualOwner', not '$expectedOwner'"
        }

        // Adopt an existing directory only if we can actually lock it down.
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY_DIR_PERMISSIONS)
        } catch (expected: UnsupportedOperationException) {
            // Non-POSIX filesystem on a non-Windows OS: nothing further to enforce.
            return
        } catch (e: IOException) {
            throw SecurityException("Could not restrict managed directory $path to its owner", e)
        }

        val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        requireSecure(permissions == OWNER_ONLY_DIR_PERMISSIONS) {
            "Managed directory $path is not owner-only: $permissions"
        }
    }

    /** Fail closed with a consistent message shape. */
    private fun requireSecure(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw SecurityException(message())
    }
}
