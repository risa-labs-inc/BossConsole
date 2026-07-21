package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.SecretMetadataData
import ai.rever.boss.plugin.api.SecretShareData
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

    override suspend fun getUserSecrets(limit: Int, offset: Int): Result<PaginatedSecretsData> {
        return SecretService.getUserSecrets(limit, offset).map { paginated ->
            PaginatedSecretsData(
                data = paginated.data.map { it.toPluginData() },
                hasMore = paginated.hasMore
            )
        }
    }

    override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int): Result<PaginatedSecretsWithSharingData> {
        return SecretService.getUserSecretsWithSharingInfo(limit, offset).map { paginated ->
            PaginatedSecretsWithSharingData(
                data = paginated.data.map { it.toPluginDataWithSharing() },
                hasMore = paginated.hasMore
            )
        }
    }

    override suspend fun searchSecrets(query: String, limit: Int, offset: Int): Result<PaginatedSecretsData> {
        return SecretService.searchSecrets(query, limit, offset).map { paginated ->
            PaginatedSecretsData(
                data = paginated.data.map { it.toPluginData() },
                hasMore = paginated.hasMore
            )
        }
    }

    override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> {
        return SecretService.createSecret(request.toServiceRequest())
    }

    override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> {
        return SecretService.updateSecret(request.toServiceRequest())
    }

    override suspend fun deleteSecret(id: String): Result<Unit> {
        return SecretService.deleteSecret(id)
    }

    override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> {
        return SecretService.getSecretShares(secretId).map { shares ->
            shares.map { it.toPluginData() }
        }
    }

    override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> {
        return SecretService.shareSecret(request.toServiceRequest())
    }

    override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> {
        return SecretService.unshareSecret(request.toServiceRequest())
    }

    // Mapping functions from internal types to plugin API types

    private fun SecretEntry.toPluginData(): SecretEntryData {
        return SecretEntryData(
            id = id,
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            metadata = metadata?.let { meta ->
                SecretMetadataData(
                    twofaEnabled = meta.twofaEnabled,
                    twofaType = meta.twofaType,
                    twofaSecret = meta.twofaSecret,
                    recoveryCodes = meta.recoveryCodes
                )
            },
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun SecretEntryWithSharing.toPluginDataWithSharing(): SecretEntryWithSharingData {
        return SecretEntryWithSharingData(
            id = id,
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            metadata = metadata?.let { meta ->
                SecretMetadataData(
                    twofaEnabled = meta.twofaEnabled,
                    twofaType = meta.twofaType,
                    twofaSecret = meta.twofaSecret,
                    recoveryCodes = meta.recoveryCodes
                )
            },
            createdAt = createdAt,
            updatedAt = updatedAt,
            isOwner = isOwner,
            sharedByEmail = sharedByEmail,
            accessLevel = accessLevel
        )
    }

    private fun SecretShareEntry.toPluginData(): SecretShareData {
        return SecretShareData(
            shareId = shareId,
            sharedWithUserId = sharedWithUserId,
            sharedWithUserEmail = sharedWithUserEmail,
            sharedWithRoleId = sharedWithRoleId,
            sharedWithRoleName = sharedWithRoleName,
            accessLevel = accessLevel,
            sharedByEmail = sharedByEmail,
            createdAt = createdAt,
            expiresAt = expiresAt,
            notes = notes
        )
    }

    private fun CreateSecretRequestData.toServiceRequest(): CreateSecretRequest {
        return CreateSecretRequest(
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            twofaEnabled = twofaEnabled,
            twofaType = twofaType,
            recoveryCodes = recoveryCodes
        )
    }

    private fun UpdateSecretRequestData.toServiceRequest(): UpdateSecretRequest {
        return UpdateSecretRequest(
            secretId = secretId,
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            twofaEnabled = twofaEnabled,
            twofaType = twofaType,
            recoveryCodes = recoveryCodes
        )
    }

    private fun ShareSecretRequestData.toServiceRequest(): ShareSecretRequest {
        return ShareSecretRequest(
            secretId = secretId,
            targetUserId = targetUserId,
            targetRoleId = targetRoleId,
            notes = notes,
            expiresAt = expiresAt
        )
    }

    private fun UnshareSecretRequestData.toServiceRequest(): UnshareSecretRequest {
        return UnshareSecretRequest(
            secretId = secretId,
            targetUserId = targetUserId,
            targetRoleId = targetRoleId
        )
    }
}
