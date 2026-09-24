package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginManifestConstants
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.pathutils.ManagedDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * Repository that scans a local directory for plugin JARs.
 *
 * This is the primary source for user-installed plugins from local files.
 *
 * @param pluginDirectory Directory to scan for plugin JARs
 * @param repositoryId Unique ID for this repository
 * @param repositoryName Display name for this repository
 */
class LocalPluginRepository(
    private val pluginDirectory: File,
    override val id: String = "local",
    override val name: String = "Local Plugins",
) : PluginRepository {
    private val logger = BossLogger.forComponent("LocalPluginRepository")

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Cached plugin list.
     */
    private var cachedPlugins: List<PluginInfo> = emptyList()

    override val isLocal: Boolean = true

    override val isAvailable: Boolean
        get() =
            !Files.isSymbolicLink(pluginDirectory.toPath()) &&
                Files.isDirectory(pluginDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)

    override suspend fun listPlugins(): Result<List<PluginInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isAvailable) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Plugin directory not available",
                        mapOf(
                            "path" to pluginDirectory.absolutePath,
                        ),
                    )
                    return@runCatching emptyList()
                }

                val plugins =
                    managedJars(pluginDirectory).mapNotNull { jarFile -> readPluginFromJar(jarFile) }

                cachedPlugins = plugins
                logger.info(
                    LogCategory.SYSTEM,
                    "Scanned local plugins",
                    mapOf(
                        "count" to plugins.size,
                        "path" to pluginDirectory.absolutePath,
                    ),
                )

                plugins
            }
        }

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        listPlugins().map { plugins ->
            val filtered =
                plugins.filter { plugin ->
                    val matchesQuery =
                        filter.query.isEmpty() ||
                            plugin.displayName.contains(filter.query, ignoreCase = true) ||
                            plugin.description.contains(filter.query, ignoreCase = true) ||
                            plugin.pluginId.contains(filter.query, ignoreCase = true)

                    val matchesType = filter.type == null || plugin.type == filter.type

                    val matchesTags =
                        filter.tags.isEmpty() ||
                            filter.tags.any { tag -> plugin.tags.contains(tag) }

                    matchesQuery && matchesType && matchesTags
                }

            // Sort
            val sorted =
                when (filter.sortBy) {
                    PluginSortOrder.NAME -> filtered.sortedBy { it.displayName }
                    PluginSortOrder.DOWNLOADS -> filtered.sortedByDescending { it.downloadCount }
                    PluginSortOrder.RATING -> filtered.sortedByDescending { it.rating }
                    PluginSortOrder.NEWEST -> filtered.sortedByDescending { it.publishedAt }
                    PluginSortOrder.UPDATED -> filtered.sortedByDescending { it.publishedAt }
                }

            // Paginate
            val startIndex = (filter.page - 1) * filter.pageSize
            val endIndex = minOf(startIndex + filter.pageSize, sorted.size)
            val pagePlugins = if (startIndex < sorted.size) sorted.subList(startIndex, endIndex) else emptyList()

            PluginSearchResult(
                plugins = pagePlugins,
                totalCount = sorted.size,
                page = filter.page,
                pageSize = filter.pageSize,
            )
        }

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
        listPlugins().map { plugins ->
            plugins.find { it.pluginId == pluginId }
        }

    override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> {
        // Local repository only has one version per plugin
        return getPlugin(pluginId).map { plugin ->
            if (plugin != null) listOf(plugin) else emptyList()
        }
    }

    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // For local repository, find the JAR and copy it
                val sourceJar =
                    managedJars(pluginDirectory).find { jarFile ->
                        readPluginId(jarFile) == pluginId
                    } ?: throw PluginNotFoundException(pluginId, id)

                val targetFile = File(targetPath)
                // Write a sibling .part file and verify its hash against the
                // source before it lands on the target name: a bare
                // copyTo(overwrite = true) destroys the existing jar on open
                // and could leave unverified bytes at a scannable path.
                val sourceHash = sha256Hex(sourceJar)
                val tempFile = File.createTempFile(".local-copy-", ".part", targetFile.absoluteFile.parentFile)
                try {
                    sourceJar.copyTo(tempFile, overwrite = true)
                    check(sha256Hex(tempFile) == sourceHash) {
                        "Copied plugin JAR failed integrity check: $targetPath"
                    }
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    tempFile.delete()
                }

                // A local copy is instant, but a caller showing progress still
                // needs to be told it finished: without this its row would sit
                // at "Downloading" until the phase after it moved on.
                onProgress?.invoke(1f)

                targetFile.absolutePath
            }
        }

    override fun getDownloadProgress(pluginId: String): Flow<Float>? {
        // Local downloads are instant, no progress tracking needed
        return null
    }

    override suspend fun refresh(): Result<Unit> = listPlugins().map { }

    /**
     * Read plugin information from a JAR file.
     *
     * The manifest entry read is bounded (PluginManifestReader.MAX_MANIFEST_BYTES):
     * a zip-bomb `plugin.json` is skipped like any other malformed JAR instead
     * of being fully loaded into memory.
     */
    private fun readPluginFromJar(jarFile: File): PluginInfo? {
        return try {
            JarFile(jarFile).use { jar ->
                val manifestEntry =
                    jar.getJarEntry(PluginManifestConstants.MANIFEST_PATH)
                        ?: return null

                val content = PluginManifestReader.readManifestContent(jar, manifestEntry)
                val manifest = json.decodeFromString<PluginManifest>(content)

                PluginInfo(
                    pluginId = manifest.pluginId,
                    displayName = manifest.displayName,
                    version = manifest.version,
                    description = manifest.description,
                    author = manifest.author,
                    url = manifest.url,
                    type = manifest.type,
                    apiVersion = manifest.apiVersion,
                    downloadUrl = jarFile.absolutePath,
                    size = jarFile.length(),
                    dependencies = manifest.dependencies.map { it.pluginId },
                    verified = false,
                    publishedAt = jarFile.lastModified(),
                )
            }
        } catch (e: Exception) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to read plugin from JAR",
                mapOf(
                    "path" to jarFile.absolutePath,
                    "error" to (e.message ?: "unknown"),
                ),
            )
            null
        }
    }

    /**
     * Read just the plugin ID from a JAR file.
     *
     * Bounded like readPluginFromJar: an oversize manifest entry is treated
     * as unreadable and skipped.
     */
    private fun readPluginId(jarFile: File): String? {
        return try {
            JarFile(jarFile).use { jar ->
                val manifestEntry =
                    jar.getJarEntry(PluginManifestConstants.MANIFEST_PATH)
                        ?: return null

                val content = PluginManifestReader.readManifestContent(jar, manifestEntry)
                val manifest = json.decodeFromString<PluginManifest>(content)
                manifest.pluginId
            }
        } catch (e: Exception) {
            // Not a readable BOSS plugin JAR - callers treat null as "skip this file"
            logger.debug(
                LogCategory.SYSTEM,
                "Could not read pluginId from JAR",
                mapOf("jar" to jarFile.name, "error" to e.toString()),
            )
            null
        }
    }

    /**
     * Get the path for a plugin JAR in this repository.
     */
    fun getJarPath(pluginId: String): String? =
        managedJars(pluginDirectory)
            .find { jarFile ->
                readPluginId(jarFile) == pluginId
            }?.absolutePath
}

/**
 * The JARs directly inside [dir] that are safe to scan: plain regular files
 * whose real path stays inside the directory's own real path. Symlinked or
 * escaping entries are skipped, not followed.
 */
private fun managedJars(dir: File): List<File> =
    ManagedDirectories.listContainedRegularFiles(dir) { file ->
        file.extension == "jar"
    }

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
