package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretEntry
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupabaseRowRecoveryTest {
    @Test
    fun `one malformed row does not discard valid siblings`() {
        val body =
            """
            [
                {"id":"1","website":"one","username":"u","password":"p","tags":[],"created_at":"x","updated_at":"x"},
                {"id":"2","website":"broken","username":"u","password":null,"tags":[],"created_at":"x","updated_at":"x"},
                {"id":"3","website":"three","username":"u","password":"p","tags":[],"created_at":"x","updated_at":"x"}
            ]
            """.trimIndent()

        val decoded = decodeSupabaseRows<SecretEntry>(supabaseJson.parseToJsonElement(body))

        assertEquals(listOf("1", "3"), decoded.values.map { it.id })
        assertEquals(1, decoded.droppedCount)
        assertEquals(3, decoded.receivedCount)
    }

    @Test
    fun `a malformed array remains a response failure`() {
        val body = """[{"id":"1"}"""

        val error =
            runCatching { decodeSupabaseRows<SecretEntry>(supabaseJson.parseToJsonElement(body)) }
                .exceptionOrNull()

        assertTrue(error is SerializationException)
    }
}
