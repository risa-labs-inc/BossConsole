package ai.rever.boss.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how an operator's provider/model/prompt choice reaches the HTTP client.
 *
 * The settings screen forwards these as environment variables, and a field the operator cleared
 * arrives here as an empty string rather than as absent — so "blank means default" is the property
 * that keeps a cleared field from being sent as an empty model name.
 */
class AiRepairConfigTest {
    @Test
    fun `nothing configured is OpenAI, as it always was`() {
        // Prior behaviour: the client only ever spoke chat-completions to api.openai.com.
        val config = aiRepairConfig(provider = null, endpoint = null, apiKey = null, model = null, systemPrompt = null)

        assertEquals(AiRepairWire.OPENAI, config.wire)
        assertEquals("https://api.openai.com/v1/chat/completions", config.endpoint)
        assertEquals("gpt-5.4", config.model)
        assertEquals(DEFAULT_REPAIR_SYSTEM_PROMPT, config.systemPrompt)
    }

    @Test
    fun `anthropic selects the messages API and a Claude model`() {
        val config =
            aiRepairConfig(provider = "anthropic", endpoint = null, apiKey = "k", model = null, systemPrompt = null)

        assertEquals(AiRepairWire.ANTHROPIC, config.wire)
        assertEquals("https://api.anthropic.com/v1/messages", config.endpoint)
        assertEquals("claude-opus-5", config.model)
    }

    @Test
    fun `the provider name is matched leniently`() {
        listOf("Anthropic", "ANTHROPIC", " anthropic ").forEach { name ->
            assertEquals(
                AiRepairWire.ANTHROPIC,
                aiRepairConfig(name, null, null, null, null).wire,
                "provider \"$name\" should select the Anthropic wire",
            )
        }
    }

    @Test
    fun `every other provider speaks chat-completions`() {
        // Together and self-hosted gateways are OpenAI-compatible; only Anthropic differs.
        listOf("openai", "together", "custom", "", "something-new").forEach { name ->
            assertEquals(
                AiRepairWire.OPENAI,
                aiRepairConfig(name, null, null, null, null).wire,
                "provider \"$name\" should speak chat-completions",
            )
        }
    }

    @Test
    fun `a cleared field falls back to the default rather than being sent empty`() {
        val config = aiRepairConfig(provider = "openai", endpoint = "  ", apiKey = "k", model = "", systemPrompt = "")

        assertEquals("https://api.openai.com/v1/chat/completions", config.endpoint)
        assertEquals("gpt-5.4", config.model)
        assertEquals(DEFAULT_REPAIR_SYSTEM_PROMPT, config.systemPrompt)
    }

    @Test
    fun `an operator's choices are used as given`() {
        val config =
            aiRepairConfig(
                provider = "custom",
                endpoint = "https://gateway.internal/v1/chat/completions",
                apiKey = "sk-live",
                model = "internal-repair-v2",
                systemPrompt = "Reply with JSON. House style applies.",
            )

        assertEquals("https://gateway.internal/v1/chat/completions", config.endpoint)
        assertEquals("internal-repair-v2", config.model)
        assertEquals("Reply with JSON. House style applies.", config.systemPrompt)
        assertEquals("sk-live", config.apiKey)
    }

    @Test
    fun `a multi-line system prompt survives intact`() {
        // It travels as an environment variable; newlines are the whole point of the field.
        val prompt = "Line one.\nLine two.\n\nRespond with JSON only."

        assertEquals(prompt, aiRepairConfig(null, null, null, null, prompt).systemPrompt)
    }

    @Test
    fun `the environment mapping reads the variables the kernel sets`() {
        val env =
            mapOf(
                "AI_REPAIR_PROVIDER" to "anthropic",
                "AI_REPAIR_API_URL" to "https://proxy.internal/v1/messages",
                "AI_REPAIR_API_KEY" to "sk-repair",
                "AI_REPAIR_MODEL" to "claude-opus-5",
                "AI_REPAIR_SYSTEM_PROMPT" to "Be terse.",
            )

        val config = aiRepairConfigFromEnvironment { env[it] }

        assertEquals(AiRepairWire.ANTHROPIC, config.wire)
        assertEquals("https://proxy.internal/v1/messages", config.endpoint)
        assertEquals("sk-repair", config.apiKey)
        assertEquals("claude-opus-5", config.model)
        assertEquals("Be terse.", config.systemPrompt)
    }

    @Test
    fun `OPENAI_API_KEY still works for operators who configured this before the settings screen`() {
        val config = aiRepairConfigFromEnvironment { name -> "sk-legacy".takeIf { name == "OPENAI_API_KEY" } }

        assertEquals("sk-legacy", config.apiKey)
    }

    @Test
    fun `the legacy OpenAI key is not offered to Anthropic`() {
        // It would be sent as x-api-key: a credential going somewhere it cannot work and should not
        // go. AI_REPAIR_API_KEY is the way to configure the Anthropic wire.
        val env = mapOf("AI_REPAIR_PROVIDER" to "anthropic", "OPENAI_API_KEY" to "sk-openai")

        assertEquals("", aiRepairConfigFromEnvironment { env[it] }.apiKey)
    }

    @Test
    fun `a dedicated repair key wins over the general one`() {
        val env = mapOf("AI_REPAIR_API_KEY" to "sk-narrow", "OPENAI_API_KEY" to "sk-broad")

        assertEquals("sk-narrow", aiRepairConfigFromEnvironment { env[it] }.apiKey)
    }

    @Test
    fun `Anthropic gets room for thinking as well as an answer`() {
        // max_tokens bounds thinking plus text there, and thinking is on by default on current
        // models — sizing it around the JSON alone truncates the proposal.
        assertTrue(AiRepairWire.ANTHROPIC.maxTokens > AiRepairWire.OPENAI.maxTokens)
    }
}
