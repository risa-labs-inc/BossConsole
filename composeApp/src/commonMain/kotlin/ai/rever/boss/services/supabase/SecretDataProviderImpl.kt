package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithAccessData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingAccessData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretEntryWithAccessData
import ai.rever.boss.plugin.api.SecretEntryWithSharingAccessData
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.SecretMetadataData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.SecretShareWithTargetData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import ai.rever.boss.services.supabase.models.CreateSecretRequest
import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import ai.rever.boss.services.supabase.models.SecretShareEntry
import ai.rever.boss.services.supabase.models.ShareSecretRequest
import ai.rever.boss.services.supabase.models.UnshareSecretRequest
import ai.rever.boss.services.supabase.models.UpdateSecretRequest

/**
 * Implementation of SecretDataProvider that wraps SecretService.
 *
 * This adapter allows the SecretManager panel to be extracted to a separate
 * plugin module while keeping the actual Supabase service in composeApp.
 */
class SecretDataProviderImpl : SecretDataProvider {
    override suspend fun getUserSecrets(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> =
        SecretService.getUserSecrets(limit, offset).map { paginated ->
            PaginatedSecretsData(
                data = paginated.data.map { it.toPluginData() },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun getUserSecretsWithAccess(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsWithAccessData> =
        SecretService.getUserSecrets(limit, offset).map { paginated ->
            PaginatedSecretsWithAccessData(
                data = paginated.data.map { entry -> entry.toPluginAccessData(entry.toPluginData()) },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun getUserSecretsWithSharingInfo(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsWithSharingData> =
        SecretService.getUserSecretsWithSharingInfo(limit, offset).map { paginated ->
            PaginatedSecretsWithSharingData(
                data = paginated.data.map { it.toPluginDataWithSharing() },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun searchSecrets(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> =
        SecretService.searchSecrets(query, limit, offset).map { paginated ->
            PaginatedSecretsData(
                data = paginated.data.map { it.toPluginData() },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun getUserSecretsWithSharingAccess(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsWithSharingAccessData> =
        SecretService.getUserSecretsWithSharingInfo(limit, offset).map { paginated ->
            PaginatedSecretsWithSharingAccessData(
                data = paginated.data.map { entry -> entry.toPluginSharingAccessData(entry.toPluginDataWithSharing()) },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun searchSecretsWithAccess(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsWithAccessData> =
        SecretService.searchSecrets(query, limit, offset).map { paginated ->
            PaginatedSecretsWithAccessData(
                data = paginated.data.map { entry -> entry.toPluginAccessData(entry.toPluginData()) },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> =
        SecretService.createSecret(request.toServiceRequest())

    override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> =
        SecretService.updateSecret(request.toServiceRequest())

    override suspend fun deleteSecret(id: String): Result<Unit> = SecretService.deleteSecret(id)

    override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> =
        SecretService.getSecretShares(secretId).map { shares ->
            shares.map { it.toPluginData() }
        }

    override suspend fun getSecretSharesWithTargets(secretId: String): Result<List<SecretShareWithTargetData>> =
        SecretService.getSecretShares(secretId).map { shares ->
            shares.map { entry -> entry.toPluginTargetData(entry.toPluginData()) }
        }

    override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> = SecretService.shareSecret(request.toServiceRequest())

    override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> =
        SecretService.unshareSecret(request.toServiceRequest())

    // Mapping functions from internal types to plugin API types

    private fun SecretEntry.toPluginData(): SecretEntryData =
        SecretEntryData(
            id = id,
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            metadata =
                metadata?.let { meta ->
                    SecretMetadataData(
                        twofaEnabled = meta.twofaEnabled,
                        twofaType = meta.twofaType,
                        twofaSecret = meta.twofaSecret,
                        recoveryCodes = meta.recoveryCodes,
                    )
                },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun SecretEntryWithSharing.toPluginDataWithSharing(): SecretEntryWithSharingData =
        SecretEntryWithSharingData(
            id = id,
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            metadata =
                metadata?.let { meta ->
                    SecretMetadataData(
                        twofaEnabled = meta.twofaEnabled,
                        twofaType = meta.twofaType,
                        twofaSecret = meta.twofaSecret,
                        recoveryCodes = meta.recoveryCodes,
                    )
                },
            createdAt = createdAt,
            updatedAt = updatedAt,
            isOwner = isOwner,
            sharedByEmail = sharedByEmail,
            accessLevel = accessLevel,
        )

    private fun SecretShareEntry.toPluginData(): SecretShareData =
        SecretShareData(
            shareId = shareId,
            sharedWithUserId = sharedWithUserId,
            sharedWithUserEmail = sharedWithUserEmail,
            sharedWithRoleId = sharedWithRoleId,
            sharedWithRoleName = sharedWithRoleName,
            accessLevel = accessLevel,
            // Unknown sharer collapses to "" rather than widening SecretShareData: the IPC
            // path already does exactly this (SecretDataProviderProxy), so the two agree, and
            // making the plugin-facing field nullable would break binary compatibility.
            sharedByEmail = sharedByEmail ?: "",
            createdAt = createdAt,
            expiresAt = expiresAt,
            notes = notes,
        )

    private fun CreateSecretRequestData.toServiceRequest(): CreateSecretRequest =
        CreateSecretRequest(
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            twofaEnabled = twofaEnabled,
            twofaType = twofaType,
            recoveryCodes = recoveryCodes,
        )

    private fun UpdateSecretRequestData.toServiceRequest(): UpdateSecretRequest =
        UpdateSecretRequest(
            secretId = secretId,
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            twofaEnabled = twofaEnabled,
            twofaType = twofaType,
            recoveryCodes = recoveryCodes,
        )

    private fun ShareSecretRequestData.toServiceRequest(): ShareSecretRequest =
        ShareSecretRequest(
            secretId = secretId,
            targetUserId = targetUserId,
            targetRoleId = targetRoleId,
            notes = notes,
            expiresAt = expiresAt,
        )

    private fun UnshareSecretRequestData.toServiceRequest(): UnshareSecretRequest =
        UnshareSecretRequest(
            secretId = secretId,
            targetUserId = targetUserId,
            targetRoleId = targetRoleId,
        )
}

/** Access-envelope projection kept visible to tests because null must never become an allow. */
internal fun SecretEntry.toPluginAccessData(secretData: SecretEntryData): SecretEntryWithAccessData =
    SecretEntryWithAccessData(
        secret = secretData,
        orgId = orgId,
        orgSlug = orgSlug,
        isOrgOwned = isOrgOwned == true,
        canManage = canManageOrDeny,
    )

/** Organisation target projection shared by the in-process plugin path. */
internal fun SecretShareEntry.toPluginTargetData(shareData: SecretShareData): SecretShareWithTargetData =
    SecretShareWithTargetData(
        share = shareData,
        sharedWithOrgId = sharedWithOrgId,
        sharedWithOrgSlug = sharedWithOrgSlug,
    )

/** Organisation ownership for read-only sharing rows, preserving the old row constructor. */
@Suppress("MaxLineLength")
internal fun SecretEntryWithSharing.toPluginSharingAccessData(secretData: SecretEntryWithSharingData): SecretEntryWithSharingAccessData =
    SecretEntryWithSharingAccessData(
        secret = secretData,
        orgId = orgId,
        orgSlug = orgSlug,
        isOrgOwned = isOrgOwned == true,
        canManage = canManageOrDeny,
    )
