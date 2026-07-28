package ai.rever.boss.orchestrator

/**
 * The HTTP shape a configured endpoint speaks.
 *
 * Two are enough to cover the providers BOSS offers: Anthropic's Messages API, and the
 * OpenAI chat-completions shape that OpenAI, Together and most self-hosted gateways implement.
 */
enum class AiRepairWire(
    val defaultEndpoint: String,
    val defaultModel: String,
    /**
     * Output ceiling for one repair proposal.
     *
     * Higher on Anthropic because `max_tokens` there bounds thinking *and* answer text, and
     * thinking is on by default on current models — sizing it around the JSON alone truncates
     * the proposal mid-object.
     */
    val maxTokens: Int,
) {
    ANTHROPIC(
        defaultEndpoint = "https://api.anthropic.com/v1/messages",
        defaultModel = "claude-opus-5",
        maxTokens = 8192,
    ),
    OPENAI(
        defaultEndpoint = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-5.4",
        maxTokens = 2048,
    ),
}

/**
 * What the repair client sends, and where.
 *
 * Everything here is operator-chosen (Settings → Advanced → Self-Healing, forwarded as environment
 * variables when the kernel spawns this process). It is a plain value so the choice can be read and
 * asserted without standing up an HTTP client.
 */
data class AiRepairConfig(
    val wire: AiRepairWire,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
)

/**
 * The instruction the model gets when nobody has written one.
 *
 * Both prompts the client builds ask for a bare JSON object, so the default reinforces that and
 * nothing else — an operator replacing it is free to add house rules, but a replacement that drops
 * the JSON requirement will produce proposals the parser rejects.
 */
const val DEFAULT_REPAIR_SYSTEM_PROMPT: String =
    "You are a precise code repair assistant. Always respond with valid JSON only. " +
        "Do not include markdown code fences."

/**
 * Resolve a repair configuration from whatever the operator supplied.
 *
 * Blank is treated as unset throughout: an empty environment variable is what a settings field the
 * operator cleared actually looks like by the time it reaches this process, and it should mean "use
 * the default", not "send an empty model name".
 *
 * [provider] is matched leniently — only "anthropic" selects the Messages API shape, because every
 * other provider BOSS offers (OpenAI, Together, custom gateways) speaks chat-completions.
 */
fun aiRepairConfig(
    provider: String?,
    endpoint: String?,
    apiKey: String?,
    model: String?,
    systemPrompt: String?,
): AiRepairConfig {
    val wire =
        if (provider?.trim()?.equals("anthropic", ignoreCase = true) == true) {
            AiRepairWire.ANTHROPIC
        } else {
            AiRepairWire.OPENAI
        }
    return AiRepairConfig(
        wire = wire,
        endpoint = endpoint?.trim()?.ifBlank { null } ?: wire.defaultEndpoint,
        apiKey = apiKey?.trim().orEmpty(),
        model = model?.trim()?.ifBlank { null } ?: wire.defaultModel,
        systemPrompt = systemPrompt?.ifBlank { null } ?: DEFAULT_REPAIR_SYSTEM_PROMPT,
    )
}

/**
 * The same resolution, reading the variables the kernel sets when it spawns this process.
 *
 * [getenv] is injectable so the mapping can be tested without mutating the real environment.
 * `AI_REPAIR_API_KEY` falls back to `OPENAI_API_KEY` for operators who configured this before there
 * was a settings screen.
 */
fun aiRepairConfigFromEnvironment(getenv: (String) -> String? = { System.getenv(it) }): AiRepairConfig =
    aiRepairConfig(
        provider = getenv("AI_REPAIR_PROVIDER"),
        endpoint = getenv("AI_REPAIR_API_URL"),
        apiKey = getenv("AI_REPAIR_API_KEY") ?: getenv("OPENAI_API_KEY"),
        model = getenv("AI_REPAIR_MODEL"),
        systemPrompt = getenv("AI_REPAIR_SYSTEM_PROMPT"),
    )
