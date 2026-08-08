package ai.rever.boss.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class RisaLlmTokenCommandTest {
    @Test
    fun extractsSafeGatewayError() {
        val message =
            RisaLlmTokenCommand.parseGatewayError(
                """{"error":{"message":"RISA LLM access has been disabled for this account"}}""",
            )

        assertEquals("RISA LLM access has been disabled for this account", message)
    }

    @Test
    fun doesNotEchoUnknownProviderPayload() {
        val message = RisaLlmTokenCommand.parseGatewayError("upstream secret-like failure")

        assertEquals("RISA LLM gateway rejected the token request.", message)
    }
}
