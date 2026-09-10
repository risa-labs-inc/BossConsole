package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import ai.rever.boss.services.supabase.models.SecretShareEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The secret lists must survive columns this build does not model.
 *
 * The organisation migration extended FOUR list-returning RPCs at once, and against the
 * strict kotlinx default every one of them throws "Encountered an unknown key 'org_id'".
 * Because they return lists the throw is all-or-nothing: not a missing field, an empty
 * panel, with only a WARN in the log to say why.
 *
 * Each case below carries the top-level keys from the latest `RETURNS TABLE`: secret
 * listings in `20260907000000_secrets_paging_tiebreaker.sql`, and shares in
 * `20260802000000_secrets_org_ownership.sql`, rather than a representative unknown key.
 * Two of these paths (`searchSecrets`, `getSecretShares`) were
 * broken in production without anyone reporting them, so "which shape did the server
 * actually send" is the thing worth pinning.
 *
 * Decoding goes through [supabaseJson] deliberately. A lenient `Json` built here would
 * assert that kotlinx honours `ignoreUnknownKeys`, which was never in doubt; what needs
 * pinning is that the services are wired to it and stay so.
 */
class SecretDecodingTest {
    /** The ten columns every secret RPC returned before the organisation work. */
    private fun JsonObjectBuilder.baseSecret(id: String) {
        put("id", id)
        put("website", "github.com")
        put("username", "someone@example.com")
        put("password", "hunter2")
        put("notes", null as String?)
        put("expiration_date", null as String?)
        put("tags", buildJsonArray { })
        put(
            "metadata",
            // The RPC emits a jsonb object unconditionally via COALESCE, so a bare null is
            // not a shape the server can actually send - and decoding it exercises nothing
            // of SecretMetadata, which otherwise has zero nested coverage here.
            buildJsonObject {
                put("twofa_enabled", true)
                put("twofa_type", "app")
                put("recovery_codes", buildJsonArray { add(JsonPrimitive("abc-123")) })
            },
        )
        put("created_at", "2026-08-01T00:00:00Z")
        put("updated_at", "2026-08-01T00:00:00Z")
    }

    /** The four keys `get_user_secrets` and `search_user_secrets` both gained. */
    private fun JsonObjectBuilder.orgColumns(
        orgId: String?,
        canManage: Boolean = true,
    ) {
        put("org_id", orgId)
        put("org_slug", orgId?.let { "acme" })
        put("is_org_owned", orgId != null)
        put("can_manage", canManage)
    }

    private fun userSecretsRow(
        id: String,
        orgId: String?,
    ): JsonObject =
        buildJsonObject {
            baseSecret(id)
            orgColumns(orgId)
        }

    @Test
    fun `absent and explicit null organisation permissions stay unknown`() {
        for (includeNulls in listOf(false, true)) {
            val payload =
                buildJsonObject {
                    baseSecret("secret-id")
                    put("is_owner", false)
                    put("access_level", "read")
                    if (includeNulls) {
                        put("can_manage", null as Boolean?)
                        put("is_org_owned", null as Boolean?)
                    }
                }
            val plain = supabaseJson.decodeFromJsonElement<SecretEntry>(payload)
            val shared = supabaseJson.decodeFromJsonElement<SecretEntryWithSharing>(payload)
            assertNull(plain.canManage)
            assertNull(plain.isOrgOwned)
            assertNull(shared.canManage)
            assertNull(shared.isOrgOwned)
            assertNull(shared.toSecretEntry().canManage)
            // canManageOrDeny is the fail-closed reading either an absent column (an
            // older/newer server) or an explicit null resolves to - "unknown" must never
            // grant the edit affordance a caller gates on this.
            assertEquals(false, plain.canManageOrDeny)
            assertEquals(false, shared.canManageOrDeny)
        }
    }

    @Test
    fun `get_user_secrets decodes with its four new organisation columns`() {
        val payload =
            buildJsonArray {
                add(userSecretsRow("11111111-1111-1111-1111-111111111111", null))
                add(userSecretsRow("22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333"))
            }

        val secrets = supabaseJson.decodeFromJsonElement<List<SecretEntry>>(payload)

        // The count is what matters. kotlinx does not decode partially - it is every row or
        // an exception - so "2" is what separates a working list from the empty panel.
        assertEquals(2, secrets.size)
        assertEquals("github.com", secrets[0].website)
        assertEquals("hunter2", secrets[1].password)
        assertEquals(true, secrets[0].metadata?.twofaEnabled)
        assertEquals(listOf("abc-123"), secrets[0].metadata?.recoveryCodes)

        // BossConsole#146: these four columns used to decode and vanish - SecretEntry had no
        // field to receive them. A personal secret (no org) and an org-owned one, so both the
        // null and the populated shape are pinned, not just "it didn't throw".
        assertNull(secrets[0].orgId)
        assertEquals(false, secrets[0].isOrgOwned)
        assertEquals("33333333-3333-3333-3333-333333333333", secrets[1].orgId)
        assertEquals("acme", secrets[1].orgSlug)
        assertEquals(true, secrets[1].isOrgOwned)
        assertEquals(true, secrets[1].canManage)
        // These owned-secret rows send can_manage=true regardless of org ownership - canManageOrDeny
        // agrees with the raw column on this row shape; the null/absent case is covered by
        // `absent and explicit null organisation permissions stay unknown`.
        assertEquals(true, secrets[0].canManageOrDeny)
        assertEquals(true, secrets[1].canManageOrDeny)
    }

    @Test
    fun `search_user_secrets decodes with the same four columns`() {
        // Broken in production exactly as getUserSecrets was, and never reported - search is
        // simply used less. It is not covered by the getUserSecrets case: same shape today,
        // but a separate function that can drift independently.
        val payload = buildJsonArray { add(userSecretsRow("44444444-4444-4444-4444-444444444444", null)) }

        val secrets = supabaseJson.decodeFromJsonElement<List<SecretEntry>>(payload)

        assertEquals(1, secrets.size)
    }

    @Test
    fun `get_user_secrets_with_shared decodes with all five of its new columns`() {
        val payload =
            buildJsonArray {
                add(
                    buildJsonObject {
                        baseSecret("55555555-5555-5555-5555-555555555555")
                        put("is_owner", false)
                        put("shared_by_email", "owner@example.com")
                        put("access_level", "read")
                        // An ordinary member of the target org can read but cannot manage.
                        orgColumns("66666666-6666-6666-6666-666666666666", canManage = false)
                        put("shared_with_org_slug", "partner-org")
                    },
                )
            }

        val secrets = supabaseJson.decodeFromJsonElement<List<SecretEntryWithSharing>>(payload)

        assertEquals(1, secrets.size)
        assertEquals("read", secrets[0].accessLevel)
        assertFalse(secrets[0].isOwner)
        // BossConsole#146: five more columns that used to decode and vanish.
        assertEquals("66666666-6666-6666-6666-666666666666", secrets[0].orgId)
        assertEquals("acme", secrets[0].orgSlug)
        assertEquals(true, secrets[0].isOrgOwned)
        assertEquals("partner-org", secrets[0].sharedWithOrgSlug)
        assertEquals(false, secrets[0].canManage)
        assertFalse(secrets[0].canManageOrDeny)
        val plain = secrets[0].toSecretEntry()
        assertEquals(false, plain.canManage)
        assertFalse(plain.canManageOrDeny)
    }

    @Test
    fun `get_user_secrets_with_shared decodes access_level org - the case #146 is about`() {
        // The fourth UNION branch the organisation migration added: a secret owned by an org
        // the caller belongs to, where is_owner is (creator == caller) and can genuinely be
        // false for an org admin who didn't create it. Before this model change, "org" decoded
        // fine (it is just a string) but had nothing to distinguish it from "owner"/"read"/
        // "write" once it reached anything that branched on the value - which is the actual bug
        // #146 reports, not a decoding failure.
        val payload =
            buildJsonArray {
                add(
                    buildJsonObject {
                        baseSecret("cccccccc-cccc-cccc-cccc-cccccccccccc")
                        put("is_owner", false)
                        put("shared_by_email", null as String?)
                        put("access_level", "org")
                        orgColumns("dddddddd-dddd-dddd-dddd-dddddddddddd")
                        put("shared_with_org_slug", "acme")
                    },
                )
            }

        val secrets = supabaseJson.decodeFromJsonElement<List<SecretEntryWithSharing>>(payload)

        assertEquals(1, secrets.size)
        val orgSecret = secrets[0]
        assertEquals("org", orgSecret.accessLevel)
        assertFalse(orgSecret.isOwner)
        // The point of #146: an org admin who did not create this secret is not its "owner",
        // but IS allowed to manage it - can_manage is what a UI must gate edit/delete on.
        assertEquals(true, orgSecret.canManage)
        assertEquals(true, orgSecret.isOrgOwned)
        assertEquals("acme", orgSecret.sharedWithOrgSlug)
        val plain = orgSecret.toSecretEntry()
        assertEquals(orgSecret.orgId, plain.orgId)
        assertEquals(orgSecret.orgSlug, plain.orgSlug)
        assertEquals(orgSecret.isOrgOwned, plain.isOrgOwned)
        assertEquals(orgSecret.canManage, plain.canManage)
    }

    @Test
    fun `get_secret_shares decodes with shared_with_org_id and shared_with_org_slug`() {
        // The third broken RPC, and the one the first pass of this test missed. Its failure
        // showed as "no shares" on every secret, which reads like a state rather than a fault.
        val payload =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("share_id", "77777777-7777-7777-7777-777777777777")
                        put("shared_with_user_id", null as String?)
                        put("shared_with_user_email", null as String?)
                        put("shared_with_role_id", null as String?)
                        put("shared_with_role_name", null as String?)
                        put("access_level", "write")
                        put("shared_by_email", "owner@example.com")
                        put("created_at", "2026-08-01T00:00:00Z")
                        put("expires_at", null as String?)
                        put("notes", null as String?)
                        put("shared_with_org_id", "88888888-8888-8888-8888-888888888888")
                        put("shared_with_org_slug", "acme")
                    },
                )
            }

        val shares = supabaseJson.decodeFromJsonElement<List<SecretShareEntry>>(payload)

        assertEquals(1, shares.size)
        assertEquals("write", shares[0].accessLevel)
        // BossConsole#146: the two columns this test's own name promises, previously decoded
        // and discarded - ignoreUnknownKeys hid the gap rather than a missing-field exception,
        // which is why this went unnoticed until someone read the model against the RPC.
        assertEquals("88888888-8888-8888-8888-888888888888", shares[0].sharedWithOrgId)
        assertEquals("acme", shares[0].sharedWithOrgSlug)
    }

    @Test
    fun `get_secret_shares decodes when shared_by_email is null`() {
        // shared_by_email comes from `LEFT JOIN auth.users sb ON sb.id = ss.shared_by`, and
        // auth.users.email is nullable. Preserve unknown identity as null: unknown-key
        // leniency cannot fix it, and coercion to an invented default would lose that meaning.
        val payload =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("share_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                        put("access_level", "read")
                        put("shared_by_email", null as String?)
                        put("created_at", "2026-08-01T00:00:00Z")
                        put("shared_with_org_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                        put("shared_with_org_slug", "acme")
                    },
                )
            }

        val shares = supabaseJson.decodeFromJsonElement<List<SecretShareEntry>>(payload)

        assertEquals(1, shares.size)
        // assertNull also catches a non-null default: coercion must preserve unknown identity.
        assertNull(shares[0].sharedByEmail)
    }

    @Test
    fun `a column nobody has invented yet is ignored rather than fatal`() {
        // The point is not org_id specifically. Any future additive migration - shipped, as
        // always, ahead of the installed build - has to degrade to "ignored", or this outage
        // repeats under a different column name.
        val payload =
            buildJsonArray {
                add(
                    buildJsonObject {
                        baseSecret("99999999-9999-9999-9999-999999999999")
                        put("some_future_column", "whatever")
                        put("another_one", 42)
                    },
                )
            }

        val secrets = supabaseJson.decodeFromJsonElement<List<SecretEntry>>(payload)

        assertEquals(1, secrets.size)
    }

    @Test
    fun `decodeFromString is lenient too, not just decodeFromJsonElement`() {
        // Every call site parses then decodes, so leniency proven on one overload says
        // nothing about the other. Both are in use across the package.
        val raw = buildJsonArray { add(userSecretsRow("10101010-1010-1010-1010-101010101010", null)) }.toString()

        val secrets = supabaseJson.decodeFromString<List<SecretEntry>>(raw)

        assertEquals(1, secrets.size)
    }
}
