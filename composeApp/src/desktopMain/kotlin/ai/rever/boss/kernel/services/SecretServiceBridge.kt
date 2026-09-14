package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException

/**
 * Kernel-side bridge for `SecretService` — the whole out-of-process password vault surface:
 * list, search, create, update, delete, share and unshare every secret the signed-in user holds.
 *
 * **Every call requires a verified caller identity (BossConsole#53).** Before this, an out-of-process
 * plugin's secrets access was unlimited and unattributed: [PluginUIServiceBridge] is the one bridge
 * [ProcessIdentityInterceptor]'s own KDoc names as actually checking identity, and this was one of the
 * "fourteen other service bridges... none of them authenticated today" it lists — the single most
 * sensitive one among them, since every other bridge's worst case is UI/workspace state while this one
 * is the password vault. A caller with no token (any process not spawned through
 * `ai.rever.boss.process.ProcessSpawner`, which is every legitimate plugin in production - see
 * `ChildProcessBootstrap.processToken`) is refused with `PERMISSION_DENIED` before the request ever
 * reaches [provider], on every method, not only the mutating ones: an unattributed read of the vault
 * list is exactly the exposure this closes. The verified identity is logged with every mutating call so
 * a compromised or rogue plugin's secrets activity is attributable after the fact, not just blocked.
 */
class SecretServiceBridge(
    private val provider: SecretDataProvider,
) : SecretServiceGrpcKt.SecretServiceCoroutineImplBase() {
    override suspend fun getUserSecrets(request: SecretPaginatedRequest): PaginatedSecretsResponse {
        authenticatedCallerOrRefuse("getUserSecrets")
        val result = provider.getUserSecrets(request.limit, request.offset)
        return result.fold(
            onSuccess = { paginated ->
                PaginatedSecretsResponse
                    .newBuilder()
                    .addAllSecrets(paginated.data.map { it.toProto() })
                    .setHasMore(paginated.hasMore)
                    .setSuccess(true)
                    .build()
            },
            onFailure = { error ->
                PaginatedSecretsResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )
    }

    override suspend fun getUserSecretsWithSharingInfo(request: SecretPaginatedRequest): PaginatedSecretsWithSharingResponse {
        authenticatedCallerOrRefuse("getUserSecretsWithSharingInfo")
        val result = provider.getUserSecretsWithSharingInfo(request.limit, request.offset)
        return result.fold(
            onSuccess = { paginated ->
                PaginatedSecretsWithSharingResponse
                    .newBuilder()
                    .addAllSecrets(
                        paginated.data.map { entry ->
                            SecretWithSharingProto
                                .newBuilder()
                                .setSecret(
                                    SecretEntryProto
                                        .newBuilder()
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
                                        .build(),
                                ).build()
                        },
                    ).setHasMore(paginated.hasMore)
                    .setSuccess(true)
                    .build()
            },
            onFailure = { error ->
                PaginatedSecretsWithSharingResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )
    }

    override suspend fun searchSecrets(request: SearchSecretsRequest): PaginatedSecretsResponse {
        authenticatedCallerOrRefuse("searchSecrets")
        val result = provider.searchSecrets(request.query, request.limit, request.offset)
        return result.fold(
            onSuccess = { paginated ->
                PaginatedSecretsResponse
                    .newBuilder()
                    .addAllSecrets(paginated.data.map { it.toProto() })
                    .setHasMore(paginated.hasMore)
                    .setSuccess(true)
                    .build()
            },
            onFailure = { error ->
                PaginatedSecretsResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )
    }

    override suspend fun createSecret(request: CreateSecretProtoRequest): SecretOperationResult {
        val caller = authenticatedCallerOrRefuse("createSecret")
        logMutation("createSecret", caller)
        val result =
            provider.createSecret(
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
                ),
            )
        return result.toOperationResult()
    }

    override suspend fun updateSecret(request: UpdateSecretProtoRequest): SecretOperationResult {
        val caller = authenticatedCallerOrRefuse("updateSecret")
        logMutation("updateSecret", caller, mapOf("secretId" to request.secretId))
        val result =
            provider.updateSecret(
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
                ),
            )
        return result.toOperationResult()
    }

    override suspend fun deleteSecret(request: SecretIdRequest): SecretOperationResult {
        val caller = authenticatedCallerOrRefuse("deleteSecret")
        logMutation("deleteSecret", caller, mapOf("secretId" to request.id))
        return provider.deleteSecret(request.id).toOperationResult()
    }

    override suspend fun getSecretShares(request: SecretIdRequest): SecretShareListResponse {
        authenticatedCallerOrRefuse("getSecretShares")
        val result = provider.getSecretShares(request.id)
        return result.fold(
            onSuccess = { shares ->
                SecretShareListResponse
                    .newBuilder()
                    .addAllShares(shares.map { it.toShareProto() })
                    .build()
            },
            onFailure = {
                SecretShareListResponse.getDefaultInstance()
            },
        )
    }

    override suspend fun shareSecret(request: ShareSecretProtoRequest): SecretOperationResult {
        val caller = authenticatedCallerOrRefuse("shareSecret")
        logMutation("shareSecret", caller, mapOf("secretId" to request.secretId))
        val result =
            provider.shareSecret(
                ShareSecretRequestData(
                    secretId = request.secretId,
                    targetUserId = request.targetUserId.ifEmpty { null },
                    targetRoleId = request.targetRoleId.ifEmpty { null },
                    notes = request.notes.ifEmpty { null },
                    expiresAt = request.expiresAt.ifEmpty { null },
                ),
            )
        return result.toOperationResult()
    }

    override suspend fun unshareSecret(request: UnshareSecretProtoRequest): SecretOperationResult {
        val caller = authenticatedCallerOrRefuse("unshareSecret")
        logMutation("unshareSecret", caller, mapOf("secretId" to request.secretId))
        val result =
            provider.unshareSecret(
                UnshareSecretRequestData(
                    secretId = request.secretId,
                    targetUserId = request.targetUserId.ifEmpty { null },
                    targetRoleId = request.targetRoleId.ifEmpty { null },
                ),
            )
        return result.toOperationResult()
    }

    /**
     * The verified identity behind this call, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors [PluginUIServiceBridge]'s own helper (BossConsole#53) — fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     *
     * Unary RPCs only: [ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID] is a per-call snapshot,
     * correct for unary methods. A streaming RPC on this bridge must read
     * [ProcessIdentityInterceptor.CURRENT_IDENTITY] instead (see [PluginUIServiceBridge.streamUI]),
     * so a mid-stream revocation is honoured.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: run {
            logger.warn(
                LogCategory.AUTH,
                "Refused $rpc: no verified process identity on this call",
                mapOf("rpc" to rpc),
            )
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
        }

    /** Attributed audit line for a vault mutation — the caller identity a refusal would otherwise hide. */
    private fun logMutation(
        rpc: String,
        caller: String,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        logger.info(LogCategory.AUTH, "Secret vault mutation", mapOf("rpc" to rpc, "caller" to caller) + extra)
    }

    private fun SecretEntryData.toProto(): SecretEntryProto =
        SecretEntryProto
            .newBuilder()
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
        SecretShareProto
            .newBuilder()
            .setShareId(shareId)
            .setSharedWithUserId(sharedWithUserId ?: "")
            .setSharedWithUserEmail(sharedWithUserEmail ?: "")
            .setSharedWithRoleId(sharedWithRoleId ?: "")
            .setSharedWithRoleName(sharedWithRoleName ?: "")
            .setAccessLevel(accessLevel)
            .setSharedByEmail(sharedByEmail ?: "")
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
                SecretOperationResult
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )

    private companion object {
        val logger = BossLogger.forComponent("SecretServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
