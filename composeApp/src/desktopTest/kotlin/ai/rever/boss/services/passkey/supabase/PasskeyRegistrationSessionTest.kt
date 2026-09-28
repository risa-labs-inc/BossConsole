package ai.rever.boss.services.passkey.supabase

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins serialization of the caller-created browser session into the registration challenge
 * request. The Deno mobile and route suites pin server-side rejection of unbound or mismatched
 * sessions; this test does not exercise the desktop browser URL because that path depends on
 * singleton browser infrastructure.
 */
class PasskeyRegistrationSessionTest {
    @Test
    fun `registration request serializes the caller-created browser session`() {
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
