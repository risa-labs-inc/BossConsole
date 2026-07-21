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
     * Accepts "true" (case-insensitive), "1", and "yes" as truthy.
     */
    private fun isTruthy(value: String?): Boolean {
        if (value == null) return false
        val v = value.trim().lowercase()
        return v == "true" || v == "1" || v == "yes"
    }
}
