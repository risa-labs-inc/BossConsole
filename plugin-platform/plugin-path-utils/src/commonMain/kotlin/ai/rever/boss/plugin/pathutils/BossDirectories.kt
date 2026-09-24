package ai.rever.boss.plugin.pathutils

import java.io.File

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
    val isDevMode: Boolean =
        isTruthy(System.getProperty("boss.dev.mode")) ||
            isTruthy(System.getenv("BOSS_DEV_MODE"))

    private val rootDirName: String = if (isDevMode) ".boss_debug" else ".boss"

    /**
     * The BOSS data root directory. Created on first access as an owner-only
     * (0700) directory; an existing root is adopted only after [ManagedDirectories]
     * verifies it is a real, current-user-owned directory it can lock down.
     */
    val rootDir: File by lazy {
        ManagedDirectories.createOwnerOnlyDir(File(System.getProperty("user.home"), rootDirName))
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
