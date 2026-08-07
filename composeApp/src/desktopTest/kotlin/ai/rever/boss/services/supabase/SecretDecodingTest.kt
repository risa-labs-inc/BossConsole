package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import ai.rever.boss.services.supabase.models.SecretShareEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The secret lists must survive columns this build does not model.
 *
 * The organisation migration extended FOUR list-returning RPCs at once, and against the
 * strict kotlinx default every one of them throws "Encountered an unknown key 'org_id'".
 * Because they return lists the throw is all-or-nothing: not a missing field, an empty
 * panel, with only a WARN in the log to say why.
 *
 * Each case below carries the EXACT set of keys its RPC ships today, taken from the
 * `RETURNS TABLE` in `20260802000000_secrets_org_ownership.sql`, rather than a
 * representative unknown key. Two of these paths (`searchSecrets`, `getSecretShares`) were
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
        put("metadata", null as String?)
        put("created_at", "2026-08-01T00:00:00Z")
        put("updated_at", "2026-08-01T00:00:00Z")
    }

    /** The four keys `get_user_secrets` and `search_user_secrets` both gained. */
    private fun JsonObjectBuilder.orgColumns(orgId: String?) {
        put("org_id", orgId)
        put("org_slug", orgId?.let { "acme" })
        put("is_org_owned", orgId != null)
        put("can_manage", true)
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
                        orgColumns("66666666-6666-6666-6666-666666666666")
                        put("shared_with_org_slug", "acme")
                    },
                )
            }

        val secrets = supabaseJson.decodeFromJsonElement<List<SecretEntryWithSharing>>(payload)

        assertEquals(1, secrets.size)
        assertEquals("read", secrets[0].accessLevel)
        assertFalse(secrets[0].isOwner)
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
