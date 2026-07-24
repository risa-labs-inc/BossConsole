package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.api.PluginType
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import ai.rever.boss.plugin.repository.PluginSortOrder
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP client for the remote plugin store Edge Function.
 *
 * Handles all communication with the /plugin-store endpoints.
 */
object PluginStoreClient {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

    private val httpClient: HttpClient by lazy {
        createHttpClient()
    }

    // ============================================================================
    // Browse Endpoints
    // ============================================================================

    /**
     * GET /plugin-store/list - List all plugins
     */
    suspend fun listPlugins(
        page: Int = 1,
        pageSize: Int = 20,
        sortBy: String = "downloads",
    ): PluginListResponse {
        val response =
            httpClient.get("${PluginStoreConfig.pluginStoreUrl}/list") {
                parameter("page", page)
                parameter("pageSize", pageSize)
                parameter("sortBy", sortBy)
                header("apikey", PluginStoreConfig.anonKey)
            }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to list plugins: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * POST /plugin-store/search - Search plugins
     */
    suspend fun searchPlugins(filter: PluginSearchFilter): PluginListResponse {
        val request =
            SearchRequest(
                query = filter.query,
                type = filter.type?.name?.lowercase(),
                tags = filter.tags.takeIf { it.isNotEmpty() },
                minRating = filter.minRating,
                verifiedOnly = filter.verifiedOnly,
                page = filter.page,
                pageSize = filter.pageSize,
                sortBy = filter.sortBy.toApiString(),
            )

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/search") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                setBody(json.encodeToString(SearchRequest.serializer(), request))
            }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to search plugins: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * GET /plugin-store/:pluginId - Get plugin details
     */
    suspend fun getPlugin(pluginId: String): PluginDetailResponse? {
        val response =
            httpClient.get("${PluginStoreConfig.pluginStoreUrl}/$pluginId") {
                header("apikey", PluginStoreConfig.anonKey)
            }

        if (response.status == HttpStatusCode.NotFound) {
            return null
        }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to get plugin: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * GET /plugin-store/tags/popular - Get popular tags
     */
    suspend fun getPopularTags(limit: Int = 20): PopularTagsResponse {
        val response =
            httpClient.get("${PluginStoreConfig.pluginStoreUrl}/tags/popular") {
                parameter("limit", limit)
                header("apikey", PluginStoreConfig.anonKey)
            }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to get popular tags: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    // ============================================================================
    // Download Endpoints
    // ============================================================================

    /**
     * GET /plugin-store/:pluginId/download - Get download URL for latest version
     */
    suspend fun getDownloadUrl(pluginId: String): DownloadInfoResponse {
        val response =
            httpClient.get("${PluginStoreConfig.pluginStoreUrl}/$pluginId/download") {
                header("apikey", PluginStoreConfig.anonKey)
                PluginStoreConfig.accessToken?.let {
                    header("Authorization", "Bearer $it")
                }
            }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to get download URL: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * GET /plugin-store/:pluginId/download/:version - Get download URL for specific version
     */
    suspend fun getDownloadUrl(
        pluginId: String,
        version: String,
    ): DownloadInfoResponse {
        val response =
            httpClient.get("${PluginStoreConfig.pluginStoreUrl}/$pluginId/download/$version") {
                header("apikey", PluginStoreConfig.anonKey)
                PluginStoreConfig.accessToken?.let {
                    header("Authorization", "Bearer $it")
                }
            }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to get download URL: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    // ============================================================================
    // Rating Endpoints
    // ============================================================================

    /**
     * POST /plugin-store/:pluginId/rate - Rate a plugin
     */
    suspend fun ratePlugin(
        pluginId: String,
        rating: Int,
        review: String = "",
    ): RateResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("Authentication required to rate plugins")

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/$pluginId/rate") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(json.encodeToString(RateRequest.serializer(), RateRequest(rating, review)))
            }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to rate plugin: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    // ============================================================================
    // Health Check
    // ============================================================================

    /**
     * GET /plugin-store/health - Check if the service is available
     */
    suspend fun checkHealth(): Boolean =
        try {
            val response =
                httpClient.get("${PluginStoreConfig.pluginStoreUrl}/health") {
                    header("apikey", PluginStoreConfig.anonKey)
                }
            response.status.isSuccess()
        } catch (_: Exception) {
            false
        }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun PluginSortOrder.toApiString(): String =
        when (this) {
            PluginSortOrder.NAME -> "name"
            PluginSortOrder.DOWNLOADS -> "downloads"
            PluginSortOrder.RATING -> "rating"
            PluginSortOrder.NEWEST -> "newest"
            PluginSortOrder.UPDATED -> "updated"
        }

    // ============================================================================
    // Publish Endpoints
    // ============================================================================

    /**
     * POST /plugin-store/publish - Create a new plugin entry (first-time publish)
     */
    suspend fun publishPlugin(request: PublishPluginRequest): PublishPluginResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("Authentication required to publish plugins")

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/publish") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(json.encodeToString(PublishPluginRequest.serializer(), request))
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<PublishPluginResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to publish plugin: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * POST /plugin-store/:pluginId/version - Create a new version and get upload URL
     */
    suspend fun publishVersion(
        pluginId: String,
        request: PublishVersionRequest,
    ): PublishVersionResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("Authentication required to publish versions")

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/$pluginId/version") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(json.encodeToString(PublishVersionRequest.serializer(), request))
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<PublishVersionResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to publish version: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * PUT {uploadUrl} - Upload JAR file to the signed URL
     */
    suspend fun uploadJar(
        uploadUrl: String,
        jarBytes: ByteArray,
    ): Boolean {
        val response =
            httpClient.put(uploadUrl) {
                contentType(ContentType.Application.OctetStream)
                setBody(jarBytes)
            }

        if (!response.status.isSuccess()) {
            throw PluginStoreException("Failed to upload JAR: ${response.status}")
        }

        return true
    }

    /**
     * POST /plugin-store/version/finalize - Finalize version after JAR upload
     */
    suspend fun finalizeVersion(request: FinalizeVersionRequest): FinalizeVersionResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("Authentication required to finalize versions")

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/version/finalize") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(json.encodeToString(FinalizeVersionRequest.serializer(), request))
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<FinalizeVersionResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to finalize version: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Check if a plugin with the given ID exists
     */
    suspend fun pluginExists(pluginId: String): Boolean = getPlugin(pluginId) != null

    // ============================================================================
    // Admin API Methods
    // ============================================================================

    /**
     * POST /plugin-store/admin/:pluginId/publish - Enable/disable a plugin (admin only)
     */
    suspend fun setPluginPublished(
        pluginId: String,
        published: Boolean,
    ): AdminActionResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("Authentication required for admin actions")

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/admin/$pluginId/publish") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(json.encodeToString(SetPublishedRequest.serializer(), SetPublishedRequest(published)))
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<AdminActionResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to update plugin status: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * DELETE /plugin-store/admin/:pluginId - Delete a plugin (admin only)
     */
    suspend fun deletePlugin(pluginId: String): AdminActionResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("Authentication required for admin actions")

        val response =
            httpClient.delete("${PluginStoreConfig.pluginStoreUrl}/admin/$pluginId") {
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<AdminActionResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to delete plugin: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * POST /plugin-store/admin/:pluginId/verify - Verify/unverify a plugin (admin only)
     */
    suspend fun setPluginVerified(
        pluginId: String,
        verified: Boolean,
    ): AdminActionResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("Authentication required for admin actions")

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/admin/$pluginId/verify") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(json.encodeToString(SetVerifiedRequest.serializer(), SetVerifiedRequest(verified)))
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<AdminActionResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to update verification status: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    // ============================================================================
    // API Key Management Endpoints
    // ============================================================================

    /**
     * POST /plugin-store/api-keys - Create a new API key
     */
    suspend fun createApiKey(request: CreateApiKeyRequest): CreateApiKeyResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("JWT authentication required to create API keys")

        val response =
            httpClient.post("${PluginStoreConfig.pluginStoreUrl}/api-keys") {
                contentType(ContentType.Application.Json)
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
                setBody(json.encodeToString(CreateApiKeyRequest.serializer(), request))
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<CreateApiKeyResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to create API key: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * GET /plugin-store/api-keys - List user's API keys
     */
    suspend fun listApiKeys(): ListApiKeysResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("JWT authentication required to list API keys")

        val response =
            httpClient.get("${PluginStoreConfig.pluginStoreUrl}/api-keys") {
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<ListApiKeysResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to list API keys: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * DELETE /plugin-store/api-keys/:keyId - Revoke an API key
     */
    suspend fun revokeApiKey(keyId: String): DeleteApiKeyResponse {
        val accessToken =
            PluginStoreConfig.accessToken
                ?: throw PluginStoreException("JWT authentication required to revoke API keys")

        val response =
            httpClient.delete("${PluginStoreConfig.pluginStoreUrl}/api-keys/$keyId") {
                header("apikey", PluginStoreConfig.anonKey)
                header("Authorization", "Bearer $accessToken")
            }

        if (!response.status.isSuccess()) {
            val errorBody =
                try {
                    json.decodeFromString<DeleteApiKeyResponse>(response.bodyAsText())
                } catch (_: Exception) {
                    null
                }
            throw PluginStoreException(errorBody?.error ?: "Failed to revoke API key: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }
}

/**
 * Exception thrown when plugin store API calls fail.
 */
class PluginStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

// ============================================================================
// API Request/Response Models
// ============================================================================

@Serializable
internal data class SearchRequest(
    val query: String = "",
    val type: String? = null,
    val tags: List<String>? = null,
    val minRating: Float = 0f,
    val verifiedOnly: Boolean = false,
    val page: Int = 1,
    val pageSize: Int = 20,
    val sortBy: String = "downloads",
)

@Serializable
internal data class RateRequest(
    val rating: Int,
    val review: String = "",
)

@Serializable
data class PluginListResponse(
    val plugins: List<PluginListItem>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
)

@Serializable
data class PluginListItem(
    val id: String,
    val pluginId: String,
    val displayName: String,
    val description: String,
    val author: String,
    val type: String,
    val apiVersion: String,
    val verified: Boolean,
    val iconUrl: String = "",
    val url: String = "",
    val version: String? = null,
    val rating: Float = 0f,
    val ratingCount: Int = 0,
    val downloadCount: Int = 0,
    val tags: List<String> = emptyList(),
    val updatedAt: String = "",
) {
    fun toPluginInfo(): PluginInfo =
        PluginInfo(
            pluginId = pluginId,
            displayName = displayName,
            version = version ?: "0.0.0",
            description = description,
            author = author,
            url = url,
            type = parsePluginType(type),
            apiVersion = apiVersion,
            iconUrl = iconUrl,
            rating = rating,
            ratingCount = ratingCount,
            downloadCount = downloadCount,
            tags = tags,
            verified = verified,
        )

    private fun parsePluginType(type: String): PluginType =
        when (type.lowercase()) {
            "tab" -> PluginType.TAB
            "hybrid" -> PluginType.MIXED
            else -> PluginType.PANEL
        }
}

@Serializable
data class PluginDetailResponse(
    val id: String,
    val pluginId: String,
    val displayName: String,
    val description: String,
    val authorId: String? = null,
    val authorName: String,
    val homepageUrl: String = "",
    val iconUrl: String = "",
    val type: String,
    val apiVersion: String,
    val verified: Boolean,
    val createdAt: String = "",
    val updatedAt: String = "",
    val latestVersion: String? = null,
    val avgRating: Float = 0f,
    val ratingCount: Int = 0,
    val downloadCount: Int = 0,
    val tags: List<String> = emptyList(),
    val screenshots: List<ScreenshotInfo> = emptyList(),
    val versions: List<VersionInfo> = emptyList(),
) {
    fun toPluginInfo(): PluginInfo =
        PluginInfo(
            pluginId = pluginId,
            displayName = displayName,
            version = latestVersion ?: "0.0.0",
            description = description,
            author = authorName,
            url = homepageUrl,
            type = parsePluginType(type),
            apiVersion = apiVersion,
            minBossVersion = versions.firstOrNull { it.version == latestVersion }?.minBossVersion ?: "",
            minApiVersion = versions.firstOrNull { it.version == latestVersion }?.minApiVersion ?: "",
            minIpcVersion = versions.firstOrNull { it.version == latestVersion }?.minIpcVersion ?: "1.0.0",
            iconUrl = iconUrl,
            screenshots = screenshots.map { it.url },
            rating = avgRating,
            ratingCount = ratingCount,
            downloadCount = downloadCount,
            tags = tags,
            verified = verified,
            publishedAt = parseTimestamp(updatedAt),
        )

    private fun parsePluginType(type: String): PluginType =
        when (type.lowercase()) {
            "tab" -> PluginType.TAB
            "hybrid" -> PluginType.MIXED
            else -> PluginType.PANEL
        }

    private fun parseTimestamp(timestamp: String): Long {
        // Simple ISO timestamp parsing - return 0 if parsing fails
        return try {
            // Remove timezone info and parse
            0L // TODO: Implement proper timestamp parsing if needed
        } catch (_: Exception) {
            0L
        }
    }
}

@Serializable
data class ScreenshotInfo(
    val url: String,
    val caption: String = "",
)

@Serializable
data class VersionInfo(
    val id: String,
    val version: String,
    val changelog: String = "",
    val minBossVersion: String = "1.0.0",
    val minApiVersion: String = "",
    val minIpcVersion: String = "1.0.0",
    val jarSize: Long = 0,
    val sha256: String = "",
    val dependencies: List<DependencyInfo> = emptyList(),
    val publishedAt: String = "",
    val downloadCount: Int = 0,
)

@Serializable
data class DependencyInfo(
    val pluginId: String,
    val versionRange: String,
)

@Serializable
data class DownloadInfoResponse(
    val downloadUrl: String,
    val sha256: String,
    val version: String,
    val size: Long,
    val versionId: String,
    val minIpcVersion: String = "1.0.0",
    // Base64 RSASSA-PKCS1-v1_5/SHA-256 signature over the UTF-8 bytes of the
    // canonical anchor "pluginId|version|sha256" (lowercase hex digest — see
    // PluginStoreTrust.versionAnchor); null for versions published before
    // store signing.
    val signature: String? = null,
)

@Serializable
data class RateResponse(
    val success: Boolean,
    val ratingId: String? = null,
    val created: Boolean? = null,
    val error: String? = null,
)

@Serializable
data class PopularTagsResponse(
    val tags: List<TagCount>,
)

@Serializable
data class TagCount(
    val tag: String,
    val count: Int,
)

// ============================================================================
// Publish API Request/Response Models
// ============================================================================

@Serializable
data class PublishPluginRequest(
    val pluginId: String,
    val displayName: String,
    val description: String = "",
    val authorName: String? = null,
    val homepageUrl: String = "",
    val iconUrl: String = "",
    val type: String = "panel",
    val apiVersion: String = "1.0",
    val tags: List<String> = emptyList(),
)

@Serializable
data class PublishPluginResponse(
    val success: Boolean,
    val id: String? = null,
    val pluginId: String? = null,
    val error: String? = null,
)

@Serializable
data class PublishVersionRequest(
    val version: String,
    val changelog: String = "",
    val minBossVersion: String = "1.0.0",
    val minApiVersion: String = "",
    val dependencies: List<DependencyInfo> = emptyList(),
)

@Serializable
data class PublishVersionResponse(
    val success: Boolean,
    val versionId: String? = null,
    val uploadUrl: String? = null,
    val error: String? = null,
)

@Serializable
data class FinalizeVersionRequest(
    val versionId: String,
    val sha256: String,
    val jarSize: Long,
)

@Serializable
data class FinalizeVersionResponse(
    val success: Boolean,
    val error: String? = null,
)

// ============================================================================
// Admin API Request/Response Models
// ============================================================================

@Serializable
internal data class SetPublishedRequest(
    val published: Boolean,
)

@Serializable
internal data class SetVerifiedRequest(
    val verified: Boolean,
)

@Serializable
data class AdminActionResponse(
    val success: Boolean,
    val pluginId: String? = null,
    val published: Boolean? = null,
    val verified: Boolean? = null,
    val error: String? = null,
)

// ============================================================================
// API Key Request/Response Models
// ============================================================================

@Serializable
data class CreateApiKeyRequest(
    val name: String,
    val scopes: List<String> = listOf("publish", "version", "finalize"),
    val expiresInDays: Int? = null,
)

@Serializable
data class CreateApiKeyResponse(
    val success: Boolean,
    val apiKey: String? = null,
    val keyInfo: ApiKeyInfoResponse? = null,
    val error: String? = null,
)

@Serializable
data class ApiKeyInfoResponse(
    val id: String,
    val name: String,
    val keyPrefix: String,
    val scopes: List<String>,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val expiresAt: String? = null,
    val isExpired: Boolean = false,
)

@Serializable
data class ListApiKeysResponse(
    val success: Boolean,
    val keys: List<ApiKeyInfoResponse> = emptyList(),
    val error: String? = null,
)

@Serializable
data class DeleteApiKeyResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
)

// Platform-specific HTTP client creation
internal expect fun createHttpClient(): HttpClient
