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
 * **BOSS profiles.** A process started with `BOSS_PROFILE=<id>` (or `-Dboss.profile=<id>`) is a
 * separate BOSS account on the same machine: its [rootDir] is `<base>/profiles/<id>`, so its auth
 * session, browser profile, plugins, plugin data, Spaces and single-instance channel are all its
 * own. Without the variable nothing changes - [rootDir] is [baseDir], exactly as before. The
 * environment variable is the primary carrier because child processes (out-of-process plugins,
 * kernel services, terminals) inherit it, so every process a profile spawns agrees on the root.
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

    /** Environment variable naming the BOSS profile this process runs as. */
    const val PROFILE_ENV = "BOSS_PROFILE"

    /** System property alternative to [PROFILE_ENV], for `./gradlew run` style launches. */
    const val PROFILE_PROPERTY = "boss.profile"

    /** Directory under [baseDir] that holds one root per profile. */
    const val PROFILES_DIR_NAME = "profiles"

    /** The file in a profile root that registers it; a root without one is not a profile. */
    const val PROFILE_FILE_NAME = "profile.json"

    /** Opt-in for the hidden profiles feature (also `-Dboss.profiles.enabled=true`). */
    const val PROFILES_ENABLED_ENV = "BOSS_PROFILES_ENABLED"

    const val PROFILES_ENABLED_PROPERTY = "boss.profiles.enabled"

    private val PROFILE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

    /** Ids that name the main profile rather than a separate one. */
    private val MAIN_PROFILE_ALIASES = setOf("main", "default")

    val isDevMode: Boolean =
        isTruthy(System.getProperty("boss.dev.mode")) ||
            isTruthy(System.getenv("BOSS_DEV_MODE"))

    private val rootDirName: String = if (isDevMode) ".boss_debug" else ".boss"

    /**
     * The profile this process runs as, or null for the main profile.
     *
     * An id that fails [isValidProfileId] is ignored with a warning rather than used: it becomes
     * a path segment, so `../x` must never reach [File]. So is an id no profile was created for, so
     * a mistyped `BOSS_PROFILE` never materialises a new, signed-out root.
     */
    val profileId: String? =
        registeredProfileId(resolveProfileId(System.getProperty(PROFILE_PROPERTY), System.getenv(PROFILE_ENV))) { id ->
            File(File(File(System.getProperty("user.home"), rootDirName), PROFILES_DIR_NAME), id)
                .resolve(PROFILE_FILE_NAME)
                .isFile
        }

    /** Whether this process runs as a separate BOSS profile. */
    val isProfile: Boolean
        get() = profileId != null

    /**
     * Whether the hidden profiles feature is on in this process: opted in, or running as a
     * profile. Off by default; while off, the host's sign-in path is the one it had before profiles.
     */
    val profilesEnabled: Boolean =
        isProfile ||
            isTruthy(System.getProperty(PROFILES_ENABLED_PROPERTY)) ||
            isTruthy(System.getenv(PROFILES_ENABLED_ENV))

    /**
     * The main profile's data root (`~/.boss` or `~/.boss_debug`), whichever profile this
     * process runs as. Profiles themselves live under it, in [profilesDir].
     */
    val baseDir: File by lazy {
        File(System.getProperty("user.home"), rootDirName).also(::ensureDirectory)
    }

    /**
     * The BOSS data root directory for THIS process: [baseDir] for the main profile,
     * `<baseDir>/profiles/<id>` for a separate one. Created on first access.
     */
    val rootDir: File by lazy {
        val id = profileId
        if (id == null) baseDir else profileRoot(id).also(::ensureDirectory)
    }

    fun resolve(relativePath: String): File = File(rootDir, relativePath)

    /** Where every profile root lives: `<baseDir>/profiles`. */
    fun profilesDir(): File = File(baseDir, PROFILES_DIR_NAME)

    /**
     * The data root of profile [id], whether or not it exists yet.
     *
     * @throws IllegalArgumentException when [id] is not a valid profile id.
     */
    fun profileRoot(id: String): File {
        require(isValidProfileId(id)) { "Invalid BOSS profile id: '$id'" }
        return File(profilesDir(), id)
    }

    /**
     * Lowercase letters, digits and '-', starting with a letter or digit, at most 32 characters.
     * Short on purpose: the profile root carries a Unix-domain socket path, which macOS caps at
     * 104 bytes. `main` and `default` are reserved for the main profile.
     */
    fun isValidProfileId(id: String): Boolean = PROFILE_ID_PATTERN.matches(id) && id !in MAIN_PROFILE_ALIASES

    internal fun resolveProfileId(
        property: String?,
        env: String?,
    ): String? {
        val raw = listOf(property, env).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
        val id = raw?.lowercase()?.takeIf { it !in MAIN_PROFILE_ALIASES }
        if (id != null && !PROFILE_ID_PATTERN.matches(id)) {
            logger.warning("Ignoring invalid BOSS profile id '$raw'; running as the main profile")
            return null
        }
        return id
    }

    internal fun registeredProfileId(
        id: String?,
        isRegistered: (String) -> Boolean,
    ): String? {
        if (id == null || isRegistered(id)) return id
        logger.warning("Ignoring BOSS profile '$id': no such profile was created; running as the main profile")
        return null
    }

    private fun ensureDirectory(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Failed to create BOSS data directory: ${dir.absolutePath}")
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
