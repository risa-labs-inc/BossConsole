package ai.rever.boss.services.auth

import ai.rever.boss.plugin.api.ApiKeyCreationResult
import ai.rever.boss.plugin.api.ApiKeyInfo
import ai.rever.boss.plugin.api.PluginStoreApiKeyProvider
import ai.rever.boss.plugin.repository.remote.CreateApiKeyRequest
import ai.rever.boss.plugin.repository.remote.PluginStoreClient
import ai.rever.boss.plugin.repository.remote.PluginStoreConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Implementation of PluginStoreApiKeyProvider that uses PluginStoreClient.
 *
 * Provides API key management functionality for the Secret Manager plugin.
 */
class PluginStoreApiKeyProviderImpl : PluginStoreApiKeyProvider {

    private val logger = BossLogger.forComponent("PluginStoreApiKeyProvider")

    override suspend fun createApiKey(
        name: String,
        scopes: List<String>,
        expiresInDays: Int?
    ): Result<ApiKeyCreationResult> {
        return try {
            logger.info(LogCategory.NETWORK, "Creating API key", mapOf(
                "name" to name,
                "scopes" to scopes.joinToString(","),
                "expiresInDays" to (expiresInDays?.toString() ?: "never")
            ))

            val request = CreateApiKeyRequest(
                name = name,
                scopes = scopes,
                expiresInDays = expiresInDays
            )

            val response = PluginStoreClient.createApiKey(request)

            val apiKey = response.apiKey
            val keyInfo = response.keyInfo

            if (!response.success || apiKey == null || keyInfo == null) {
                logger.error(LogCategory.NETWORK, "Failed to create API key: ${response.error}")
                return Result.failure(Exception(response.error ?: "Unknown error"))
            }

            Result.success(ApiKeyCreationResult(
                apiKey = apiKey,
                keyInfo = ApiKeyInfo(
                    id = keyInfo.id,
                    name = keyInfo.name,
                    keyPrefix = keyInfo.keyPrefix,
                    scopes = keyInfo.scopes,
                    createdAt = parseTimestamp(keyInfo.createdAt),
                    lastUsedAt = keyInfo.lastUsedAt?.let { parseTimestamp(it) },
                    expiresAt = keyInfo.expiresAt?.let { parseTimestamp(it) },
                    isRevoked = false
                )
            ))
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Failed to create API key", error = e)
            Result.failure(e)
        }
    }

    override suspend fun listApiKeys(): Result<List<ApiKeyInfo>> {
        return try {
            logger.debug(LogCategory.NETWORK, "Listing API keys")

            val response = PluginStoreClient.listApiKeys()

            if (!response.success) {
                logger.error(LogCategory.NETWORK, "Failed to list API keys: ${response.error}")
                return Result.failure(Exception(response.error ?: "Unknown error"))
            }

            val keys = response.keys.map { key ->
                ApiKeyInfo(
                    id = key.id,
                    name = key.name,
                    keyPrefix = key.keyPrefix,
                    scopes = key.scopes,
                    createdAt = parseTimestamp(key.createdAt),
                    lastUsedAt = key.lastUsedAt?.let { parseTimestamp(it) },
                    expiresAt = key.expiresAt?.let { parseTimestamp(it) },
                    isRevoked = key.isExpired
                )
            }

            Result.success(keys)
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Failed to list API keys", error = e)
            Result.failure(e)
        }
    }

    override suspend fun revokeApiKey(keyId: String): Result<Unit> {
        return try {
            logger.info(LogCategory.NETWORK, "Revoking API key", mapOf("keyId" to keyId))

            val response = PluginStoreClient.revokeApiKey(keyId)

            if (!response.success) {
                logger.error(LogCategory.NETWORK, "Failed to revoke API key: ${response.error}")
                return Result.failure(Exception(response.error ?: "Unknown error"))
            }

            logger.info(LogCategory.NETWORK, "API key revoked successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Failed to revoke API key", error = e)
            Result.failure(e)
        }
    }

    override suspend fun canManageApiKeys(): Boolean {
        // Check if user is admin via PluginStoreConfig
        // or has plugin_admin role via AuthStateManager
        val isAdmin = PluginStoreConfig.isAdmin
        val hasPluginAdminRole = AuthStateManager.currentUser.value?.hasRole("plugin_admin") == true

        logger.debug(LogCategory.AUTH, "Checking API key management permission", mapOf(
            "isAdmin" to isAdmin,
            "hasPluginAdminRole" to hasPluginAdminRole
        ))

        return isAdmin || hasPluginAdminRole
    }

    /**
     * Parse ISO timestamp to epoch milliseconds.
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            Instant.from(DateTimeFormatter.ISO_INSTANT.parse(timestamp)).toEpochMilli()
        } catch (_: Exception) {
            try {
                // Try parsing with offset
                Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp)).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }
}
