package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.*
import io.grpc.ManagedChannel

/**
 * IPC proxy implementation of SecretDataProvider.
 */
class SecretDataProviderProxy(
    channel: ManagedChannel,
) : SecretDataProvider {
    private val stub = SecretServiceGrpcKt.SecretServiceCoroutineStub(channel)

    override suspend fun getUserSecrets(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> =
        try {
            val resp =
                stub.getUserSecrets(
                    SecretPaginatedRequest
                        .newBuilder()
                        .setLimit(limit)
                        .setOffset(offset)
                        .build(),
                )
            if (resp.success) {
                Result.success(
                    PaginatedSecretsData(
                        data = resp.secretsList.map { it.toData() },
                        hasMore = resp.hasMore,
                    ),
                )
            } else {
                Result.failure(Exception(resp.errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun getUserSecretsWithSharingInfo(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsWithSharingData> =
        try {
            val resp =
                stub.getUserSecretsWithSharingInfo(
                    SecretPaginatedRequest
                        .newBuilder()
                        .setLimit(limit)
                        .setOffset(offset)
                        .build(),
                )
            if (resp.success) {
                Result.success(
                    PaginatedSecretsWithSharingData(
                        data = resp.secretsList.map { it.secret.toDataWithSharing() },
                        hasMore = resp.hasMore,
                    ),
                )
            } else {
                Result.failure(Exception(resp.errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun searchSecrets(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> =
        try {
            val resp =
                stub.searchSecrets(
                    SearchSecretsRequest
                        .newBuilder()
                        .setQuery(query)
                        .setLimit(limit)
                        .setOffset(offset)
                        .build(),
                )
            if (resp.success) {
                Result.success(
                    PaginatedSecretsData(
                        data = resp.secretsList.map { it.toData() },
                        hasMore = resp.hasMore,
                    ),
                )
            } else {
                Result.failure(Exception(resp.errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> =
        try {
            val resp = stub.createSecret(request.toProto())
            if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> =
        try {
            val resp = stub.updateSecret(request.toProto())
            if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun deleteSecret(id: String): Result<Unit> =
        try {
            val resp = stub.deleteSecret(SecretIdRequest.newBuilder().setId(id).build())
            if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> =
        try {
            val resp = stub.getSecretShares(SecretIdRequest.newBuilder().setId(secretId).build())
            Result.success(resp.sharesList.map { it.toData() })
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> =
        try {
            val resp = stub.shareSecret(request.toProto())
            if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> =
        try {
            val resp = stub.unshareSecret(request.toProto())
            if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
        } catch (e: Exception) {
            Result.failure(e)
        }

    // ---- Conversion helpers ----

    private fun SecretEntryProto.toData() =
        SecretEntryData(
            id = id,
            website = website,
            username = username,
            password = password,
            notes = notes.takeIf { it.isNotEmpty() },
            expirationDate = expirationDate.takeIf { it.isNotEmpty() },
            tags = tagsList,
            metadata =
                if (twofaEnabled) {
                    SecretMetadataData(
                        twofaEnabled = true,
                        twofaType = twofaType.takeIf { it.isNotEmpty() },
                        twofaSecret = twofaSecret.takeIf { it.isNotEmpty() },
                        recoveryCodes = recoveryCodesList,
                    )
                } else {
                    null
                },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun SecretEntryProto.toDataWithSharing() =
        SecretEntryWithSharingData(
            id = id,
            website = website,
            username = username,
            password = password,
            notes = notes.takeIf { it.isNotEmpty() },
            expirationDate = expirationDate.takeIf { it.isNotEmpty() },
            tags = tagsList,
            metadata =
                if (twofaEnabled) {
                    SecretMetadataData(
                        twofaEnabled = true,
                        twofaType = twofaType.takeIf { it.isNotEmpty() },
                        twofaSecret = twofaSecret.takeIf { it.isNotEmpty() },
                        recoveryCodes = recoveryCodesList,
                    )
                } else {
                    null
                },
            createdAt = createdAt,
            updatedAt = updatedAt,
            isOwner = isOwner,
            sharedByEmail = sharedByEmail.takeIf { it.isNotEmpty() },
            accessLevel = accessLevel.ifEmpty { "owner" },
        )

    private fun SecretShareProto.toData() =
        SecretShareData(
            shareId = shareId,
            sharedWithUserId = sharedWithUserId.takeIf { it.isNotEmpty() },
            sharedWithUserEmail = sharedWithUserEmail.takeIf { it.isNotEmpty() },
            sharedWithRoleId = sharedWithRoleId.takeIf { it.isNotEmpty() },
            sharedWithRoleName = sharedWithRoleName.takeIf { it.isNotEmpty() },
            accessLevel = accessLevel,
            sharedByEmail = sharedByEmail,
            createdAt = createdAt,
            expiresAt = expiresAt.takeIf { it.isNotEmpty() },
            notes = notes.takeIf { it.isNotEmpty() },
        )

    private fun CreateSecretRequestData.toProto(): CreateSecretProtoRequest =
        CreateSecretProtoRequest
            .newBuilder()
            .setWebsite(website)
            .setUsername(username)
            .setPassword(password)
            .setNotes(notes ?: "")
            .setExpirationDate(expirationDate ?: "")
            .addAllTags(tags)
            .setTwofaEnabled(twofaEnabled)
            .setTwofaType(twofaType ?: "")
            .setTwofaSecret("")
            .addAllRecoveryCodes(recoveryCodes)
            .build()

    private fun UpdateSecretRequestData.toProto(): UpdateSecretProtoRequest =
        UpdateSecretProtoRequest
            .newBuilder()
            .setSecretId(secretId)
            .setWebsite(website)
            .setUsername(username)
            .setPassword(password)
            .setNotes(notes ?: "")
            .setExpirationDate(expirationDate ?: "")
            .addAllTags(tags)
            .setTwofaEnabled(twofaEnabled)
            .setTwofaType(twofaType ?: "")
            .setTwofaSecret("")
            .addAllRecoveryCodes(recoveryCodes)
            .build()

    private fun ShareSecretRequestData.toProto(): ShareSecretProtoRequest =
        ShareSecretProtoRequest
            .newBuilder()
            .setSecretId(secretId)
            .setTargetUserId(targetUserId ?: "")
            .setTargetRoleId(targetRoleId ?: "")
            .setNotes(notes ?: "")
            .setExpiresAt(expiresAt ?: "")
            .build()

    private fun UnshareSecretRequestData.toProto(): UnshareSecretProtoRequest =
        UnshareSecretProtoRequest
            .newBuilder()
            .setSecretId(secretId)
            .setTargetUserId(targetUserId ?: "")
            .setTargetRoleId(targetRoleId ?: "")
            .build()
}
