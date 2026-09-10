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
    // The four columns `get_user_secrets`/`search_user_secrets` gained from the organisation
    // migration (BossConsole#146). Nullable with no default reason to be strict: `org_id` is
    // genuinely absent for a personal secret, and `is_org_owned`/`can_manage`, though the RPC
    // computes them as plain boolean expressions today, follow the same "every projected column
    // is optional" rule as everything else here - a later migration changing how they're derived
    // must degrade to "unknown", never crash the whole list.
    @SerialName("org_id")
    val orgId: String? = null,
    @SerialName("org_slug")
    val orgSlug: String? = null,
    @SerialName("is_org_owned")
    val isOrgOwned: Boolean? = null,
    // Absent (null) means "unknown", and callers must treat that as "cannot manage" - the same
    // fail-closed reading the server itself uses via can_manage_secret(). Never default this to
    // true: a client on an older build that has never seen this column must not grant an edit
    // affordance the server may since have revoked.
    @SerialName("can_manage")
    val canManage: Boolean? = null,
) {
    /** Fail-closed reading of [canManage]: unknown (null) is "no". */
    val canManageOrDeny: Boolean get() = canManage == true
}

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
    val twofaSecret: String? = null, // Plaintext TOTP seed returned by the authorized RPC; encrypted in storage
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
    // "owner" | "org" | whatever secret_shares.access_level holds (e.g. "read"/"write").
    // "org" is the value BossConsole#146 is about: an organisation migration added a fourth
    // UNION branch to get_user_secrets_with_shared for org-owned secrets, and every access_level
    // consumer that only expected "owner"/"read"/"write" falls through it silently.
    @SerialName("access_level")
    val accessLevel: String,
    // The five columns get_user_secrets_with_shared gained from the organisation migration
    // (BossConsole#146). See SecretEntry's matching fields for why these are nullable even
    // though the RPC computes most of them as plain (non-null) boolean expressions today.
    @SerialName("org_id")
    val orgId: String? = null,
    @SerialName("org_slug")
    val orgSlug: String? = null,
    @SerialName("is_org_owned")
    val isOrgOwned: Boolean? = null,
    // The owning org's slug for access_level="org" (source 4), or the target org's
    // slug for an org share (source 5). It can equal orgSlug; the fields are not exclusive.
    // A creator's org-owned secret has access_level="owner", so use isOrgOwned to
    // identify org ownership rather than testing accessLevel == "org".
    @SerialName("shared_with_org_slug")
    val sharedWithOrgSlug: String? = null,
    @SerialName("can_manage")
    val canManage: Boolean? = null,
) {
    /** Fail-closed reading of [canManage]: unknown (null) is "no". */
    val canManageOrDeny: Boolean get() = canManage == true

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
            orgId = orgId,
            orgSlug = orgSlug,
            isOrgOwned = isOrgOwned,
            canManage = canManage,
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
    // LEFT JOIN auth.users can yield a null email. Preserve unknown identity as null;
    // coercing it to an invented non-null default would lose that meaning.
    @SerialName("shared_by_email")
    val sharedByEmail: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val notes: String? = null,
    // The two columns get_secret_shares gained from the organisation migration
    // (BossConsole#146): who a secret was shared with when the target was an organisation
    // rather than a user or a role. Both null for a user/role share, same as
    // sharedWithUserId/sharedWithRoleId already are for the other two share kinds.
    @SerialName("shared_with_org_id")
    val sharedWithOrgId: String? = null,
    @SerialName("shared_with_org_slug")
    val sharedWithOrgSlug: String? = null,
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
