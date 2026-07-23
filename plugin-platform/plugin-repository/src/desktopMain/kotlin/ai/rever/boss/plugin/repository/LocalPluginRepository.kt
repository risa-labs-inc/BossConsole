package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginManifestConstants
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
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
    override val name: String = "Local Plugins"
) : PluginRepository {

    private val logger = BossLogger.forComponent("LocalPluginRepository")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Cached plugin list.
     */
    private var cachedPlugins: List<PluginInfo> = emptyList()

    override val isLocal: Boolean = true

    override val isAvailable: Boolean
        get() = pluginDirectory.exists() && pluginDirectory.isDirectory

    override suspend fun listPlugins(): Result<List<PluginInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isAvailable) {
                logger.warn(LogCategory.SYSTEM, "Plugin directory not available", mapOf(
                    "path" to pluginDirectory.absolutePath
                ))
                return@runCatching emptyList()
            }

            val plugins = pluginDirectory.listFiles { file ->
                file.isFile && file.extension == "jar"
            }?.mapNotNull { jarFile ->
                readPluginFromJar(jarFile)
            } ?: emptyList()

            cachedPlugins = plugins
            logger.info(LogCategory.SYSTEM, "Scanned local plugins", mapOf(
                "count" to plugins.size,
                "path" to pluginDirectory.absolutePath
            ))

            plugins
        }
    }

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> {
        return listPlugins().map { plugins ->
            val filtered = plugins.filter { plugin ->
                val matchesQuery = filter.query.isEmpty() ||
                    plugin.displayName.contains(filter.query, ignoreCase = true) ||
                    plugin.description.contains(filter.query, ignoreCase = true) ||
                    plugin.pluginId.contains(filter.query, ignoreCase = true)

                val matchesType = filter.type == null || plugin.type == filter.type

                val matchesTags = filter.tags.isEmpty() ||
                    filter.tags.any { tag -> plugin.tags.contains(tag) }

                matchesQuery && matchesType && matchesTags
            }

            // Sort
            val sorted = when (filter.sortBy) {
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
                pageSize = filter.pageSize
            )
        }
    }

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> {
        return listPlugins().map { plugins ->
            plugins.find { it.pluginId == pluginId }
        }
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
        targetPath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // For local repository, find the JAR and copy it
            val sourceJar = pluginDirectory.listFiles { file ->
                file.isFile && file.extension == "jar"
            }?.find { jarFile ->
                readPluginId(jarFile) == pluginId
            } ?: throw PluginNotFoundException(pluginId, id)

            val targetFile = File(targetPath)
            sourceJar.copyTo(targetFile, overwrite = true)

            targetFile.absolutePath
        }
    }

    override fun getDownloadProgress(pluginId: String): Flow<Float>? {
        // Local downloads are instant, no progress tracking needed
        return null
    }

    override suspend fun refresh(): Result<Unit> {
        return listPlugins().map { }
    }

    /**
     * Read plugin information from a JAR file.
     */
    private fun readPluginFromJar(jarFile: File): PluginInfo? {
        return try {
            JarFile(jarFile).use { jar ->
                val manifestEntry = jar.getJarEntry(PluginManifestConstants.MANIFEST_PATH)
                    ?: return null

                val content = jar.getInputStream(manifestEntry).bufferedReader().readText()
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
                    publishedAt = jarFile.lastModified()
                )
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to read plugin from JAR", mapOf(
                "path" to jarFile.absolutePath,
                "error" to (e.message ?: "unknown")
            ))
            null
        }
    }

    /**
     * Read just the plugin ID from a JAR file.
     */
    private fun readPluginId(jarFile: File): String? {
        return try {
            JarFile(jarFile).use { jar ->
                val manifestEntry = jar.getJarEntry(PluginManifestConstants.MANIFEST_PATH)
                    ?: return null

                val content = jar.getInputStream(manifestEntry).bufferedReader().readText()
                val manifest = json.decodeFromString<PluginManifest>(content)
                manifest.pluginId
            }
        } catch (e: Exception) {
            // Not a readable BOSS plugin JAR - callers treat null as "skip this file"
            logger.debug(LogCategory.SYSTEM, "Could not read pluginId from JAR", mapOf("jar" to jarFile.name, "error" to e.toString()))
            null
        }
    }

    /**
     * Get the path for a plugin JAR in this repository.
     */
    fun getJarPath(pluginId: String): String? {
        return pluginDirectory.listFiles { file ->
            file.isFile && file.extension == "jar"
        }?.find { jarFile ->
            readPluginId(jarFile) == pluginId
        }?.absolutePath
    }
}
