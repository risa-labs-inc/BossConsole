package ai.rever.boss.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what the settings screen actually hands the orchestrator.
 *
 * This map is the egress decision made concrete: an empty one means no source can leave the
 * machine, because the orchestrator's own gate needs all of `BOSS_AI_REPAIR`,
 * `BOSS_REPAIR_PROJECT_ROOT` and a key before it will construct a client. Most of what is asserted
 * here is therefore the *absence* of variables when the operator's configuration is incomplete.
 */
class SelfHealingEnvironmentTest {
    private fun settings(
        enabled: Boolean = true,
        provider: SelfHealingProvider = SelfHealingProvider.ANTHROPIC,
        model: String = "",
        endpoint: String = "",
        systemPrompt: String = "",
        projectRoot: String = "/work/boss",
    ) = SelfHealingSettingsData(
        aiRepairEnabled = enabled,
        provider = provider.name,
        model = model,
        endpoint = endpoint,
        systemPrompt = systemPrompt,
        projectRoot = projectRoot,
    )

    @Test
    fun `switched off sends nothing at all`() {
        assertEquals(emptyMap(), repairEnvironment(settings(enabled = false), apiKey = "sk-live"))
    }

    @Test
    fun `switched off does not leak the key into the child process`() {
        // The orchestrator would ignore a key without the opt-in, but a credential handed to a
        // process that has no use for it is still a credential in one more place.
        val env = repairEnvironment(settings(enabled = false), apiKey = "sk-live")

        assertFalse(env.values.any { it.contains("sk-live") })
    }

    @Test
    fun `no source root means no AI repair`() {
        assertEquals(emptyMap(), repairEnvironment(settings(projectRoot = ""), apiKey = "sk-live"))
    }

    @Test
    fun `a whitespace root is not a root`() {
        assertEquals(emptyMap(), repairEnvironment(settings(projectRoot = "   "), apiKey = "sk-live"))
    }

    @Test
    fun `no key means nothing is sent`() {
        assertEquals(emptyMap(), repairEnvironment(settings(), apiKey = null))
        assertEquals(emptyMap(), repairEnvironment(settings(), apiKey = "  "))
    }

    @Test
    fun `a custom provider with no endpoint of its own is incomplete`() {
        // CUSTOM has no default URL to fall back on, so an unset endpoint is not a configuration.
        val env = repairEnvironment(settings(provider = SelfHealingProvider.CUSTOM, model = "m"), apiKey = "sk-live")

        assertEquals(emptyMap(), env)
    }

    @Test
    fun `a custom provider with no model of its own is incomplete`() {
        val env =
            repairEnvironment(
                settings(provider = SelfHealingProvider.CUSTOM, endpoint = "https://gw/v1/chat/completions"),
                apiKey = "sk-live",
            )

        assertEquals(emptyMap(), env)
    }

    @Test
    fun `a complete configuration names provider, endpoint, model, root and key`() {
        val env = repairEnvironment(settings(), apiKey = "sk-live")

        assertEquals("true", env["BOSS_AI_REPAIR"])
        assertEquals("/work/boss", env["BOSS_REPAIR_PROJECT_ROOT"])
        assertEquals("anthropic", env["AI_REPAIR_PROVIDER"])
        assertEquals("https://api.anthropic.com/v1/messages", env["AI_REPAIR_API_URL"])
        assertEquals("claude-opus-5", env["AI_REPAIR_MODEL"])
        assertEquals("sk-live", env["AI_REPAIR_API_KEY"])
    }

    @Test
    fun `an unset system prompt is omitted so the orchestrator's default applies`() {
        assertFalse(repairEnvironment(settings(), apiKey = "sk-live").containsKey("AI_REPAIR_SYSTEM_PROMPT"))
    }

    @Test
    fun `a written system prompt is passed through verbatim, newlines and all`() {
        val prompt = "Respond with JSON only.\n\nPrefer the smallest possible diff."

        val env = repairEnvironment(settings(systemPrompt = prompt), apiKey = "sk-live")

        assertEquals(prompt, env["AI_REPAIR_SYSTEM_PROMPT"])
    }

    @Test
    fun `blank model and endpoint resolve to the chosen provider's defaults`() {
        val env = repairEnvironment(settings(provider = SelfHealingProvider.TOGETHER), apiKey = "sk-live")

        assertEquals("openai", env["AI_REPAIR_PROVIDER"])
        assertEquals("https://api.together.xyz/v1/chat/completions", env["AI_REPAIR_API_URL"])
        assertEquals("meta-llama/Llama-3.3-70B-Instruct-Turbo", env["AI_REPAIR_MODEL"])
    }

    @Test
    fun `the operator's model and endpoint win over the defaults`() {
        val env =
            repairEnvironment(
                settings(
                    provider = SelfHealingProvider.CUSTOM,
                    model = "internal-repair-v2",
                    endpoint = "https://gateway.internal/v1/chat/completions",
                ),
                apiKey = "sk-live",
            )

        assertEquals("internal-repair-v2", env["AI_REPAIR_MODEL"])
        assertEquals("https://gateway.internal/v1/chat/completions", env["AI_REPAIR_API_URL"])
    }

    @Test
    fun `a provider name this build does not know falls back to Anthropic rather than misrouting`() {
        // Downgrade, or a settings file written by a newer version. Sending a key to whatever URL
        // happened to be stored would be worse than using the house default.
        val stored = SelfHealingSettingsData(aiRepairEnabled = true, provider = "GEMINI", projectRoot = "/work/boss")

        val env = repairEnvironment(stored, apiKey = "sk-live")

        assertEquals("anthropic", env["AI_REPAIR_PROVIDER"])
        assertEquals("https://api.anthropic.com/v1/messages", env["AI_REPAIR_API_URL"])
    }

    @Test
    fun `AI repair is off in a fresh install`() {
        val fresh = SelfHealingSettingsData()

        assertFalse(fresh.aiRepairEnabled)
        assertTrue(repairEnvironment(fresh, apiKey = "sk-live").isEmpty())
    }
}
