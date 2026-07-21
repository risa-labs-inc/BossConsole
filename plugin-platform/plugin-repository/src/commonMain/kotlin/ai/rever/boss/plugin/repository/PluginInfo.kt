package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.api.PluginType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Information about a plugin available in a repository.
 */
@Serializable
data class PluginInfo(
    /**
     * Unique plugin ID.
     */
    @SerialName("pluginId")
    val pluginId: String,

    /**
     * Human-readable display name.
     */
    @SerialName("displayName")
    val displayName: String,

    /**
     * Plugin version.
     */
    @SerialName("version")
    val version: String,

    /**
     * Plugin description.
     */
    @SerialName("description")
    val description: String = "",

    /**
     * Plugin author.
     */
    @SerialName("author")
    val author: String = "",

    /**
     * URL for plugin homepage or documentation.
     */
    @SerialName("url")
    val url: String = "",

    /**
     * Type of plugin.
     */
    @SerialName("type")
    val type: PluginType = PluginType.PANEL,

    /**
     * Required BOSS Plugin API version.
     */
    @SerialName("apiVersion")
    val apiVersion: String = "1.0",

    /**
     * Minimum BOSS application version required.
     */
    @SerialName("minBossVersion")
    val minBossVersion: String = "",

    /**
     * Minimum boss-plugin-api (runtime API layer) version required.
     * Blank means no requirement / predates the field (fail-open).
     */
    @SerialName("minApiVersion")
    val minApiVersion: String = "",

    /**
     * Minimum host IPC contract version required to load this version.
     * Blank/"1.0.0" means broadly compatible. See IpcVersion in :boss-ipc.
     */
    @SerialName("minIpcVersion")
    val minIpcVersion: String = "1.0.0",

    /**
     * Download URL for the plugin JAR.
     */
    @SerialName("downloadUrl")
    val downloadUrl: String = "",

    /**
     * Size of the plugin JAR in bytes.
     */
    @SerialName("size")
    val size: Long = 0,

    /**
     * SHA-256 hash of the plugin JAR for verification.
     */
    @SerialName("sha256")
    val sha256: String = "",

    /**
     * List of dependency plugin IDs.
     */
    @SerialName("dependencies")
    val dependencies: List<String> = emptyList(),

    /**
     * Plugin icon URL (for display in UI).
     */
    @SerialName("iconUrl")
    val iconUrl: String = "",

    /**
     * List of screenshot URLs (for plugin store).
     */
    @SerialName("screenshots")
    val screenshots: List<String> = emptyList(),

    /**
     * Average rating (1-5 scale).
     */
    @SerialName("rating")
    val rating: Float = 0f,

    /**
     * Number of ratings.
     */
    @SerialName("ratingCount")
    val ratingCount: Int = 0,

    /**
     * Total download count.
     */
    @SerialName("downloadCount")
    val downloadCount: Int = 0,

    /**
     * Changelog for this version.
     */
    @SerialName("changelog")
    val changelog: String = "",

    /**
     * Whether this plugin is verified/signed.
     */
    @SerialName("verified")
    val verified: Boolean = false,

    /**
     * Publisher name (if verified).
     */
    @SerialName("publisher")
    val publisher: String = "",

    /**
     * Timestamp when this version was published.
     */
    @SerialName("publishedAt")
    val publishedAt: Long = 0,

    /**
     * Tags for categorization.
     */
    @SerialName("tags")
    val tags: List<String> = emptyList(),

    /**
     * Whether this plugin requires admin privileges.
     */
    @SerialName("requiresAdmin")
    val requiresAdmin: Boolean = false
)

/**
 * Result of a repository search.
 */
@Serializable
data class PluginSearchResult(
    /**
     * List of matching plugins.
     */
    @SerialName("plugins")
    val plugins: List<PluginInfo>,

    /**
     * Total count of matching plugins (for pagination).
     */
    @SerialName("totalCount")
    val totalCount: Int,

    /**
     * Current page number.
     */
    @SerialName("page")
    val page: Int = 1,

    /**
     * Page size.
     */
    @SerialName("pageSize")
    val pageSize: Int = 20
)

/**
 * Filter options for searching plugins.
 */
data class PluginSearchFilter(
    /**
     * Search query (matches name, description, tags).
     */
    val query: String = "",

    /**
     * Filter by plugin type.
     */
    val type: PluginType? = null,

    /**
     * Filter by tags.
     */
    val tags: List<String> = emptyList(),

    /**
     * Minimum rating.
     */
    val minRating: Float = 0f,

    /**
     * Only show verified plugins.
     */
    val verifiedOnly: Boolean = false,

    /**
     * Page number (1-indexed).
     */
    val page: Int = 1,

    /**
     * Page size.
     */
    val pageSize: Int = 20,

    /**
     * Sort order.
     */
    val sortBy: PluginSortOrder = PluginSortOrder.DOWNLOADS
)

/**
 * Sort order for plugin search results.
 */
enum class PluginSortOrder {
    NAME,
    DOWNLOADS,
    RATING,
    NEWEST,
    UPDATED
}

/**
 * Source of a plugin (which repository it came from).
 */
data class PluginSource(
    /**
     * Repository ID.
     */
    val repositoryId: String,

    /**
     * Repository name.
     */
    val repositoryName: String,

    /**
     * Whether this is a local or remote repository.
     */
    val isLocal: Boolean
)

/**
 * Plugin info with source information.
 */
data class PluginWithSource(
    /**
     * The plugin information.
     */
    val plugin: PluginInfo,

    /**
     * Source repository.
     */
    val source: PluginSource
)
