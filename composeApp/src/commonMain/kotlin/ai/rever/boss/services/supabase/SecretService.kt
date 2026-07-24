package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.CreateSecretRequest
import ai.rever.boss.services.supabase.models.PaginatedSecrets
import ai.rever.boss.services.supabase.models.PaginatedSecretsWithSharing
import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import ai.rever.boss.services.supabase.models.SecretShareEntry
import ai.rever.boss.services.supabase.models.ShareSecretRequest
import ai.rever.boss.services.supabase.models.UnshareSecretRequest
import ai.rever.boss.services.supabase.models.UpdateSecretRequest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Service for managing secrets (website credentials)
 *
 * This service provides methods to:
 * - Get user secrets (with decryption on server)
 * - Search secrets by website or username
 * - Create new secrets with encryption
 * - Update existing secrets
 * - Delete secrets
 *
 * Security:
 * - All operations require authenticated user
 * - RLS policies ensure users only access their own secrets
 * - Passwords are encrypted server-side using pgcrypto
 * - Decryption happens on server, decrypted password sent over HTTPS
 *
 * Usage:
 * ```kotlin
 * // Get all secrets for current user
 * val result = SecretService.getUserSecrets(limit = 50, offset = 0)
 *
 * // Create a new secret
 * val createResult = SecretService.createSecret(
 *     CreateSecretRequest(
 *         website = "github.com",
 *         username = "user@example.com",
 *         password = "securepassword123",
 *         tags = listOf("work", "development")
 *     )
 * )
 * ```
 */
object SecretService {
    private val client
        get() = SupabaseConfig.client

    /**
     * Get user secrets with pagination
     *
     * @param limit Maximum number of secrets to return
     * @param offset Number of secrets to skip
     * @return Paginated result with decrypted secrets
     */
    suspend fun getUserSecrets(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<PaginatedSecrets> =
        try {
            val params =
                buildJsonObject {
                    put("p_limit", limit)
                    put("p_offset", offset)
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "get_user_secrets",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val secrets = Json.decodeFromJsonElement<List<SecretEntry>>(jsonElement)
            val hasMore = secrets.size >= limit

            Result.success(PaginatedSecrets(data = secrets, hasMore = hasMore))
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Search user secrets by website or username
     *
     * @param query Search query (matches website or username)
     * @param limit Maximum number of secrets to return
     * @param offset Number of secrets to skip
     * @return Paginated result with matching secrets
     */
    suspend fun searchSecrets(
        query: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<PaginatedSecrets> =
        try {
            val params =
                buildJsonObject {
                    put("p_query", query)
                    put("p_limit", limit)
                    put("p_offset", offset)
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "search_user_secrets",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val secrets = Json.decodeFromJsonElement<List<SecretEntry>>(jsonElement)

            // Check if there might be more results
            val hasMore = secrets.size >= limit

            Result.success(
                PaginatedSecrets(
                    data = secrets,
                    hasMore = hasMore,
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Create a new secret
     *
     * @param request Secret creation request with website, username, password, etc.
     * @return Result with success/failure
     */
    suspend fun createSecret(request: CreateSecretRequest): Result<Unit> {
        return try {
            request.validate().getOrElse { return Result.failure(it) }

            val params =
                buildJsonObject {
                    put("p_website", request.website)
                    put("p_username", request.username)
                    put("p_password", request.password)
                    if (request.notes != null) {
                        put("p_notes", request.notes)
                    }
                    if (request.expirationDate != null) {
                        put("p_expiration_date", request.expirationDate)
                    }
                    if (request.tags.isNotEmpty()) {
                        put("p_tags", JsonArray(request.tags.map { JsonPrimitive(it) }))
                    }
                    put("p_twofa_enabled", request.twofaEnabled)
                    if (request.twofaType != null) {
                        put("p_twofa_type", request.twofaType)
                    }
                    if (request.recoveryCodes.isNotEmpty()) {
                        put("p_recovery_codes", JsonArray(request.recoveryCodes.map { JsonPrimitive(it) }))
                    }
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "create_secret",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to create secret"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update an existing secret
     *
     * @param request Secret update request with all fields
     * @return Result with success/failure
     */
    suspend fun updateSecret(request: UpdateSecretRequest): Result<Unit> {
        return try {
            // Validate request
            request.validate().getOrElse { return Result.failure(it) }

            val params =
                buildJsonObject {
                    put("p_secret_id", request.secretId)
                    put("p_website", request.website)
                    put("p_username", request.username)
                    put("p_password", request.password)
                    if (request.notes != null) {
                        put("p_notes", request.notes)
                    }
                    if (request.expirationDate != null) {
                        put("p_expiration_date", request.expirationDate)
                    }
                    if (request.tags.isNotEmpty()) {
                        put("p_tags", JsonArray(request.tags.map { JsonPrimitive(it) }))
                    }
                    put("p_twofa_enabled", request.twofaEnabled)
                    if (request.twofaType != null) {
                        put("p_twofa_type", request.twofaType)
                    }
                    if (request.recoveryCodes.isNotEmpty()) {
                        put("p_recovery_codes", JsonArray(request.recoveryCodes.map { JsonPrimitive(it) }))
                    }
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "update_secret",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to update secret"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a secret
     *
     * @param secretId ID of the secret to delete
     * @return Result with success/failure
     */
    suspend fun deleteSecret(secretId: String): Result<Unit> =
        try {
            val params =
                buildJsonObject {
                    put("p_secret_id", secretId)
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "delete_secret",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to delete secret"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Get user secrets including shared secrets
     *
     * @param limit Maximum number of secrets to return
     * @param offset Number of secrets to skip
     * @return Paginated result with decrypted secrets (own + shared)
     */
    suspend fun getUserSecretsWithShared(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<PaginatedSecrets> =
        try {
            val params =
                buildJsonObject {
                    put("p_limit", limit)
                    put("p_offset", offset)
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "get_user_secrets_with_shared",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val secretsWithSharing = Json.decodeFromJsonElement<List<SecretEntryWithSharing>>(jsonElement)
            val secrets = secretsWithSharing.map { it.toSecretEntry() }
            val hasMore = secrets.size >= limit

            Result.success(PaginatedSecrets(data = secrets, hasMore = hasMore))
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Get user secrets with sharing information (keeps sharing metadata)
     *
     * Similar to getUserSecretsWithShared but returns SecretEntryWithSharing objects
     * which include ownership and sharing context (isOwner, sharedByEmail, accessLevel).
     * Used by the user-level secret list panel to display ownership badges.
     *
     * @param limit Maximum number of secrets to return
     * @param offset Number of secrets to skip
     * @return Paginated result with secrets including sharing metadata
     */
    suspend fun getUserSecretsWithSharingInfo(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<PaginatedSecretsWithSharing> =
        try {
            val params =
                buildJsonObject {
                    put("p_limit", limit)
                    put("p_offset", offset)
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "get_user_secrets_with_shared",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val secretsWithSharing = Json.decodeFromJsonElement<List<SecretEntryWithSharing>>(jsonElement)
            val hasMore = secretsWithSharing.size >= limit

            Result.success(PaginatedSecretsWithSharing(data = secretsWithSharing, hasMore = hasMore))
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Share a secret with a user or role
     *
     * @param request Share request with secret ID and target user/role
     * @return Result with success/failure
     */
    suspend fun shareSecret(request: ShareSecretRequest): Result<Unit> {
        return try {
            request.validate().getOrElse { return Result.failure(it) }

            val params =
                buildJsonObject {
                    put("p_secret_id", request.secretId)
                    if (request.targetUserId != null) {
                        put("p_target_user_id", request.targetUserId)
                    }
                    if (request.targetRoleId != null) {
                        put("p_target_role_id", request.targetRoleId)
                    }
                    if (request.notes != null) {
                        put("p_notes", request.notes)
                    }
                    if (request.expiresAt != null) {
                        put("p_expires_at", request.expiresAt)
                    }
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "share_secret",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to share secret"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unshare a secret (revoke access)
     *
     * @param request Unshare request with secret ID and target user/role
     * @return Result with success/failure
     */
    suspend fun unshareSecret(request: UnshareSecretRequest): Result<Unit> {
        return try {
            request.validate().getOrElse { return Result.failure(it) }

            val params =
                buildJsonObject {
                    put("p_secret_id", request.secretId)
                    if (request.targetUserId != null) {
                        put("p_target_user_id", request.targetUserId)
                    }
                    if (request.targetRoleId != null) {
                        put("p_target_role_id", request.targetRoleId)
                    }
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "unshare_secret",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to unshare secret"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get all shares for a secret
     *
     * @param secretId ID of the secret to get shares for
     * @return Result with list of share entries
     */
    suspend fun getSecretShares(secretId: String): Result<List<SecretShareEntry>> =
        try {
            val params =
                buildJsonObject {
                    put("p_secret_id", secretId)
                }

            val postgrestResult =
                client.postgrest.rpc(
                    function = "get_secret_shares",
                    parameters = params,
                )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val shares = Json.decodeFromJsonElement<List<SecretShareEntry>>(jsonElement)

            Result.success(shares)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * RPC response structure for create/update/delete operations
     */
    @Serializable
    private data class RpcResponse(
        val success: Boolean,
        val error: String? = null,
        val message: String? = null,
        val secret_id: String? = null, // ID of created/updated secret
        val target_email: String? = null, // Email of user shared with (for share_secret)
        val target_role: String? = null, // Name of role shared with (for share_secret)
        val revoked_count: Int? = null, // Number of shares revoked (for unshare_secret)
    )
}
