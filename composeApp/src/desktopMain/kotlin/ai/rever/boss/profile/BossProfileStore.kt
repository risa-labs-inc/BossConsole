package ai.rever.boss.profile

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** How a BOSS profile signs in, which also decides where its windows run. */
@Serializable
enum class ProfileAuthMode {
    /**
     * The main BOSS account. Its windows open INSIDE the main BOSS process and share its sign-in,
     * plugins and plugin settings; only their browser tabs are separate, on a browser profile of
     * their own ([ai.rever.boss.plugin.browser.WindowBrowserProfiles]). No new session is made.
     */
    SHARED,

    /**
     * A sign-in of its own through the normal BOSS login, possibly another account. Runs as its
     * own BOSS process rooted at the profile's directory, so plugins, plugin settings and the
     * browser are all its own as well.
     */
    SEPARATE,
}

/** What a new separate-account profile starts with in its plugins directory. */
enum class ProfilePluginSeed {
    /** A copy of the main profile's installed plugins (jars and enabled flags, not their data). */
    COPY_MAIN,

    /** Nothing: the profile's first launch runs the plugin wizard. */
    EMPTY,
}

/**
 * One BOSS profile, stored as `<base>/profiles/<id>/profile.json`. A [ProfileAuthMode.SEPARATE]
 * profile also uses that directory as the data root of its own process; a [ProfileAuthMode.SHARED]
 * one keeps only this file there, since its windows run in the main process.
 *
 * @property workspaceIds the Spaces bound to this profile - the ones opened in it through
 *   [BossProfileLauncher]. A binding is what lets a later open find the profile a Space belongs to.
 */
@Serializable
data class BossProfile(
    val id: String,
    val name: String,
    val auth: ProfileAuthMode,
    val createdAt: Long,
    val workspaceIds: List<String> = emptyList(),
) {
    val root: File
        get() = BossDirectories.profileRoot(id)
}

/**
 * The registry of BOSS profiles on this machine. Profiles always live under the MAIN profile's
 * base directory, so every process - main or profile - sees the same set.
 *
 * Deliberately file-per-profile rather than one index: a profile's own process may write its
 * `profile.json` (a Space binding) while another process lists them, and one shared index would
 * need cross-process locking that a directory scan does not.
 */
@Suppress("TooManyFunctions") // the registry, plus the seeding a new profile needs
object BossProfileStore {
    private val logger = BossLogger.forComponent("BossProfileStore")

    private const val PROFILE_FILE = "profile.json"
    private const val MAX_NAME_LENGTH = 64

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val lock = Any()

    /** Every profile with a readable `profile.json`, oldest first. */
    fun list(): List<BossProfile> =
        BossDirectories
            .profilesDir()
            .listFiles { file -> file.isDirectory && BossDirectories.isValidProfileId(file.name) }
            .orEmpty()
            .mapNotNull { read(it.name) }
            .sortedBy { it.createdAt }

    /** Profile [id], or null when it does not exist (or [id] is not a valid id). */
    fun get(id: String): BossProfile? = if (BossDirectories.isValidProfileId(id)) read(id) else null

    /** The profile [workspaceId] is bound to, if any. */
    fun profileForWorkspace(workspaceId: String): BossProfile? = list().firstOrNull { workspaceId in it.workspaceIds }

    /**
     * Creates a profile and its data root.
     *
     * @param id the profile id, or null to derive one from [name]. An explicit id that is taken
     *   fails rather than reusing someone else's data.
     */
    fun create(
        name: String,
        auth: ProfileAuthMode,
        plugins: ProfilePluginSeed = ProfilePluginSeed.COPY_MAIN,
        id: String? = null,
    ): Result<BossProfile> =
        runCatching {
            val displayName = name.trim().take(MAX_NAME_LENGTH)
            require(displayName.isNotEmpty()) { "A profile needs a name" }
            if (id != null) require(BossDirectories.isValidProfileId(id)) { "Invalid profile id '$id'" }
            synchronized(lock) {
                val profileId = id ?: uniqueIdFor(displayName)
                val root = BossDirectories.profileRoot(profileId)
                if (File(root, PROFILE_FILE).exists()) {
                    throw FileAlreadyExistsException("Profile '$profileId' already exists")
                }
                root.mkdirs()
                val profile =
                    BossProfile(
                        id = profileId,
                        name = displayName,
                        auth = auth,
                        createdAt = System.currentTimeMillis(),
                    )
                write(profile)
                // A same-account profile runs inside the main process: it has no plugins or engine
                // of its own to seed, only Space bindings and a browser profile in the main engine.
                if (auth == ProfileAuthMode.SEPARATE) {
                    seedChromium(root)
                    if (plugins == ProfilePluginSeed.COPY_MAIN) seedPlugins(root)
                }
                logger.info(
                    LogCategory.SYSTEM,
                    "Created BOSS profile",
                    mapOf("profileId" to profileId, "auth" to auth.name, "plugins" to plugins.name),
                )
                profile
            }
        }

    /** Records that [workspaceId] belongs to [profileId]. Idempotent. */
    fun bindWorkspace(
        profileId: String,
        workspaceId: String,
    ): Result<BossProfile> =
        runCatching {
            synchronized(lock) {
                val profile = get(profileId) ?: error("No BOSS profile '$profileId'")
                if (workspaceId in profile.workspaceIds) return@synchronized profile
                profile.copy(workspaceIds = profile.workspaceIds + workspaceId).also(::write)
            }
        }

    /**
     * [base] for an ordinary window; `[base] - <profile name>` for a window that belongs to a
     * profile - one opened for a same-account profile ([windowProfileId]), or any window of a
     * separate-account profile's process - so they can be told apart in the OS window switcher.
     */
    fun windowTitle(
        base: String,
        windowProfileId: String? = null,
    ): String {
        val name = windowProfileId?.let { id -> get(id)?.name ?: id } ?: currentProfileName
        return name?.let { "$base - $it" } ?: base
    }

    private val currentProfileName: String? by lazy {
        BossDirectories.profileId?.let { id -> get(id)?.name ?: id }
    }

    /** The directory profile [id] keeps its Spaces in (see `DesktopWorkspaceFileManager`). */
    fun workspacesDirOf(id: String): File = File(BossDirectories.profileRoot(id), WORKSPACES_DIR_NAME)

    private fun read(id: String): BossProfile? {
        val file = File(BossDirectories.profileRoot(id), PROFILE_FILE)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(BossProfile.serializer(), file.readText()).takeIf { it.id == id }
        } catch (e: SerializationException) {
            logger.warn(LogCategory.SYSTEM, "Unreadable BOSS profile", mapOf("profileId" to id) + decodeFailure(e))
            null
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Unreadable BOSS profile", mapOf("profileId" to id), error = e)
            null
        }
    }

    private fun write(profile: BossProfile) {
        File(profile.root, PROFILE_FILE).atomicWriteText(json.encodeToString(BossProfile.serializer(), profile))
    }

    private fun uniqueIdFor(name: String): String {
        val slug =
            name
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(SLUG_LENGTH)
                .trim('-')
                .ifEmpty { "profile" }
        val random = SecureRandom()
        repeat(MAX_ID_ATTEMPTS) {
            val suffix = (1..SUFFIX_LENGTH).map { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] }.joinToString("")
            val candidate = "$slug-$suffix"
            if (BossDirectories.isValidProfileId(candidate) && !BossDirectories.profileRoot(candidate).exists()) {
                return candidate
            }
        }
        error("Could not allocate a profile id")
    }

    /**
     * Gives the profile the main profile's downloaded Chromium, so its first launch does not
     * fetch ~160 MB again. Hard links where the filesystem allows (no space, no time), a copy
     * where it does not. Not a shared directory: the auto-downloader swaps its own engine
     * directory in place on an update, and a shared one would be replaced under a running peer.
     */
    private fun seedChromium(root: File) {
        val source = File(BossDirectories.baseDir, "boss-chromium")
        if (!source.isDirectory) return
        runCatching { linkOrCopyTree(source.toPath(), File(root, "boss-chromium").toPath()) }
            .onFailure { logger.warn(LogCategory.SYSTEM, "Could not seed Chromium into the new profile", error = it) }
    }

    /**
     * Copies the main profile's installed plugins: the jars and their `.sig` sidecars, and
     * `installed.json` with every path re-pointed into the profile. Plugin DATA is not copied -
     * that is what keeps one profile's plugin configuration out of another's. Marks the plugin
     * wizard done, since the profile already has its plugins.
     */
    private fun seedPlugins(root: File) {
        val sourceDir = File(BossDirectories.baseDir, "plugins")
        if (!sourceDir.isDirectory) return
        val targetDir = File(root, "plugins").apply { mkdirs() }
        runCatching {
            sourceDir
                .listFiles { file ->
                    file.isFile &&
                        (file.name.endsWith(".jar") || file.name.endsWith(".jar.sig")) &&
                        "-downloading" !in file.name
                }.orEmpty()
                // Copied, never hard-linked: some update paths rewrite a jar in place, and a
                // shared inode would carry that write into the main profile's plugin.
                .forEach { jar ->
                    Files.copy(jar.toPath(), File(targetDir, jar.name).toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                }
            File(sourceDir, PluginPersistence.CONFIG_FILE_NAME).takeIf { it.isFile }?.let { config ->
                val sourcePrefix = sourceDir.absolutePath + File.separator
                val targetPrefix = targetDir.absolutePath + File.separator
                val rewritten = PluginPersistence.rebaseJarPaths(config.readText(), sourcePrefix, targetPrefix)
                File(targetDir, PluginPersistence.CONFIG_FILE_NAME).atomicWriteText(rewritten)
            }
            File(root, "pending_wizard_completed").writeText("true")
        }.onFailure { logger.warn(LogCategory.SYSTEM, "Could not seed plugins into the new profile", error = it) }
    }

    private fun linkOrCopyTree(
        source: Path,
        target: Path,
    ) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                when {
                    Files.isSymbolicLink(path) -> {
                        Files.createDirectories(destination.parent)
                        Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    }

                    Files.isDirectory(path) -> {
                        Files.createDirectories(destination)
                    }

                    else -> {
                        linkOrCopy(path, destination)
                    }
                }
            }
        }
    }

    private fun linkOrCopy(
        source: Path,
        target: Path,
    ) {
        Files.createDirectories(target.parent)
        if (Files.exists(target)) return
        try {
            Files.createLink(target, source)
        } catch (_: IOException) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        } catch (_: UnsupportedOperationException) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    internal const val WORKSPACES_DIR_NAME = "workspaces"
    private const val SLUG_LENGTH = 20
    private const val SUFFIX_LENGTH = 6
    private const val MAX_ID_ATTEMPTS = 16
    private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
}
