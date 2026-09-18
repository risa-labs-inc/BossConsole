package ai.rever.boss.services.passkey.supabase

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PasskeyRegistrationSessionTest {
    @Test
    fun `registration challenge request carries the browser session to the server`() {
        val request =
            PasskeyDataMapper.createRegistrationRequest(
                userId = "user-1",
                displayName = "User",
                challenge = "challenge-1",
                authenticatorSelection = null,
                sessionId = "session-created-before-challenge",
            )

        val encoded = Json.encodeToString(request)
        val decoded = Json.decodeFromString<PasskeyRegistrationRequest>(encoded)

        assertEquals("session-created-before-challenge", decoded.sessionId)
    }
}
