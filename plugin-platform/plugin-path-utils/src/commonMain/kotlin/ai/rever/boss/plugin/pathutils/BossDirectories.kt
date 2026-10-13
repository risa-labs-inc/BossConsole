package ai.rever.boss.plugin.pathutils

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
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

    private val realRootDir: Path by lazy { realRoot(rootDir) }

    /**
     * Resolve a durable BOSS path below [rootDir].
     *
     * This is an enforcement boundary, not a convenience join. A plugin id, record name or
     * future configuration value must never turn a state path into an absolute path or escape
     * through `..` or an existing symlink. User-selected project files are deliberately handled
     * by the file-access APIs instead of this durable-state API. First use creates and resolves
     * the root once for the process. An unusable or unresolvable root fails closed instead of
     * returning a path that later writers could interpret differently.
     */
    fun resolve(relativePath: String): File = resolveUnderRealRoot(realRootDir, relativePath)

    internal fun resolveUnderRoot(
        rootDirectory: File,
        relativePath: String,
    ): File = resolveUnderRealRoot(realRoot(rootDirectory), relativePath)

    private fun resolveUnderRealRoot(
        root: Path,
        relativePath: String,
    ): File {
        val relative = File(relativePath)
        require(!relative.isAbsolute) { "BOSS state paths must be relative to $root" }

        val lexicalTarget = root.resolve(relativePath).normalize()
        require(lexicalTarget.startsWith(root)) {
            "BOSS state paths must remain under $root"
        }

        // File.getCanonicalFile does not reliably resolve a symlinked parent on Windows when the
        // final child does not exist. Resolve the nearest existing ancestor instead, then append
        // only the suffix that cannot contain another existing symlink.
        val target = resolveThroughExistingAncestor(lexicalTarget)
        require(target.startsWith(root)) {
            "BOSS state paths must remain under $root"
        }
        return target.toFile()
    }

    /** Whether [file] is contained by the durable BOSS state root. */
    fun contains(file: File): Boolean =
        runCatching { containsUnderRealRoot(realRootDir, file) }
            .getOrDefault(false)

    internal fun containsUnderRoot(
        rootDirectory: File,
        file: File,
    ): Boolean =
        runCatching { containsUnderRealRoot(realRoot(rootDirectory), file) }
            .getOrDefault(false)

    private fun containsUnderRealRoot(
        root: Path,
        file: File,
    ): Boolean =
        runCatching {
            val lexicalTarget = file.toPath().toAbsolutePath().normalize()
            resolveThroughExistingAncestor(lexicalTarget).startsWith(root)
        }.getOrDefault(false)

    private fun realRoot(rootDirectory: File): Path {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        // createDirectories rejects an existing symlink even when it resolves to a directory on
        // some JDK providers. A symlinked state root is useful when users relocate ~/.boss to a
        // larger volume, so accept it while still resolving the real containment boundary below.
        if (!Files.isDirectory(root)) {
            Files.createDirectories(root)
        }
        return root.toRealPath()
    }

    private fun resolveThroughExistingAncestor(lexicalTarget: Path): Path {
        var existingAncestor = lexicalTarget
        while (!Files.exists(existingAncestor, NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.parent
                ?: error("BOSS state path has no existing ancestor: $lexicalTarget")
        }
        val realAncestor =
            try {
                existingAncestor.toRealPath()
            } catch (error: NoSuchFileException) {
                throw IllegalArgumentException("BOSS state path contains a dangling symlink: $lexicalTarget", error)
            }
        return realAncestor.resolve(existingAncestor.relativize(lexicalTarget)).normalize()
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
