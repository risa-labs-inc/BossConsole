package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.services.supabase.models.SecretShareEntry
import ai.rever.boss.utils.logging.BossLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupabaseDecodingRecoveryTest {
    private val logger = BossLogger.forComponent("SupabaseDecodingRecoveryTest")

    @Test
    fun `decodeListRecovering drops bad row but keeps good rows`() {
        val payload =
            """
            [
                {
                    "id": "1",
                    "website": "github.com",
                    "username": "user",
                    "password": "pwd",
                    "created_at": "now",
                    "updated_at": "now"
                },
                {
                    "id": "2",
                    "website": "gitlab.com",
                    "username": "user",
                    "password": null,
                    "created_at": "now",
                    "updated_at": "now"
                },
                {
                    "id": "3",
                    "website": "bitbucket.org",
                    "username": "user",
                    "password": "pwd",
                    "created_at": "now",
                    "updated_at": "now"
                }
            ]
            """.trimIndent()

        val jsonElement = Json.parseToJsonElement(payload)

        // Row 2 has null password where string is expected, so it should fail decoding
        val results = decodeListRecovering<SecretEntry>(jsonElement, logger, "testOperation")

        assertEquals(2, results.size, "Should recover and parse 2 good rows")
        assertEquals("1", results[0].id)
        assertEquals("3", results[1].id)
    }

    @Test
    fun `share recovery drops null access level while retaining valid shares`() {
        val payload =
            Json.parseToJsonElement(
                """[
                    {"share_id":"readable","access_level":"read","created_at":"now"},
                    {"share_id":"malformed","access_level":null,"created_at":"now"},
                    {"share_id":"writable","access_level":"write","created_at":"now"}
                ]""",
            )
        val shares = decodeListRecovering<SecretShareEntry>(payload, logger, "getSecretShares")
        assertEquals(listOf("readable", "writable"), shares.map { it.shareId })
        assertEquals(listOf("read", "write"), shares.map { it.accessLevel })
    }

    @Test
    fun `coerceInputValues allows nulls for properties with default values`() {
        // With coerceInputValues = true, a null for a non-nullable property with a default value
        // will be coerced to the default value instead of throwing an exception.
        val payload =
            """
            {
                "id": "1",
                "website": "github.com",
                "username": "user",
                "password": "pwd",
                "tags": null,
                "created_at": "now",
                "updated_at": "now"
            }
            """.trimIndent()

        val jsonElement = Json.parseToJsonElement(payload)

        val result =
            runCatching {
                supabaseJson.decodeFromJsonElement(SecretEntry.serializer(), jsonElement)
            }.getOrNull()

        assertNotNull(result, "Should successfully parse SecretEntry")
        assertTrue(result.tags.isEmpty(), "Null tags should be coerced to emptyList()")
    }

    @Test
    fun `non-array response fails without including its contents`() {
        val error =
            assertFailsWith<SupabaseFailure> {
                decodeListRecovering<SecretEntry>(Json.parseToJsonElement("\"private-password\""), logger, "list")
            }
        assertEquals("list: expected response array", error.message)
    }

    @Test
    fun `empty array succeeds but all malformed rows fail`() {
        assertTrue(decodeListRecovering<SecretEntry>(Json.parseToJsonElement("[]"), logger, "list").isEmpty())
        val error =
            assertFailsWith<SupabaseFailure> {
                decodeListRecovering<SecretEntry>(Json.parseToJsonElement("[null,42,{}]"), logger, "list")
            }
        assertEquals("list: no decodable rows", error.message)
    }

    @Test
    fun `coercion does not invent a required primary key`() {
        assertFailsWith<SerializationException> {
            supabaseJson.decodeFromJsonElement(
                SecretEntry.serializer(),
                Json.parseToJsonElement(
                    """{"website":"w","username":"u","password":"p","created_at":"x","updated_at":"x"}""",
                ),
            )
        }
    }
}
