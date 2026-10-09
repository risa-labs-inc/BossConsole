package ai.rever.boss.plugin.pathutils

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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

    private val canonicalRootDir: File by lazy { rootDir.canonicalFile }

    /**
     * Resolve a durable BOSS path below [rootDir].
     *
     * This is an enforcement boundary, not a convenience join. A plugin id, record name or
     * future configuration value must never turn a state path into an absolute path or escape
     * through `..` or an existing symlink. User-selected project files are deliberately handled
     * by the file-access APIs instead of this durable-state API.
     */
    fun resolve(relativePath: String): File = resolveUnderRoot(canonicalRootDir, relativePath)

    internal fun resolveUnderRoot(
        rootDirectory: File,
        relativePath: String,
    ): File {
        require(relativePath.isNotBlank()) { "BOSS state paths must not be blank" }

        val relative = File(relativePath)
        require(!relative.isAbsolute) { "BOSS state paths must be relative to ${rootDirectory.absolutePath}" }

        val root = rootDirectory.toPath().toRealPath()
        val lexicalTarget = root.resolve(relativePath).normalize()
        require(lexicalTarget != root && lexicalTarget.startsWith(root)) {
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
    fun contains(file: File): Boolean = containsUnderRoot(canonicalRootDir, file)

    internal fun containsUnderRoot(
        rootDirectory: File,
        file: File,
    ): Boolean =
        runCatching {
            val root = rootDirectory.toPath().toRealPath()
            val lexicalTarget = file.toPath().toAbsolutePath().normalize()
            lexicalTarget.startsWith(root) && resolveThroughExistingAncestor(lexicalTarget).startsWith(root)
        }.getOrDefault(false)

    private fun resolveThroughExistingAncestor(lexicalTarget: Path): Path {
        var existingAncestor = lexicalTarget
        while (!Files.exists(existingAncestor, NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.parent
                ?: error("BOSS state path has no existing ancestor: $lexicalTarget")
        }
        return existingAncestor
            .toRealPath()
            .resolve(existingAncestor.relativize(lexicalTarget))
            .normalize()
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
