package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretSummary
import ai.rever.boss.services.supabase.models.SecretValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wire shapes of `list_secrets_v2` / `get_secret_values` (20261111190000). */
class SecretServiceV2Test {
    @Test
    fun `listV2 sends kinds only when given`() {
        val all = SecretValueService.listV2Params(50, 0, null)
        assertFalse("p_kinds" in all)
        assertEquals(JsonPrimitive(50), all["p_limit"])
        val cards = SecretValueService.listV2Params(10, 20, listOf("card"))
        assertEquals(JsonArray(listOf(JsonPrimitive("card"))), cards["p_kinds"])
        assertEquals(JsonPrimitive(20), cards["p_offset"])
    }

    @Test
    fun `getValues validates purpose and id count before the network`() {
        assertTrue(SecretValueService.getValuesParams(listOf("a"), "").isFailure)
        assertTrue(SecretValueService.getValuesParams(listOf("a"), "Has Spaces").isFailure)
        assertTrue(SecretValueService.getValuesParams((1..101).map { "id$it" }, "mcp").isFailure)
        val ok = SecretValueService.getValuesParams(listOf("a", "a", "b"), "autofill").getOrThrow()
        assertEquals(listOf("a", "b"), ok["p_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("autofill", ok["p_purpose"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a list_secrets_v2 row decodes, unknown columns tolerated`() {
        val json =
            """
            [{"id":"6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5","website":"bank.test","username":"Visa 4242",
              "has_notes":true,"expiration_date":null,"tags":["kind:card","fluck-ws:w1"],
              "twofa_enabled":false,"twofa_type":null,
              "created_at":"2026-11-11T00:00:00+00:00","updated_at":"2026-11-11T00:00:00+00:00",
              "is_owner":true,"shared_by_email":null,"access_level":"owner","org_id":null,"org_slug":null,
              "is_org_owned":false,"shared_with_org_slug":null,"can_manage":true,"future_column":1}]
            """.trimIndent()
        val rows = supabaseJson.decodeFromJsonElement<List<SecretSummary>>(supabaseJson.parseToJsonElement(json))
        val row = rows.single()
        assertEquals(listOf("kind:card", "fluck-ws:w1"), row.tags)
        assertTrue(row.hasNotes)
        assertEquals(true, row.canManage)
    }

    @Test
    fun `a get_secret_values row decodes and redacts its string form`() {
        val json =
            """
            [{"id":"x","password":"4242424242424242","notes":"{\"name\":\"Ada\"}",
              "twofa_secret":null,"recovery_codes":["one","two"]}]
            """.trimIndent()
        val row = supabaseJson.decodeFromJsonElement<List<SecretValues>>(supabaseJson.parseToJsonElement(json)).single()
        assertEquals("4242424242424242", row.password)
        assertEquals(listOf("one", "two"), row.recoveryCodes)
        assertNull(row.twofaSecret)
        assertFalse(row.toString().contains("4242"))
        assertFalse(row.toString().contains("Ada"))
    }
}
