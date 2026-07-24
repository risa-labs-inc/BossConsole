package ai.rever.boss.services.supabase.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A secret entry containing website credentials and metadata
 */
@Serializable
data class SecretEntry(
    val id: String,
    val website: String,
    val username: String,
    val password: String, // Decrypted on client
    val notes: String? = null,
    @SerialName("expiration_date")
    val expirationDate: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: SecretMetadata? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Metadata for a secret (2FA information)
 */
@Serializable
data class SecretMetadata(
    @SerialName("twofa_enabled")
    val twofaEnabled: Boolean = false,
    @SerialName("twofa_type")
    val twofaType: String? = null, // 'app', 'sms', 'email', 'hardware'
    @SerialName("twofa_secret")
    val twofaSecret: String? = null, // Encrypted 2FA secret (for TOTP apps)
    @SerialName("recovery_codes")
    val recoveryCodes: List<String> = emptyList(),
)

/**
 * Request to create a new secret
 */
data class CreateSecretRequest(
    val website: String,
    val username: String,
    val password: String,
    val notes: String? = null,
    val expirationDate: String? = null,
    val tags: List<String> = emptyList(),
    val twofaEnabled: Boolean = false,
    val twofaType: String? = null,
    val recoveryCodes: List<String> = emptyList(),
) {
    /**
     * Validate the request data
     */
    fun validate(): Result<Unit> {
        if (website.isBlank()) {
            return Result.failure(IllegalArgumentException("Website cannot be empty"))
        }
        if (username.isBlank()) {
            return Result.failure(IllegalArgumentException("Username cannot be empty"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("Password cannot be empty"))
        }
        if (twofaEnabled && twofaType == null) {
            return Result.failure(IllegalArgumentException("2FA type must be specified when 2FA is enabled"))
        }
        if (twofaType != null && twofaType !in listOf("app", "sms", "email", "hardware")) {
            return Result.failure(IllegalArgumentException("Invalid 2FA type: $twofaType"))
        }
        return Result.success(Unit)
    }
}

/**
 * Request to update an existing secret
 */
data class UpdateSecretRequest(
    val secretId: String,
    val website: String,
    val username: String,
    val password: String,
    val notes: String? = null,
    val expirationDate: String? = null,
    val tags: List<String> = emptyList(),
    val twofaEnabled: Boolean = false,
    val twofaType: String? = null,
    val recoveryCodes: List<String> = emptyList(),
) {
    /**
     * Validate the request data
     */
    fun validate(): Result<Unit> {
        if (secretId.isBlank()) {
            return Result.failure(IllegalArgumentException("Secret ID cannot be empty"))
        }
        if (website.isBlank()) {
            return Result.failure(IllegalArgumentException("Website cannot be empty"))
        }
        if (username.isBlank()) {
            return Result.failure(IllegalArgumentException("Username cannot be empty"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("Password cannot be empty"))
        }
        if (twofaEnabled && twofaType == null) {
            return Result.failure(IllegalArgumentException("2FA type must be specified when 2FA is enabled"))
        }
        if (twofaType != null && twofaType !in listOf("app", "sms", "email", "hardware")) {
            return Result.failure(IllegalArgumentException("Invalid 2FA type: $twofaType"))
        }
        return Result.success(Unit)
    }
}

/**
 * Paginated result for secret queries
 */
data class PaginatedSecrets(
    val data: List<SecretEntry>,
    val hasMore: Boolean,
    val total: Int? = null,
)

/**
 * Secret entry with sharing information
 * Used when fetching secrets that may be shared with the user
 */
@Serializable
data class SecretEntryWithSharing(
    val id: String,
    val website: String,
    val username: String,
    val password: String, // Decrypted on client
    val notes: String? = null,
    @SerialName("expiration_date")
    val expirationDate: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: SecretMetadata? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("is_owner")
    val isOwner: Boolean,
    @SerialName("shared_by_email")
    val sharedByEmail: String? = null,
    @SerialName("access_level")
    val accessLevel: String,
) {
    /**
     * Convert to regular SecretEntry for compatibility
     */
    fun toSecretEntry(): SecretEntry =
        SecretEntry(
            id = id,
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            metadata = metadata,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

/**
 * A share entry representing who has access to a secret
 */
@Serializable
data class SecretShareEntry(
    @SerialName("share_id")
    val shareId: String,
    @SerialName("shared_with_user_id")
    val sharedWithUserId: String? = null,
    @SerialName("shared_with_user_email")
    val sharedWithUserEmail: String? = null,
    @SerialName("shared_with_role_id")
    val sharedWithRoleId: String? = null,
    @SerialName("shared_with_role_name")
    val sharedWithRoleName: String? = null,
    @SerialName("access_level")
    val accessLevel: String,
    @SerialName("shared_by_email")
    val sharedByEmail: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val notes: String? = null,
)

/**
 * Request to share a secret with a user or role
 */
data class ShareSecretRequest(
    val secretId: String,
    val targetUserId: String? = null,
    val targetRoleId: String? = null,
    val notes: String? = null,
    val expiresAt: String? = null,
) {
    /**
     * Validate the request data
     */
    fun validate(): Result<Unit> {
        if (secretId.isBlank()) {
            return Result.failure(IllegalArgumentException("Secret ID cannot be empty"))
        }
        if (targetUserId == null && targetRoleId == null) {
            return Result.failure(IllegalArgumentException("Must specify either targetUserId or targetRoleId"))
        }
        if (targetUserId != null && targetRoleId != null) {
            return Result.failure(IllegalArgumentException("Cannot specify both targetUserId and targetRoleId"))
        }
        return Result.success(Unit)
    }
}

/**
 * Request to unshare a secret (revoke access)
 */
data class UnshareSecretRequest(
    val secretId: String,
    val targetUserId: String? = null,
    val targetRoleId: String? = null,
) {
    /**
     * Validate the request data
     */
    fun validate(): Result<Unit> {
        if (secretId.isBlank()) {
            return Result.failure(IllegalArgumentException("Secret ID cannot be empty"))
        }
        if (targetUserId == null && targetRoleId == null) {
            return Result.failure(IllegalArgumentException("Must specify either targetUserId or targetRoleId"))
        }
        if (targetUserId != null && targetRoleId != null) {
            return Result.failure(IllegalArgumentException("Cannot specify both targetUserId and targetRoleId"))
        }
        return Result.success(Unit)
    }
}

/**
 * Paginated result for secrets with sharing information
 * Used by user-level secret list to show ownership and sharing details
 */
data class PaginatedSecretsWithSharing(
    val data: List<SecretEntryWithSharing>,
    val hasMore: Boolean,
    val total: Int? = null,
)
