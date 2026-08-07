package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The secret list must survive columns this build does not model.
 *
 * These payloads are the shape `get_user_secrets` and `get_user_secrets_with_shared`
 * actually returned once the organisation migration landed. Against the strict default
 * `Json` every one of them throws
 * "Encountered an unknown key 'org_id'", and because the RPCs return a LIST the throw
 * takes the whole page with it: the panel showed no secrets at all, with nothing but a
 * WARN in the log to say why.
 *
 * Decoding goes through [SecretService.json] deliberately. Building a lenient `Json`
 * here would assert that kotlinx honours `ignoreUnknownKeys`, which was never in doubt.
 * What needs pinning is that THIS service is configured that way and stays so.
 */
class SecretDecodingTest {
    private val row = """
        {
          "id": "11111111-1111-1111-1111-111111111111",
          "website": "github.com",
          "username": "someone@example.com",
          "password": "hunter2",
          "notes": null,
          "expiration_date": null,
          "tags": ["work"],
          "metadata": null,
          "created_at": "2026-08-01T00:00:00Z",
          "updated_at": "2026-08-01T00:00:00Z"
        }
    """

    private fun withKeys(vararg extra: Pair<String, String>): String {
        val added = extra.joinToString("") { (k, v) -> ""","$k": $v""" }
        return row.trimIndent().removeSuffix("}").trimEnd() + added + "}"
    }

    @Test
    fun `secret list decodes when rows carry org_id`() {
        val payload = "[${withKeys("org_id" to "null")},${withKeys("org_id" to "\"22222222-2222-2222-2222-222222222222\"")}]"

        val secrets = SecretService.json.decodeFromString<List<SecretEntry>>(payload)

        // The count is the assertion that matters. A partial decode is not a thing kotlinx
        // does - it is all rows or an exception - so "2" is what distinguishes a working
        // list from the empty panel this bug produced.
        assertEquals(2, secrets.size)
        assertEquals("github.com", secrets[0].website)
        assertEquals("hunter2", secrets[1].password)
    }

    @Test
    fun `shared secret list decodes when rows carry shared_with_org_id`() {
        val shared =
            withKeys(
                "is_owner" to "false",
                "access_level" to "\"read\"",
                "shared_by_email" to "\"owner@example.com\"",
                "shared_with_org_id" to "\"33333333-3333-3333-3333-333333333333\"",
                "org_id" to "null",
            )

        val secrets = SecretService.json.decodeFromString<List<SecretEntryWithSharing>>("[$shared]")

        assertEquals(1, secrets.size)
        assertEquals("read", secrets[0].accessLevel)
        assertTrue(!secrets[0].isOwner)
    }

    @Test
    fun `a column nobody has invented yet is ignored rather than fatal`() {
        // The point of the fix is not org_id specifically. Any future additive migration -
        // shipped, as always, ahead of the installed desktop build - has to degrade to
        // "ignored", or it repeats this outage under a different column name.
        val payload = "[${withKeys("some_future_column" to "\"whatever\"", "another_one" to "42")}]"

        val secrets = SecretService.json.decodeFromString<List<SecretEntry>>(payload)

        assertEquals(1, secrets.size)
    }

    @Test
    fun `decodeFromJsonElement is lenient too, not just decodeFromString`() {
        // Every call site in SecretService goes through parseToJsonElement then
        // decodeFromJsonElement, so leniency on the string overload alone would prove
        // nothing about the path production actually takes.
        val element = SecretService.json.parseToJsonElement("[${withKeys("org_id" to "null")}]")

        val secrets = SecretService.json.decodeFromJsonElement<List<SecretEntry>>(element)

        assertEquals(1, secrets.size)
    }
}
