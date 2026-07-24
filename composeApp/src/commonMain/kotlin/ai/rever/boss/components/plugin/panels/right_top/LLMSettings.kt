package ai.rever.boss.components.plugin.panels.right_top

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM Provider types
 */
enum class LLMProvider(val displayName: String, val baseUrl: String) {
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1/messages"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions"),
    TOGETHER("Together AI", "https://api.together.xyz/v1/chat/completions"),
    CUSTOM("Custom Provider", "")
}

/**
 * LLM Model configurations
 */
enum class LLMModel(val modelId: String, val displayName: String) {
    // Anthropic Models (January 2025)
    CLAUDE_3_5_SONNET_V2("claude-3-5-sonnet-v2", "Claude 3.5 Sonnet v2", LLMProvider.ANTHROPIC),
    CLAUDE_3_5_SONNET_LATEST("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", LLMProvider.ANTHROPIC),
    CLAUDE_3_5_HAIKU_LATEST("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", LLMProvider.ANTHROPIC),
    CLAUDE_3_OPUS_LATEST("claude-3-opus-latest", "Claude 3 Opus (Latest)", LLMProvider.ANTHROPIC),
    CLAUDE_3_OPUS("claude-3-opus-20240229", "Claude 3 Opus", LLMProvider.ANTHROPIC),
    CLAUDE_3_SONNET("claude-3-sonnet-20240229", "Claude 3 Sonnet", LLMProvider.ANTHROPIC),
    CLAUDE_3_HAIKU("claude-3-haiku-20240307", "Claude 3 Haiku", LLMProvider.ANTHROPIC),
    
    // OpenAI Models (January 2025)
    GPT_4O_2024_11_20("gpt-4o-2024-11-20", "GPT-4o (Nov 2024)", LLMProvider.OPENAI),
    GPT_4O_2024_08_06("gpt-4o-2024-08-06", "GPT-4o (Aug 2024)", LLMProvider.OPENAI),
    GPT_4O("gpt-4o", "GPT-4o", LLMProvider.OPENAI),
    GPT_4O_MINI_2024_07_18("gpt-4o-mini-2024-07-18", "GPT-4o Mini (July 2024)", LLMProvider.OPENAI),
    GPT_4O_MINI("gpt-4o-mini", "GPT-4o Mini", LLMProvider.OPENAI),
    GPT_4_TURBO_2024_04_09("gpt-4-turbo-2024-04-09", "GPT-4 Turbo (April 2024)", LLMProvider.OPENAI),
    GPT_4_TURBO("gpt-4-turbo", "GPT-4 Turbo", LLMProvider.OPENAI),
    GPT_4_0125_PREVIEW("gpt-4-0125-preview", "GPT-4 Preview", LLMProvider.OPENAI),
    GPT_4("gpt-4", "GPT-4", LLMProvider.OPENAI),
    GPT_35_TURBO_0125("gpt-3.5-turbo-0125", "GPT-3.5 Turbo (Latest)", LLMProvider.OPENAI),
    GPT_35_TURBO("gpt-3.5-turbo", "GPT-3.5 Turbo", LLMProvider.OPENAI),
    O1_PREVIEW_2024_09_12("o1-preview-2024-09-12", "o1 Preview (Sept 2024)", LLMProvider.OPENAI),
    O1_PREVIEW("o1-preview", "o1 Preview", LLMProvider.OPENAI),
    O1_MINI_2024_09_12("o1-mini-2024-09-12", "o1 Mini (Sept 2024)", LLMProvider.OPENAI),
    O1_MINI("o1-mini", "o1 Mini", LLMProvider.OPENAI),
    
    // Together AI Models (January 2025)
    LLAMA_3_3_70B("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B (Latest)", LLMProvider.TOGETHER),
    LLAMA_3_2_90B_VISION("meta-llama/Llama-3.2-90B-Vision-Instruct-Turbo", "Llama 3.2 90B Vision", LLMProvider.TOGETHER),
    LLAMA_3_2_11B_VISION("meta-llama/Llama-3.2-11B-Vision-Instruct-Turbo", "Llama 3.2 11B Vision", LLMProvider.TOGETHER),
    LLAMA_3_2_3B("meta-llama/Llama-3.2-3B-Instruct-Turbo", "Llama 3.2 3B", LLMProvider.TOGETHER),
    LLAMA_3_1_405B("meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo", "Llama 3.1 405B", LLMProvider.TOGETHER),
    LLAMA_3_1_70B("meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo", "Llama 3.1 70B", LLMProvider.TOGETHER),
    LLAMA_3_1_8B("meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo", "Llama 3.1 8B", LLMProvider.TOGETHER),
    QWEN_2_5_CODER_32B("Qwen/Qwen2.5-Coder-32B-Instruct", "Qwen 2.5 Coder 32B", LLMProvider.TOGETHER),
    QWEN_2_5_72B("Qwen/Qwen2.5-72B-Instruct-Turbo", "Qwen 2.5 72B", LLMProvider.TOGETHER),
    QWEN_2_5_7B("Qwen/Qwen2.5-7B-Instruct-Turbo", "Qwen 2.5 7B", LLMProvider.TOGETHER),
    DEEPSEEK_V3("deepseek-ai/DeepSeek-V3", "DeepSeek V3 (Latest)", LLMProvider.TOGETHER),
    DEEPSEEK_V2_5("deepseek-ai/DeepSeek-V2.5", "DeepSeek V2.5", LLMProvider.TOGETHER),
    MIXTRAL_8X22B("mistralai/Mixtral-8x22B-Instruct-v0.1", "Mixtral 8x22B", LLMProvider.TOGETHER),
    MIXTRAL_8X7B("mistralai/Mixtral-8x7B-Instruct-v0.1", "Mixtral 8x7B", LLMProvider.TOGETHER),
    GEMMA_2_27B("google/gemma-2-27b-it", "Gemma 2 27B", LLMProvider.TOGETHER),
    GEMMA_2_9B("google/gemma-2-9b-it", "Gemma 2 9B", LLMProvider.TOGETHER);
    
    constructor(modelId: String, displayName: String, provider: LLMProvider) : this(modelId, displayName)
}

/**
 * LLM Settings data class
 */
@Serializable
data class LLMSettingsData(
    val selectedProvider: String = LLMProvider.ANTHROPIC.name,
    val selectedModel: String = LLMModel.CLAUDE_3_5_SONNET_V2.name,
    val selectedModelId: String? = null, // For dynamic models
    val apiKeys: Map<String, String> = emptyMap(), // Provider name -> API key
    val customEndpoint: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2000,
    val enableStreaming: Boolean = true,
    val enableCaching: Boolean = true,
    val cacheExpirationMinutes: Int = 60
)

/**
 * LLM Settings singleton
 */
object LLMSettings {
    private val logger = BossLogger.forComponent("LLMSettings")
    private var settings = LLMSettingsData()
    
    var selectedProvider: LLMProvider
        get() = try {
            LLMProvider.valueOf(settings.selectedProvider)
        } catch (e: Exception) {
            logger.debug(LogCategory.GENERAL, "Unknown LLM provider in settings - defaulting to ANTHROPIC", mapOf("error" to e.toString()))
            LLMProvider.ANTHROPIC
        }
        set(value) {
            settings = settings.copy(selectedProvider = value.name)
        }
    
    var selectedModel: LLMModel
        get() = try {
            LLMModel.valueOf(settings.selectedModel)
        } catch (e: Exception) {
            logger.debug(LogCategory.GENERAL, "Unknown LLM model in settings - using default", mapOf("error" to e.toString()))
            LLMModel.CLAUDE_3_5_SONNET_V2
        }
        set(value) {
            settings = settings.copy(selectedModel = value.name)
        }
    
    // New method for dynamic model selection
    var selectedModelId: String
        get() = settings.selectedModelId ?: selectedModel.modelId
        set(value) {
            settings = settings.copy(selectedModelId = value)
        }
    
    fun getApiKey(provider: LLMProvider): String? {
        // First check environment variables
        val envKey = getApiKeyFromEnvironment(provider)
        if (!envKey.isNullOrBlank()) {
            logger.debug(LogCategory.GENERAL, "Using API key from environment", mapOf("provider" to provider.name))
            return envKey
        }
        // Fall back to settings
        val settingsKey = settings.apiKeys[provider.name]
        if (!settingsKey.isNullOrBlank()) {
            logger.debug(LogCategory.GENERAL, "Using API key from settings", mapOf("provider" to provider.name))
            return settingsKey
        }
        logger.debug(LogCategory.GENERAL, "No API key found", mapOf("provider" to provider.name))
        return null
    }
    
    fun setApiKey(provider: LLMProvider, key: String?) {
        val newKeys = settings.apiKeys.toMutableMap()
        if (key.isNullOrBlank()) {
            newKeys.remove(provider.name)
        } else {
            newKeys[provider.name] = key
        }
        settings = settings.copy(apiKeys = newKeys)
    }
    
    var customEndpoint: String?
        get() = settings.customEndpoint
        set(value) {
            settings = settings.copy(customEndpoint = value)
        }
    
    var temperature: Float
        get() = settings.temperature
        set(value) {
            settings = settings.copy(temperature = value.coerceIn(0f, 2f))
        }
    
    var maxTokens: Int
        get() = settings.maxTokens
        set(value) {
            settings = settings.copy(maxTokens = value.coerceIn(100, 100000))
        }
    
    var enableStreaming: Boolean
        get() = settings.enableStreaming
        set(value) {
            settings = settings.copy(enableStreaming = value)
        }
    
    var enableCaching: Boolean
        get() = settings.enableCaching
        set(value) {
            settings = settings.copy(enableCaching = value)
        }

    fun loadFromJson(json: String) {
        try {
            settings = Json.decodeFromString(json)
        } catch (e: Exception) {
            // Keep default settings if parsing fails.
            // Deliberately NOT logging the throwable: kotlinx.serialization decode
            // errors embed a snippet of the offending JSON near the failure offset,
            // and this file holds provider API keys — the raw message could leak a
            // key fragment into WARN logs. The exception type is enough to diagnose.
            logger.warn(LogCategory.GENERAL, "Failed to parse LLM settings JSON - keeping defaults", mapOf(
                "exception" to (e::class.simpleName ?: "Exception")
            ))
        }
    }
    
    fun toJson(): String {
        return Json.encodeToString(settings)
    }
    
    /**
     * Get API key from environment variable
     */
    private fun getApiKeyFromEnvironment(provider: LLMProvider): String? {
        return when (provider) {
            LLMProvider.ANTHROPIC -> getEnvironmentVariable("ANTHROPIC_API_KEY")
            LLMProvider.OPENAI -> getEnvironmentVariable("OPENAI_API_KEY")
            LLMProvider.TOGETHER -> getEnvironmentVariable("TOGETHER_API_KEY")
            LLMProvider.CUSTOM -> getEnvironmentVariable("CUSTOM_LLM_API_KEY")
        }
    }

    /**
     * Check if current provider has a valid API key
     */
    fun hasValidApiKey(): Boolean {
        return getApiKey(selectedProvider)?.isNotBlank() == true
    }
    
    /**
     * Get the effective API endpoint
     */
    fun getApiEndpoint(): String {
        return when (selectedProvider) {
            LLMProvider.CUSTOM -> customEndpoint ?: ""
            else -> selectedProvider.baseUrl
        }
    }
}

/**
 * Platform-specific environment variable access
 */
expect fun getEnvironmentVariable(name: String): String?

/**
 * LLM Settings Manager for persistence
 */
expect object LLMSettingsManager {
    suspend fun loadSettings()
    suspend fun saveSettings()
}
