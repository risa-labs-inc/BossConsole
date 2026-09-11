package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.services.SearchSecretsRequest
import ai.rever.boss.ipc.proto.services.SecretIdRequest
import ai.rever.boss.ipc.proto.services.SecretPaginatedRequest
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
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.SecretShareWithTargetData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretServiceBridgeTest {
    @Test
    fun `list serializes organisation access and preserves paging`() =
        runBlocking {
            val provider = RecordingProvider()
            val response = SecretServiceBridge(provider).getUserSecrets(page(limit = 7, offset = 3))

            assertTrue(response.success)
            assertEquals(7 to 3, provider.pageRequest)
            response.secretsList.single().let { row ->
                assertEquals("org-1", row.orgId)
                assertEquals("acme", row.orgSlug)
                assertTrue(row.isOrgOwned)
                assertTrue(row.canManage)
            }
        }

    @Test
    fun `search serializes a server deny without inferring permission`() =
        runBlocking {
            val provider = RecordingProvider(canManage = false)
            val request =
                SearchSecretsRequest
                    .newBuilder()
                    .setQuery("git")
                    .setLimit(9)
                    .setOffset(4)
                    .build()
            val response = SecretServiceBridge(provider).searchSecrets(request)

            assertEquals(Triple("git", 9, 4), provider.searchRequest)
            assertFalse(response.secretsList.single().canManage)
        }

    @Test
    fun `share list serializes organisation target`() =
        runBlocking {
            val response =
                SecretServiceBridge(RecordingProvider())
                    .getSecretShares(SecretIdRequest.newBuilder().setId("s1").build())

            response.sharesList.single().let { share ->
                assertEquals("org-1", share.sharedWithOrgId)
                assertEquals("acme", share.sharedWithOrgSlug)
            }
        }

    @Test
    fun `sharing list serializes organisation ownership and access level`() =
        runBlocking {
            val response = SecretServiceBridge(RecordingProvider()).getUserSecretsWithSharingInfo(page(5, 1))

            assertTrue(response.success)
            response.secretsList.single().secret.let { row ->
                assertEquals("org-1", row.orgId)
                assertEquals("acme", row.orgSlug)
                assertTrue(row.isOrgOwned)
                assertFalse(row.canManage)
                assertEquals("read", row.accessLevel)
            }
        }

    @Test
    fun `list failure stays an explicit failed response`() =
        runBlocking {
            val response = SecretServiceBridge(RecordingProvider(fail = true)).getUserSecrets(page(50, 0))

            assertFalse(response.success)
            assertEquals("offline", response.errorMessage)
            assertTrue(response.secretsList.isEmpty())
        }

    private class RecordingProvider(
        private val canManage: Boolean = true,
        private val fail: Boolean = false,
    ) : SecretDataProvider {
        var pageRequest: Pair<Int, Int>? = null
        var searchRequest: Triple<String, Int, Int>? = null

        override suspend fun getUserSecretsWithAccess(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithAccessData> {
            pageRequest = limit to offset
            return accessResult()
        }

        override suspend fun searchSecretsWithAccess(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithAccessData> {
            searchRequest = Triple(query, limit, offset)
            return accessResult()
        }

        override suspend fun getSecretSharesWithTargets(secretId: String) =
            Result.success(
                listOf(
                    SecretShareWithTargetData(
                        share =
                            SecretShareData(
                                shareId = "share-1",
                                accessLevel = "read",
                                sharedByEmail = "owner@example.com",
                                createdAt = "now",
                            ),
                        sharedWithOrgId = "org-1",
                        sharedWithOrgSlug = "acme",
                    ),
                ),
            )

        private fun accessResult(): Result<PaginatedSecretsWithAccessData> =
            if (fail) {
                Result.failure(IllegalStateException("offline"))
            } else {
                Result.success(
                    PaginatedSecretsWithAccessData(
                        listOf(
                            SecretEntryWithAccessData(
                                secret = secret(),
                                orgId = "org-1",
                                orgSlug = "acme",
                                isOrgOwned = true,
                                canManage = canManage,
                            ),
                        ),
                        hasMore = false,
                    ),
                )
            }

        override suspend fun getUserSecrets(
            limit: Int,
            offset: Int,
        ) = Result.success(PaginatedSecretsData(listOf(secret()), false))

        override suspend fun searchSecrets(
            query: String,
            limit: Int,
            offset: Int,
        ) = Result.success(PaginatedSecretsData(listOf(secret()), false))

        override suspend fun getSecretShares(secretId: String) = Result.success(emptyList<SecretShareData>())

        override suspend fun getUserSecretsWithSharingInfo(
            limit: Int,
            offset: Int,
        ) = Result.failure<PaginatedSecretsWithSharingData>(UnsupportedOperationException())

        override suspend fun getUserSecretsWithSharingAccess(
            limit: Int,
            offset: Int,
        ) = Result.success(
            PaginatedSecretsWithSharingAccessData(
                data =
                    listOf(
                        SecretEntryWithSharingAccessData(
                            secret = sharingSecret(),
                            orgId = "org-1",
                            orgSlug = "acme",
                            isOrgOwned = true,
                            canManage = false,
                        ),
                    ),
                hasMore = false,
            ),
        )

        override suspend fun createSecret(request: CreateSecretRequestData) = Result.success(Unit)

        override suspend fun updateSecret(request: UpdateSecretRequestData) = Result.success(Unit)

        override suspend fun deleteSecret(id: String) = Result.success(Unit)

        override suspend fun shareSecret(request: ShareSecretRequestData) = Result.success(Unit)

        override suspend fun unshareSecret(request: UnshareSecretRequestData) = Result.success(Unit)
    }

    private companion object {
        fun page(
            limit: Int,
            offset: Int,
        ) = SecretPaginatedRequest
            .newBuilder()
            .setLimit(limit)
            .setOffset(offset)
            .build()

        fun secret() =
            SecretEntryData(
                id = "s1",
                website = "github.com",
                username = "octocat",
                password = "hunter2",
                createdAt = "then",
                updatedAt = "now",
            )

        fun sharingSecret() =
            SecretEntryWithSharingData(
                id = "s1",
                website = "github.com",
                username = "octocat",
                password = "hunter2",
                createdAt = "then",
                updatedAt = "now",
                isOwner = false,
                sharedByEmail = "owner@example.com",
                accessLevel = "read",
            )
    }
}
