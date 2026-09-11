package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import ai.rever.boss.services.supabase.models.SecretShareEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretDataProviderMappingTest {
    @Test
    fun `in-process mapping preserves organisation admin access`() {
        val mapped = model(canManage = true).toPluginAccessData(pluginSecret())

        assertEquals("org-1", mapped.orgId)
        assertEquals("acme", mapped.orgSlug)
        assertTrue(mapped.isOrgOwned)
        assertTrue(mapped.canManage)
    }

    @Test
    fun `in-process mapping fails closed when server decision is absent`() {
        val mapped = model(canManage = null, isOrgOwned = null).toPluginAccessData(pluginSecret())

        assertFalse(mapped.canManage)
        assertFalse(mapped.isOrgOwned)
    }

    @Test
    fun `in-process share mapping preserves organisation target`() {
        val model =
            SecretShareEntry(
                shareId = "share-1",
                accessLevel = "read",
                createdAt = "now",
                sharedWithOrgId = "org-1",
                sharedWithOrgSlug = "acme",
            )
        val mapped = model.toPluginTargetData(pluginShare())

        assertEquals("org-1", mapped.sharedWithOrgId)
        assertEquals("acme", mapped.sharedWithOrgSlug)
        assertNull(mapped.share.sharedWithUserId)
    }

    @Test
    fun `in-process sharing row preserves organisation ownership`() {
        val model =
            SecretEntryWithSharing(
                id = "s1",
                website = "github.com",
                username = "octocat",
                password = "hunter2",
                createdAt = "then",
                updatedAt = "now",
                isOwner = false,
                sharedByEmail = "owner@example.com",
                accessLevel = "read",
                orgId = "org-1",
                orgSlug = "acme",
                isOrgOwned = true,
                canManage = false,
            )
        val mapped =
            model.toPluginSharingAccessData(
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
                ),
            )

        assertEquals("org-1", mapped.orgId)
        assertEquals("acme", mapped.orgSlug)
        assertTrue(mapped.isOrgOwned)
        assertFalse(mapped.canManage)
    }

    private fun model(
        canManage: Boolean?,
        isOrgOwned: Boolean? = true,
    ) = SecretEntry(
        id = "s1",
        website = "github.com",
        username = "octocat",
        password = "hunter2",
        createdAt = "then",
        updatedAt = "now",
        orgId = "org-1",
        orgSlug = "acme",
        isOrgOwned = isOrgOwned,
        canManage = canManage,
    )

    private fun pluginSecret() =
        SecretEntryData(
            id = "s1",
            website = "github.com",
            username = "octocat",
            password = "hunter2",
            createdAt = "then",
            updatedAt = "now",
        )

    private fun pluginShare() =
        SecretShareData(
            shareId = "share-1",
            accessLevel = "read",
            sharedByEmail = "",
            createdAt = "now",
        )
}
