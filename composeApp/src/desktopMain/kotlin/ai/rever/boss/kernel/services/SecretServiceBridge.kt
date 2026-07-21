package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData

class SecretServiceBridge(
    private val provider: SecretDataProvider,
) : SecretServiceGrpcKt.SecretServiceCoroutineImplBase() {

    override suspend fun getUserSecrets(request: SecretPaginatedRequest): PaginatedSecretsResponse {
        val result = provider.getUserSecrets(request.limit, request.offset)
        return result.fold(
            onSuccess = { paginated ->
                PaginatedSecretsResponse.newBuilder()
                    .addAllSecrets(paginated.data.map { it.toProto() })
                    .setHasMore(paginated.hasMore)
                    .setSuccess(true)
                    .build()
            },
            onFailure = { error ->
                PaginatedSecretsResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            }
        )
    }

    override suspend fun getUserSecretsWithSharingInfo(request: SecretPaginatedRequest): PaginatedSecretsWithSharingResponse {
        val result = provider.getUserSecretsWithSharingInfo(request.limit, request.offset)
        return result.fold(
            onSuccess = { paginated ->
                PaginatedSecretsWithSharingResponse.newBuilder()
                    .addAllSecrets(paginated.data.map { entry ->
                        SecretWithSharingProto.newBuilder()
                            .setSecret(
                                SecretEntryProto.newBuilder()
                                    .setId(entry.id)
                                    .setWebsite(entry.website)
                                    .setUsername(entry.username)
                                    .setPassword(entry.password)
                                    .setNotes(entry.notes ?: "")
                                    .setExpirationDate(entry.expirationDate ?: "")
                                    .addAllTags(entry.tags)
                                    .setCreatedAt(entry.createdAt)
                                    .setUpdatedAt(entry.updatedAt)
                                    .setIsOwner(entry.isOwner)
                                    .setSharedByEmail(entry.sharedByEmail ?: "")
                                    .setAccessLevel(entry.accessLevel)
                                    .build()
                            )
                            .build()
                    })
                    .setHasMore(paginated.hasMore)
                    .setSuccess(true)
                    .build()
            },
            onFailure = { error ->
                PaginatedSecretsWithSharingResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            }
        )
    }

    override suspend fun searchSecrets(request: SearchSecretsRequest): PaginatedSecretsResponse {
        val result = provider.searchSecrets(request.query, request.limit, request.offset)
        return result.fold(
            onSuccess = { paginated ->
                PaginatedSecretsResponse.newBuilder()
                    .addAllSecrets(paginated.data.map { it.toProto() })
                    .setHasMore(paginated.hasMore)
                    .setSuccess(true)
                    .build()
            },
            onFailure = { error ->
                PaginatedSecretsResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            }
        )
    }

    override suspend fun createSecret(request: CreateSecretProtoRequest): SecretOperationResult {
        val result = provider.createSecret(
            CreateSecretRequestData(
                website = request.website,
                username = request.username,
                password = request.password,
                notes = request.notes.ifEmpty { null },
                expirationDate = request.expirationDate.ifEmpty { null },
                tags = request.tagsList,
                twofaEnabled = request.twofaEnabled,
                twofaType = request.twofaType.ifEmpty { null },
                recoveryCodes = request.recoveryCodesList,
            )
        )
        return result.toOperationResult()
    }

    override suspend fun updateSecret(request: UpdateSecretProtoRequest): SecretOperationResult {
        val result = provider.updateSecret(
            UpdateSecretRequestData(
                secretId = request.secretId,
                website = request.website,
                username = request.username,
                password = request.password,
                notes = request.notes.ifEmpty { null },
                expirationDate = request.expirationDate.ifEmpty { null },
                tags = request.tagsList,
                twofaEnabled = request.twofaEnabled,
                twofaType = request.twofaType.ifEmpty { null },
                recoveryCodes = request.recoveryCodesList,
            )
        )
        return result.toOperationResult()
    }

    override suspend fun deleteSecret(request: SecretIdRequest): SecretOperationResult {
        return provider.deleteSecret(request.id).toOperationResult()
    }

    override suspend fun getSecretShares(request: SecretIdRequest): SecretShareListResponse {
        val result = provider.getSecretShares(request.id)
        return result.fold(
            onSuccess = { shares ->
                SecretShareListResponse.newBuilder()
                    .addAllShares(shares.map { it.toShareProto() })
                    .build()
            },
            onFailure = {
                SecretShareListResponse.getDefaultInstance()
            }
        )
    }

    override suspend fun shareSecret(request: ShareSecretProtoRequest): SecretOperationResult {
        val result = provider.shareSecret(
            ShareSecretRequestData(
                secretId = request.secretId,
                targetUserId = request.targetUserId.ifEmpty { null },
                targetRoleId = request.targetRoleId.ifEmpty { null },
                notes = request.notes.ifEmpty { null },
                expiresAt = request.expiresAt.ifEmpty { null },
            )
        )
        return result.toOperationResult()
    }

    override suspend fun unshareSecret(request: UnshareSecretProtoRequest): SecretOperationResult {
        val result = provider.unshareSecret(
            UnshareSecretRequestData(
                secretId = request.secretId,
                targetUserId = request.targetUserId.ifEmpty { null },
                targetRoleId = request.targetRoleId.ifEmpty { null },
            )
        )
        return result.toOperationResult()
    }

    private fun SecretEntryData.toProto(): SecretEntryProto =
        SecretEntryProto.newBuilder()
            .setId(id)
            .setWebsite(website)
            .setUsername(username)
            .setPassword(password)
            .setNotes(notes ?: "")
            .setExpirationDate(expirationDate ?: "")
            .addAllTags(tags)
            .setCreatedAt(createdAt)
            .setUpdatedAt(updatedAt)
            .build()

    private fun SecretShareData.toShareProto(): SecretShareProto =
        SecretShareProto.newBuilder()
            .setShareId(shareId)
            .setSharedWithUserId(sharedWithUserId ?: "")
            .setSharedWithUserEmail(sharedWithUserEmail ?: "")
            .setSharedWithRoleId(sharedWithRoleId ?: "")
            .setSharedWithRoleName(sharedWithRoleName ?: "")
            .setAccessLevel(accessLevel)
            .setSharedByEmail(sharedByEmail)
            .setCreatedAt(createdAt)
            .setExpiresAt(expiresAt ?: "")
            .setNotes(notes ?: "")
            .build()

    private fun Result<Unit>.toOperationResult(): SecretOperationResult =
        fold(
            onSuccess = {
                SecretOperationResult.newBuilder().setSuccess(true).build()
            },
            onFailure = { error ->
                SecretOperationResult.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            }
        )
}
