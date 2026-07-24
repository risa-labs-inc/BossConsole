package ai.rever.boss.plugin.repository

import kotlinx.coroutines.flow.Flow

/**
 * Interface for plugin repositories.
 *
 * A repository provides access to plugins, either from local storage
 * or remote plugin stores.
 */
interface PluginRepository {
    /**
     * Unique identifier for this repository.
     */
    val id: String

    /**
     * Human-readable name for this repository.
     */
    val name: String

    /**
     * Whether this is a local repository.
     */
    val isLocal: Boolean

    /**
     * Whether this repository is currently available.
     */
    val isAvailable: Boolean

    /**
     * List all plugins in this repository.
     *
     * @return List of available plugins
     */
    suspend fun listPlugins(): Result<List<PluginInfo>>

    /**
     * Search for plugins matching the given filter.
     *
     * @param filter Search filter
     * @return Search results
     */
    suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult>

    /**
     * Get detailed information about a specific plugin.
     *
     * @param pluginId The plugin ID
     * @return Plugin info, or null if not found
     */
    suspend fun getPlugin(pluginId: String): Result<PluginInfo?>

    /**
     * Get all available versions of a plugin.
     *
     * @param pluginId The plugin ID
     * @return List of available versions (sorted newest first)
     */
    suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>>

    /**
     * Download a plugin JAR to the specified path.
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
    ): Result<String>

    /**
     * Get download progress as a flow.
     *
     * @param pluginId The plugin ID being downloaded
     * @return Flow of download progress (0.0 to 1.0)
     */
    fun getDownloadProgress(pluginId: String): Flow<Float>?

    /**
     * Refresh the repository's plugin list.
     *
     * For remote repositories, this fetches the latest data from the server.
     * For local repositories, this rescans the plugin directory.
     *
     * @return Result indicating success or failure
     */
    suspend fun refresh(): Result<Unit>
}

/**
 * Exception thrown when a repository operation fails.
 */
class RepositoryException(
    message: String,
    val repositoryId: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when a plugin is not found.
 */
class PluginNotFoundException(
    val pluginId: String,
    val repositoryId: String,
) : Exception("Plugin not found: $pluginId in repository: $repositoryId")

/**
 * Exception thrown when a download fails.
 */
class DownloadException(
    message: String,
    val pluginId: String,
    val repositoryId: String,
    cause: Throwable? = null,
) : Exception(message, cause)
