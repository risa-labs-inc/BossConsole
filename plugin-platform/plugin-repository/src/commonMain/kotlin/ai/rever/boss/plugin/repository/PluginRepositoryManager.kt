package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple plugin repositories and provides aggregated access.
 *
 * This allows searching across local and remote repositories, prioritizing
 * local sources when the same plugin exists in multiple repositories.
 */
class PluginRepositoryManager {
    private val logger = BossLogger.forComponent("PluginRepositoryManager")

    /**
     * Registered repositories by ID.
     */
    private val repositories = ConcurrentHashMap<String, PluginRepository>()

    /**
     * Add a repository.
     *
     * @param repository The repository to add
     */
    fun addRepository(repository: PluginRepository) {
        repositories[repository.id] = repository
        logger.info(
            LogCategory.SYSTEM,
            "Added repository",
            mapOf(
                "id" to repository.id,
                "name" to repository.name,
                "isLocal" to repository.isLocal,
            ),
        )
    }

    /**
     * Remove a repository.
     *
     * @param repositoryId The repository ID to remove
     */
    fun removeRepository(repositoryId: String) {
        repositories.remove(repositoryId)
        logger.info(
            LogCategory.SYSTEM,
            "Removed repository",
            mapOf(
                "id" to repositoryId,
            ),
        )
    }

    /**
     * Get a repository by ID.
     *
     * @param repositoryId The repository ID
     * @return The repository, or null if not found
     */
    fun getRepository(repositoryId: String): PluginRepository? = repositories[repositoryId]

    /**
     * Get all registered repositories.
     */
    fun getAllRepositories(): List<PluginRepository> = repositories.values.toList()

    /**
     * Get all local repositories.
     */
    fun getLocalRepositories(): List<PluginRepository> = repositories.values.filter { it.isLocal }

    /**
     * Get all remote repositories.
     */
    fun getRemoteRepositories(): List<PluginRepository> = repositories.values.filter { !it.isLocal }

    /**
     * List all plugins from all repositories.
     *
     * @return List of plugins with their source information
     */
    suspend fun listAllPlugins(): Result<List<PluginWithSource>> =
        coroutineScope {
            runCatching {
                val results =
                    repositories.values
                        .map { repo ->
                            async {
                                repo.listPlugins().getOrNull()?.map { plugin ->
                                    PluginWithSource(
                                        plugin = plugin,
                                        source =
                                            PluginSource(
                                                repositoryId = repo.id,
                                                repositoryName = repo.name,
                                                isLocal = repo.isLocal,
                                            ),
                                    )
                                } ?: emptyList()
                            }
                        }.awaitAll()

                // Flatten and deduplicate (prefer local sources)
                val pluginMap = mutableMapOf<String, PluginWithSource>()
                results.flatten().forEach { pluginWithSource ->
                    val existing = pluginMap[pluginWithSource.plugin.pluginId]
                    if (existing == null || pluginWithSource.source.isLocal) {
                        pluginMap[pluginWithSource.plugin.pluginId] = pluginWithSource
                    }
                }

                pluginMap.values.toList()
            }
        }

    /**
     * Search for plugins across all repositories.
     *
     * @param filter Search filter
     * @return Aggregated search results
     */
    suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        coroutineScope {
            runCatching {
                val results =
                    repositories.values
                        .map { repo ->
                            async {
                                repo.searchPlugins(filter).getOrNull()?.plugins?.map { plugin ->
                                    PluginWithSource(
                                        plugin = plugin,
                                        source =
                                            PluginSource(
                                                repositoryId = repo.id,
                                                repositoryName = repo.name,
                                                isLocal = repo.isLocal,
                                            ),
                                    )
                                } ?: emptyList()
                            }
                        }.awaitAll()

                // Flatten and deduplicate
                val pluginMap = mutableMapOf<String, PluginWithSource>()
                results.flatten().forEach { pluginWithSource ->
                    val existing = pluginMap[pluginWithSource.plugin.pluginId]
                    if (existing == null || pluginWithSource.source.isLocal) {
                        pluginMap[pluginWithSource.plugin.pluginId] = pluginWithSource
                    }
                }

                val plugins = pluginMap.values.map { it.plugin }

                PluginSearchResult(
                    plugins = plugins,
                    totalCount = plugins.size,
                    page = filter.page,
                    pageSize = filter.pageSize,
                )
            }
        }

    /**
     * Get a plugin from any repository.
     *
     * Searches local repositories first, then remote repositories.
     *
     * @param pluginId The plugin ID
     * @return Plugin with source information, or null if not found
     */
    suspend fun getPlugin(pluginId: String): Result<PluginWithSource?> =
        coroutineScope {
            runCatching {
                // Check local repositories first
                for (repo in repositories.values.filter { it.isLocal }) {
                    val plugin = repo.getPlugin(pluginId).getOrNull()
                    if (plugin != null) {
                        return@runCatching PluginWithSource(
                            plugin = plugin,
                            source =
                                PluginSource(
                                    repositoryId = repo.id,
                                    repositoryName = repo.name,
                                    isLocal = true,
                                ),
                        )
                    }
                }

                // Check remote repositories
                for (repo in repositories.values.filter { !it.isLocal }) {
                    val plugin = repo.getPlugin(pluginId).getOrNull()
                    if (plugin != null) {
                        return@runCatching PluginWithSource(
                            plugin = plugin,
                            source =
                                PluginSource(
                                    repositoryId = repo.id,
                                    repositoryName = repo.name,
                                    isLocal = false,
                                ),
                        )
                    }
                }

                null
            }
        }

    /**
     * Get the full version history of a plugin from its source repository.
     *
     * Resolves the source the same way [getPlugin] does (local first, then
     * remote). Each returned [PluginInfo] carries that version's metadata,
     * including `minIpcVersion`, so callers can render compatibility per
     * version and offer downgrades.
     *
     * @param pluginId The plugin ID
     * @return Result with the list of versions (newest first), or empty if not found
     */
    suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        coroutineScope {
            runCatching {
                val orderedRepos =
                    repositories.values.filter { it.isLocal } +
                        repositories.values.filter { !it.isLocal }
                for (repo in orderedRepos) {
                    val versions = repo.getPluginVersions(pluginId).getOrNull()
                    if (!versions.isNullOrEmpty()) {
                        return@runCatching versions
                    }
                }
                emptyList()
            }
        }

    /**
     * Download a plugin from its source repository.
     *
     * @param pluginId The plugin ID
     * @param version The version to download (null for latest)
     * @param targetPath Path to save the downloaded JAR
     * @return Result with the path to the downloaded file
     */
    suspend fun downloadPlugin(
        pluginId: String,
        version: String? = null,
        targetPath: String,
    ): Result<String> {
        // Find the plugin and its source
        val pluginWithSource =
            getPlugin(pluginId).getOrNull()
                ?: return Result.failure(
                    PluginNotFoundException(pluginId, "any"),
                )

        val repository =
            repositories[pluginWithSource.source.repositoryId]
                ?: return Result.failure(
                    RepositoryException("Repository not found", pluginWithSource.source.repositoryId),
                )

        return repository.downloadPlugin(pluginId, version, targetPath)
    }

    /**
     * Get download progress for a plugin.
     *
     * @param pluginId The plugin ID being downloaded
     * @return Flow of download progress, or null if not downloading
     */
    fun getDownloadProgress(pluginId: String): Flow<Float>? {
        for (repo in repositories.values) {
            val progress = repo.getDownloadProgress(pluginId)
            if (progress != null) {
                return progress
            }
        }
        return null
    }

    /**
     * Refresh all repositories.
     */
    suspend fun refreshAll(): Result<Unit> =
        coroutineScope {
            runCatching {
                repositories.values
                    .map { repo ->
                        async { repo.refresh() }
                    }.awaitAll()

                logger.info(
                    LogCategory.SYSTEM,
                    "Refreshed all repositories",
                    mapOf(
                        "count" to repositories.size,
                    ),
                )
            }
        }

    /**
     * Check if any updates are available for installed plugins.
     *
     * @param installedPlugins Map of plugin ID to installed version
     * @return List of plugins with available updates
     */
    suspend fun checkForUpdates(installedPlugins: Map<String, String>): Result<List<PluginWithSource>> =
        coroutineScope {
            runCatching {
                val updates = mutableListOf<PluginWithSource>()

                for ((pluginId, installedVersion) in installedPlugins) {
                    val latestPlugin = getPlugin(pluginId).getOrNull()
                    if (latestPlugin != null && isNewerVersion(latestPlugin.plugin.version, installedVersion)) {
                        updates.add(latestPlugin)
                    }
                }

                updates
            }
        }

    /**
     * Compare two version strings to determine if the first is newer.
     */
    private fun isNewerVersion(
        version1: String,
        version2: String,
    ): Boolean {
        val v1Parts = version1.split(".").mapNotNull { it.toIntOrNull() }
        val v2Parts = version2.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(v1Parts.size, v2Parts.size)) {
            val v1 = v1Parts.getOrElse(i) { 0 }
            val v2 = v2Parts.getOrElse(i) { 0 }

            if (v1 > v2) return true
            if (v1 < v2) return false
        }

        return false
    }
}
