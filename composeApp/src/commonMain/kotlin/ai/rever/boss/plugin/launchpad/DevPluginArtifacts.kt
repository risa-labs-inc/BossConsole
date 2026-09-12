package ai.rever.boss.plugin.launchpad

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.file.Path

/**
 * Manages staging directories, version history, and dev JAR discovery for BossConsole plugins.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
object DevPluginArtifacts {
    private val logger = BossLogger.forComponent("DevPluginArtifacts")

    const val DEFAULT_MAX_VERSIONS_TO_KEEP: Int = 3

    /** Staging root directory override for isolated unit and integration tests. */
    @Volatile
    internal var stagingRootOverride: File? = null

    /**
     * Resolves the root staging directory for dev plugins (~/.boss/plugins/dev or ~/.boss_debug/plugins/dev).
     */
    fun stagingRoot(): File = stagingRootOverride ?: BossDirectories.resolve("plugins/dev")

    /**
     * Returns true if [jarFile] is located strictly within the dev staging directory tree
     * under a version folder (`.../plugins/dev/<pluginId>/v<timestamp>/<pluginId>.jar`).
     * Prevents false-positives on paths containing "dev" in username or parent directories.
     */
    fun isDevPluginJar(jarFile: File): Boolean = isDevPluginJar(jarFile.toPath())

    /**
     * Returns true if [jarPath] is located strictly within the dev staging directory tree
     * under a version folder (`.../plugins/dev/<pluginId>/v<timestamp>/<pluginId>.jar`).
     */
    fun isDevPluginJar(jarPath: Path): Boolean {
        val stagingDir = stagingRoot().toPath().toAbsolutePath().normalize()
        val candidate = jarPath.toAbsolutePath().normalize()
        val parent = candidate.parent ?: return false
        return candidate.startsWith(stagingDir) && parent.fileName?.toString()?.startsWith("v") == true
    }

    /**
     * Finds the active dev JAR for [pluginId] by inspecting version directories in descending order.
     * Skips empty or corrupt version directories and returns the latest directory containing a valid JAR.
     */
    fun findActiveDevJar(
        pluginId: String,
        devRoot: File = stagingRoot(),
    ): File? {
        val pluginDir = File(devRoot, pluginId)
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
                return candidate
            }
        }
        return null
    }

    /**
     * Discovers all active dev plugin JARs under [devRoot], including legacy flat JARs.
     */
    fun findAllActiveDevJars(devRoot: File = stagingRoot()): List<File> {
        if (!devRoot.exists() || !devRoot.isDirectory) return emptyList()

        val activeJars = mutableListOf<File>()
        val flatJars = devRoot.listFiles { file -> file.isFile && file.extension == "jar" } ?: emptyArray()
        activeJars.addAll(flatJars)

        val pluginDirs = devRoot.listFiles { file -> file.isDirectory } ?: emptyArray()
        for (pDir in pluginDirs) {
            val jar = findActiveDevJar(pDir.name, devRoot)
            if (jar != null) {
                activeJars.add(jar)
            }
        }
        return activeJars
    }

    /**
     * Safely prunes older version directories, retaining at most [maxVersionsToKeep] valid builds.
     * Never deletes the currently active or staged version, and logs warnings if deletion fails.
     */
    fun pruneStagingHistory(
        pluginDevDir: File,
        maxVersionsToKeep: Int = DEFAULT_MAX_VERSIONS_TO_KEEP,
    ) {
        if (!pluginDevDir.exists() || !pluginDevDir.isDirectory) return

        val allVersionDirs =
            pluginDevDir.listFiles { file -> file.isDirectory && file.name.startsWith("v") }
                ?: return

        val (validDirs, corruptDirs) =
            allVersionDirs.partition { dir ->
                val jars =
                    dir.listFiles { file ->
                        file.isFile && file.extension == "jar" && !file.name.endsWith(".part") && file.length() > 0
                    }
                !jars.isNullOrEmpty()
            }

        cleanCorruptDirectories(corruptDirs)

        val sortedDirs =
            validDirs.sortedWith(
                compareByDescending { dir ->
                    dir.name.removePrefix("v").toLongOrNull() ?: dir.lastModified()
                },
            )

        if (sortedDirs.size <= maxVersionsToKeep) return

        pruneOldVersionDirectories(sortedDirs.drop(maxVersionsToKeep))
    }

    private fun cleanCorruptDirectories(corruptDirs: List<File>) {
        for (dir in corruptDirs) {
            try {
                dir.deleteRecursively()
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Failed to delete empty or corrupt staging directory",
                    mapOf("dir" to dir.absolutePath, "error" to (e.message ?: "unknown")),
                )
            }
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
