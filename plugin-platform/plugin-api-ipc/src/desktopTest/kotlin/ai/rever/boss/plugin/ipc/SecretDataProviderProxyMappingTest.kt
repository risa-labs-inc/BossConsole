package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.SecretEntryProto
import ai.rever.boss.ipc.proto.services.SecretShareProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretDataProviderProxyMappingTest {
    @Test
    fun `access decoder preserves organisation admin decision`() {
        val decoded =
            baseSecret()
                .toBuilder()
                .setOrgId("org-1")
                .setOrgSlug("acme")
                .setIsOrgOwned(true)
                .setCanManage(true)
                .build()
                .toDataWithAccess()

        assertEquals("s1", decoded.secret.id)
        assertEquals("org-1", decoded.orgId)
        assertEquals("acme", decoded.orgSlug)
        assertTrue(decoded.isOrgOwned)
        assertTrue(decoded.canManage)
    }

    @Test
    fun `proto defaults decode as personal and fail closed`() {
        val decoded = baseSecret().toDataWithAccess()

        assertNull(decoded.orgId)
        assertNull(decoded.orgSlug)
        assertFalse(decoded.isOrgOwned)
        assertFalse(decoded.canManage)
    }

    @Test
    fun `share decoder preserves organisation target and nullable legacy fields`() {
        val decoded =
            SecretShareProto
                .newBuilder()
                .setShareId("share-1")
                .setAccessLevel("read")
                .setSharedByEmail("owner@example.com")
                .setCreatedAt("now")
                .setSharedWithOrgId("org-1")
                .setSharedWithOrgSlug("acme")
                .build()
                .toDataWithTarget()

        assertEquals("org-1", decoded.sharedWithOrgId)
        assertEquals("acme", decoded.sharedWithOrgSlug)
        assertNull(decoded.share.sharedWithUserId)
        assertNull(decoded.share.sharedWithRoleId)
    }

    @Test
    fun `sharing-row decoder preserves organisation ownership`() {
        val decoded =
            baseSecret()
                .toBuilder()
                .setIsOwner(false)
                .setSharedByEmail("owner@example.com")
                .setAccessLevel("read")
                .setOrgId("org-1")
                .setOrgSlug("acme")
                .setIsOrgOwned(true)
                .setCanManage(false)
                .build()
                .toDataWithSharingAccess()

        assertEquals("read", decoded.secret.accessLevel)
        assertEquals("org-1", decoded.orgId)
        assertEquals("acme", decoded.orgSlug)
        assertTrue(decoded.isOrgOwned)
        assertFalse(decoded.canManage)
    }

    private fun baseSecret(): SecretEntryProto =
        SecretEntryProto
            .newBuilder()
            .setId("s1")
            .setWebsite("github.com")
            .setUsername("octocat")
            .setPassword("hunter2")
            .setCreatedAt("then")
            .setUpdatedAt("now")
            .build()
}
