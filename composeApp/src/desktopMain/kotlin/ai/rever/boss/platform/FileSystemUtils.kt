package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.revealInFileManager
import ai.rever.boss.utils.windowsExplorer
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = BossLogger.forComponent("FileSystemUtils")

/**
 * Cross-platform file system utilities for downloads.
 */
object FileSystemUtils {
    /**
     * Opens the file manager and reveals the specified file.
     * - macOS: Uses 'open -R' to reveal in Finder
     * - Windows: Uses 'explorer /select,' to select in Explorer
     * - Linux: Opens the parent directory in file manager (xdg-open)
     *
     * @param filePath Absolute path to the file to reveal
     */
    fun revealInFolder(filePath: String) {
        revealInFileManager(filePath)
    }

    /**
     * Opens the specified file with the system default application.
     *
     * @param filePath Absolute path to the file to open
     */
    fun openFile(filePath: String) {
        try {
            val osName = System.getProperty("os.name").orEmpty()
            val file = File(filePath)

            if (!file.exists()) {
                logger.warn(LogCategory.FILE, "Cannot open file - does not exist", mapOf("path" to filePath))
                return
            }

            val command = openCommand(osName, file.absolutePath)
            if (command == null) {
                logger.warn(LogCategory.FILE, "Open file not supported on this OS", mapOf("os" to osName))
                return
            }
            Runtime.getRuntime().exec(command)
        } catch (e: IOException) {
            logger.warn(LogCategory.FILE, "Failed to open file", error = e)
        }
    }

    /**
     * Checks if there is sufficient disk space available for a download.
     * Includes a 100MB safety buffer.
     *
     * @param destinationPath Path where the file will be saved
     * @param requiredBytes Number of bytes needed for the download
     * @return true if sufficient space is available, false otherwise
     */
    fun hasSufficientDiskSpace(
        destinationPath: String,
        requiredBytes: Long,
    ): Boolean {
        return try {
            val file = File(destinationPath)
            val parentDir = file.parentFile ?: return true // Can't check, assume OK

            val usableSpace = parentDir.usableSpace
            val safetyBuffer = 100 * 1024 * 1024L // 100MB buffer

            usableSpace >= (requiredBytes + safetyBuffer)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Error checking disk space", error = e)
            true // Assume OK if we can't check
        }
    }

    /**
     * Paths handed out by [generateUniqueFilePath] whose file does not exist yet, by owner.
     *
     * "Is the name free?" cannot be answered by [File.exists] alone. A download's file is not
     * created until bytes arrive, so two downloads of `report.pdf` started before either lands
     * both saw a free name and both resolved to the same path - the second silently truncating
     * the first.
     *
     * **Keyed by owner, not just by path.** A bare `release(path)` let one download free
     * another's claim: the save-dialog path returns whatever the user typed, which can be a
     * path the auto path already claimed, and that download's terminal handler would then
     * release a claim it never took. So a claim records who holds it and only that holder can
     * give it back.
     *
     * Entries are advisory and self-healing. Nothing guarantees a terminal event - the engine
     * can be disposed mid-download, and `action.download()` itself can throw after the claim -
     * so a claim whose file still does not exist after [STALE_CLAIM_MS] is treated as
     * abandoned. Without that, one lost release pushes every later download of that name onto
     * a numbered suffix for the life of the process, with nothing on disk to explain why: the
     * exact confusion this whole mechanism exists to remove.
     */
    private val claimedPaths = ConcurrentHashMap<String, Claim>()

    private data class Claim(
        val owner: String,
        val takenAtMs: Long,
    )

    /** How long a claim with no file behind it is honoured before it is treated as abandoned. */
    private const val STALE_CLAIM_MS = 10 * 60 * 1000L

    /** Number of `(n)` suffixes tried before falling back to a name that cannot collide. */
    private const val MAX_COLLISION_SUFFIX = 999

    /**
     * Generates a unique file path by appending (1), (2), etc. if the name is taken.
     * Example: "file.txt" -> "file (1).txt" -> "file (2).txt"
     *
     * The returned path is **claimed for [owner]** until [releaseFilePath] is called with the
     * same owner, so a caller that has not written its file yet still blocks a concurrent
     * caller from the same name.
     *
     * @param directory Directory where file will be saved
     * @param fileName Original file name
     * @param owner Identifies the caller, so only it can release the claim
     * @return Absolute path to a unique file (may be the original if no collision)
     */
    fun generateUniqueFilePath(
        directory: String,
        fileName: String,
        owner: String,
    ): String {
        val dir = File(directory)

        // Ensure directory exists
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val original = File(dir, fileName)
        val extension = original.extension
        val stem = original.nameWithoutExtension

        fun suffixed(suffix: String): File {
            val name = if (extension.isEmpty()) "$stem ($suffix)" else "$stem ($suffix).$extension"
            return File(dir, name)
        }

        val claimed =
            if (claim(original, owner)) {
                original
            } else {
                (1..MAX_COLLISION_SUFFIX)
                    .asSequence()
                    .map { suffixed(it.toString()) }
                    .firstOrNull { claim(it, owner) }
                    ?: run {
                        // The old loop *returned the colliding path* once it gave up,
                        // overwriting the very file the counter exists to protect. Retry until
                        // a claim actually succeeds rather than assuming one will: a random
                        // name that happens to be taken, returned unclaimed, is that bug again.
                        logger.warn(
                            LogCategory.FILE,
                            "Exhausted numbered suffixes for a download name - using a unique suffix",
                            mapOf("fileName" to fileName),
                        )
                        generateSequence { suffixed(UUID.randomUUID().toString()) }
                            .first { claim(it, owner) }
                    }
            }
        return claimed.absolutePath
    }

    /**
     * Take [file]'s path for [owner] if nothing live holds it and it is not already on disk.
     *
     * The key is case-folded, because APFS and NTFS are case-insensitive by default: without
     * it `report.pdf` and `Report.pdf` are one file but two claims, and a server-supplied
     * `Content-Disposition` makes that trivial to arrange.
     */
    private fun claim(
        file: File,
        owner: String,
    ): Boolean {
        val key = claimKey(file)
        val now = System.currentTimeMillis()
        val mine = Claim(owner, now)
        var taken = false
        // Resolve against the map first, so two threads cannot both pass the exists() check.
        claimedPaths.compute(key) { _, current ->
            val abandoned = current != null && now - current.takenAtMs > STALE_CLAIM_MS && !file.exists()
            if (current == null || abandoned) {
                taken = true
                mine
            } else {
                current
            }
        }
        if (taken && file.exists()) {
            // A file appeared at the name between the map write and here. Give it straight
            // back rather than handing out a path whose contents the caller would destroy.
            claimedPaths.remove(key, mine)
            taken = false
        }
        return taken
    }

    private fun claimKey(file: File): String = file.absolutePath.lowercase()

    /**
     * Give back a path claimed by [generateUniqueFilePath].
     *
     * A no-op unless [owner] is the holder, so a download releasing its own path cannot free
     * one that belongs to another. Call it once the file exists (so [File.exists] takes over)
     * or once the transfer has failed.
     */
    fun releaseFilePath(
        path: String,
        owner: String,
    ) {
        val key = claimKey(File(path))
        claimedPaths.computeIfPresent(key) { _, current -> if (current.owner == owner) null else current }
    }

    /**
     * Ensures the parent directory of a file path exists, creating it if necessary.
     *
     * @param filePath Absolute path to a file
     * @return true if directory exists or was created, false on failure
     */
    fun ensureParentDirectoryExists(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val parentDir = file.parentFile ?: return true

            if (!parentDir.exists()) {
                parentDir.mkdirs()
            } else {
                true
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to create parent directory", error = e)
            false
        }
    }

    /**
     * Attempts to delete a partial/failed download file.
     * Silently fails if file doesn't exist or can't be deleted.
     *
     * @param filePath Path to the file to clean up
     */
    fun cleanupPartialFile(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
                logger.debug(LogCategory.FILE, "Cleaned up partial download", mapOf("path" to filePath))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to clean up partial file", error = e)
        }
    }

    /**
     * Checks if a directory is writable.
     *
     * @param directoryPath Path to the directory
     * @return true if directory is writable, false otherwise
     */
    fun isDirectoryWritable(directoryPath: String): Boolean =
        try {
            val dir = File(directoryPath)
            dir.isDirectory && dir.canWrite()
        } catch (e: Exception) {
            logger.debug(
                LogCategory.FILE,
                "Writability check failed - treating directory as not writable",
                mapOf("path" to directoryPath, "error" to e.toString()),
            )
            false
        }
}

/**
 * The command that opens [path] with the OS default application, or null on an OS with no
 * launcher of its own.
 *
 * **Windows goes through Explorer, not `cmd /c start`.** A command line handed to `cmd` is
 * re-parsed by the shell, and the JDK quotes an argument only when it contains a space or a tab -
 * the same rule `revealInFileManager` documents from the other direction ("when the path contains
 * spaces the JDK quotes the ENTIRE token"). So `&`, `^`, `|`, `<` and `>` reached `cmd` unquoted
 * in any path without a space: `C:\Users\dev\Downloads\R&D.pdf` ran
 * `start "" C:\Users\dev\Downloads\R` and took `D.pdf` as a second command. A path that
 * happened to contain a space was quoted whole and worked, which is why this survived.
 *
 * `explorer.exe <path>` performs the same default-application open with no shell in the middle,
 * and is already how this file's reveal path talks to Windows. [explorer] is Windows' own
 * `%SystemRoot%\explorer.exe` where that resolves (see [windowsExplorer]), so a same-named binary
 * earlier in the search path cannot stand in for it.
 *
 * **[path] must already be an OS-absolute path.** Explorer's parser is non-standard and honours
 * switches such as `/select,` and `/root,` in its operand (see `revealInFileManager`), so
 * `openCommand("Windows 11", "/select,C:\Users")` would run one. Nothing here prevents that; what
 * does is [FileSystemUtils.openFile] passing `file.absolutePath`, which on Windows always starts
 * with a drive or `\\`, so a file named `/n` or `-e` can never be the start of the operand.
 * Keep that true for any new caller.
 *
 * File scope rather than a member: [FileSystemUtils] is at detekt's `TooManyFunctions` ceiling.
 */
internal fun openCommand(
    osName: String,
    path: String,
    explorer: String = windowsExplorer(),
): Array<String>? {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> arrayOf("open", path)
        os.contains("windows") -> arrayOf(explorer, path)
        os.contains("linux") -> arrayOf("xdg-open", path)
        else -> null
    }
}
