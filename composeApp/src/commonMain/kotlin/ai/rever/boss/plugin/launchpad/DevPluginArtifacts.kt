package ai.rever.boss.plugin.launchpad

import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path

/**
 * Manages staging directories, version history, and dev JAR discovery for BossConsole plugins.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")
object DevPluginArtifacts {
    private val logger = BossLogger.forComponent("DevPluginArtifacts")
    private val manifestJson = Json { ignoreUnknownKeys = true }

    const val DEFAULT_MAX_VERSIONS_TO_KEEP: Int = 3
    const val MAX_MANIFEST_BYTES: Int = 512 * 1024

    /** Staging root directory override for isolated unit and integration tests. */
    @Volatile
    internal var stagingRootOverride: File? = null

    /**
     * Reads up to [maxBytes] from [stream] as UTF-8.
     * Returns null if the stream exceeds [maxBytes] or cannot be read.
     */
    internal fun readBoundedUtf8String(
        stream: java.io.InputStream,
        maxBytes: Int = MAX_MANIFEST_BYTES,
    ): String? =
        try {
            stream.use { s ->
                val bytes = s.readNBytes(maxBytes + 1)
                if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Resolves the root staging directory for dev plugins (~/.boss/plugins/dev or ~/.boss_debug/plugins/dev).
     */
    fun stagingRoot(): File = stagingRootOverride ?: BossDirectories.resolve("plugins/dev")

    /**
     * Extracts the top-level plugin ID from a plugin.json manifest text.
     * Accurately inspects root keys ("pluginId", "id") and ignores nested objects
     * like dependencies or MCP tools that may declare an "id" field.
     */
    fun extractPluginIdFromManifestText(text: String): String? =
        runCatching {
            val root = manifestJson.parseToJsonElement(text).jsonObject
            root["pluginId"]?.jsonPrimitive?.contentOrNull
                ?: root["id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    /**
     * Resolves whether [jarPath] points to a version-rotated dev JAR under the staging root.
     */
    fun isDevPluginJar(jarFile: File): Boolean = isDevPluginPath(jarFile.toPath())

    /**
     * Resolves whether [jarPath] points to a version-rotated dev JAR under the staging root.
     */
    fun isDevPluginPath(
        jarPath: Path,
        devRoot: File = stagingRoot(),
    ): Boolean {
        val stagingDir = devRoot.toPath().toAbsolutePath().normalize()
        val candidate = jarPath.toAbsolutePath().normalize()
        val parent = candidate.parent ?: return false
        return candidate.startsWith(stagingDir) && parent.fileName?.toString()?.startsWith("v") == true
    }

    /**
     * Deeply validates a candidate dev plugin JAR to ensure:
     * 1. It is a readable ZIP/JAR archive.
     * 2. It contains META-INF/boss-plugin/plugin.json.
     * 3. (Optional) The manifest's pluginId matches [expectedPluginId].
     */
    fun isValidDevJar(
        jarFile: File,
        expectedPluginId: String? = null,
    ): Boolean {
        if (!isEligibleJarFile(jarFile)) return false
        val manifestText = readManifestText(jarFile) ?: return false
        if (expectedPluginId == null) return true
        return extractPluginIdFromManifestText(manifestText) == expectedPluginId
    }

    private fun isEligibleJarFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        return file.extension == "jar" && !file.name.endsWith(".part")
    }

    private fun readManifestText(jarFile: File): String? =
        try {
            java.util.jar.JarFile(jarFile).use { jar ->
                val entry = jar.getJarEntry("META-INF/boss-plugin/plugin.json") ?: return null
                jar.getInputStream(entry).use { readBoundedUtf8String(it) }
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Finds the active dev JAR for [pluginId] by inspecting version directories in descending order.
     * Skips empty or corrupt version directories and returns the latest directory containing a valid JAR.
     * When [deepValidate] is true, candidates must also pass [isValidDevJar].
     */
    fun findActiveDevJar(
        pluginId: String,
        devRoot: File = stagingRoot(),
        deepValidate: Boolean = false,
    ): File? {
        val pluginDir = resolveContainedPluginDir(pluginId, devRoot) ?: return null
        if (!pluginDir.exists() || !pluginDir.isDirectory) return null

        val versionDirs =
            pluginDir.listFiles { file -> file.isDirectory && file.name.startsWith("v") }
                ?: return null

        val sortedVersionDirs =
            versionDirs.sortedWith(
                compareByDescending { dir ->
                    dir.name.removePrefix("v").toLongOrNull() ?: dir.lastModified()
                },
            )

        for (vDir in sortedVersionDirs) {
            val candidate =
                vDir
                    .listFiles { file ->
                        file.isFile && file.extension == "jar" && !file.name.endsWith(".part") && file.length() > 0
                    }?.firstOrNull()
            if (candidate != null) {
                if (!deepValidate || isValidDevJar(candidate, pluginId)) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Resolves the staging directory for a specific [pluginId] under [devRoot].
     *
     * @throws IllegalArgumentException if [pluginId] is not a valid plugin id or would
     * resolve outside [devRoot]
     */
    fun pluginDevDir(
        pluginId: String,
        devRoot: File = stagingRoot(),
    ): File =
        resolveContainedPluginDir(pluginId, devRoot)
            ?: throw IllegalArgumentException("Invalid plugin id for dev staging: '$pluginId'")

    /**
     * Joins [pluginId] onto [devRoot] only when the id passes the manifest plugin-id
     * charset check and the canonically resolved directory stays inside [devRoot].
     * Returns null instead of touching the filesystem when either check fails, so
     * callers never list or prune a directory outside the staging root.
     */
    private fun resolveContainedPluginDir(
        pluginId: String,
        devRoot: File,
    ): File? {
        if (!PluginManifestReader.isValidPluginId(pluginId)) {
            logger.warn(
                LogCategory.SYSTEM,
                "Rejected invalid plugin id for dev staging",
                mapOf("pluginId" to pluginId),
            )
            return null
        }
        val pluginDir = File(devRoot, pluginId)
        val contained =
            runCatching {
                pluginDir.canonicalFile.toPath().startsWith(devRoot.canonicalFile.toPath())
            }.getOrDefault(false)
        if (!contained) {
            logger.warn(
                LogCategory.SYSTEM,
                "Rejected dev plugin dir escaping staging root",
                mapOf("pluginId" to pluginId),
            )
            return null
        }
        return pluginDir
    }

    /**
     * Discovers all active dev plugin JARs under [devRoot].
     * When [deepValidate] is true, only JARs passing [isValidDevJar] are returned.
     */
    fun findAllActiveDevJars(
        devRoot: File = stagingRoot(),
        deepValidate: Boolean = false,
    ): List<File> {
        if (!devRoot.exists() || !devRoot.isDirectory) return emptyList()

        val activeJars = mutableListOf<File>()
        val pluginDirs = devRoot.listFiles { file -> file.isDirectory } ?: emptyArray()
        for (pDir in pluginDirs) {
            val jar = findActiveDevJar(pDir.name, devRoot, deepValidate)
            if (jar != null) {
                activeJars.add(jar)
            }
        }
        return activeJars
    }

    /**
     * Safely prunes older version directories, retaining at most [maxVersionsToKeep] valid builds.
     * Never deletes the currently active or staged version, and strictly retains any version directory
     * whose JAR path is listed in [activeJarPaths] (e.g. actively referenced by any running window).
     */
    fun pruneStagingHistory(
        pluginDevDir: File,
        maxVersionsToKeep: Int = DEFAULT_MAX_VERSIONS_TO_KEEP,
        activeJarPaths: Set<String> = emptySet(),
    ) {
        if (!pluginDevDir.exists() || !pluginDevDir.isDirectory) return

        val allVersionDirs =
            pluginDevDir.listFiles { file -> file.isDirectory && file.name.startsWith("v") }
                ?: return

        val validDirs =
            allVersionDirs.filter { dir ->
                val jars =
                    dir.listFiles { file ->
                        file.isFile && file.extension == "jar" && !file.name.endsWith(".part") && file.length() > 0
                    }
                !jars.isNullOrEmpty()
            }

        val sortedDirs =
            validDirs.sortedWith(
                compareByDescending { dir ->
                    dir.name.removePrefix("v").toLongOrNull() ?: dir.lastModified()
                },
            )

        if (sortedDirs.size <= maxVersionsToKeep) return

        val normalizedActivePaths =
            activeJarPaths
                .mapNotNull { path ->
                    runCatching { File(path).canonicalPath }.getOrNull() ?: File(path).absolutePath
                }.toSet()

        val candidatesToPrune = sortedDirs.drop(maxVersionsToKeep)
        val dirsToPrune =
            candidatesToPrune.filterNot { dir ->
                isDirReferencedByActiveJars(dir, activeJarPaths, normalizedActivePaths)
            }

        pruneOldVersionDirectories(dirsToPrune)
    }

    private fun isDirReferencedByActiveJars(
        dir: File,
        activeJarPaths: Set<String>,
        normalizedActivePaths: Set<String>,
    ): Boolean {
        val jars = dir.listFiles { file -> file.isFile && file.extension == "jar" } ?: return false
        return jars.any { jar ->
            val abs = jar.absolutePath
            val canonical = runCatching { jar.canonicalPath }.getOrNull() ?: abs
            abs in activeJarPaths || canonical in normalizedActivePaths
        }
    }

    private fun pruneOldVersionDirectories(dirsToPrune: List<File>) {
        for (dir in dirsToPrune) {
            try {
                dir.deleteRecursively()
                logger.debug(
                    LogCategory.SYSTEM,
                    "Pruned old dev plugin staging directory",
                    mapOf("dir" to dir.absolutePath),
                )
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to delete old dev plugin staging directory",
                    mapOf("dir" to dir.absolutePath, "error" to (e.message ?: "unknown")),
                )
            }
        }
    }
}
