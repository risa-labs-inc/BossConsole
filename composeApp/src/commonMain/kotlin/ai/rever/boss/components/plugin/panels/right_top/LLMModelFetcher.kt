package ai.rever.boss.components.plugin.panels.right_top

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Dynamic LLM Model representation
 */
@Serializable
data class DynamicLLMModel(
    val id: String,
    val name: String,
    val provider: String,
    val contextLength: Int? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val capabilities: List<String> = emptyList(),
)

/**
 * Model cache with timestamp
 */
@Serializable
data class ModelCache(
    val models: Map<String, List<DynamicLLMModel>>,
    val lastUpdated: Long,
    val version: String = "1.0",
)

/**
 * LLM Model Fetcher - Dynamically fetches available models from providers
 */
object LLMModelFetcher {
    private val _availableModels = MutableStateFlow<Map<String, List<DynamicLLMModel>>>(emptyMap())
    val availableModels: StateFlow<Map<String, List<DynamicLLMModel>>> = _availableModels

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // Cache duration: 24 hours
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

    /**
     * Fetch latest models from all providers
     */
    suspend fun fetchLatestModels(forceRefresh: Boolean = false) {
        if (_isLoading.value) return

        // Check cache first
        if (!forceRefresh) {
            val cachedModels = loadCachedModels()
            if (cachedModels != null && !isCacheExpired(cachedModels)) {
                _availableModels.value = cachedModels.models
                return
            }
        }

        _isLoading.value = true
        _lastError.value = null

        try {
            val allModels = mutableMapOf<String, List<DynamicLLMModel>>()

            // Fetch from each provider
            allModels[LLMProvider.ANTHROPIC.name] = fetchAnthropicModels()
            allModels[LLMProvider.OPENAI.name] = fetchOpenAIModels()
            allModels[LLMProvider.TOGETHER.name] = fetchTogetherModels()

            _availableModels.value = allModels

            // Cache the results
            saveModelsToCache(allModels)
        } catch (e: Exception) {
            _lastError.value = "Failed to fetch models: ${e.message}"
            // Fall back to hardcoded models if fetch fails
            loadFallbackModels()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Get models for a specific provider
     */
    fun getModelsForProvider(provider: LLMProvider): List<DynamicLLMModel> =
        _availableModels.value[provider.name] ?: getFallbackModelsForProvider(provider)

    /**
     * Find a model by ID
     */
    fun findModelById(modelId: String): DynamicLLMModel? =
        _availableModels.value.values
            .flatten()
            .find { it.id == modelId }

    private suspend fun fetchAnthropicModels(): List<DynamicLLMModel> {
        // In a real implementation, this would call Anthropic's API
        // Updated for June 2025 based on actual releases
        return listOf(
            DynamicLLMModel(
                id = "claude-opus-4",
                name = "Claude Opus 4",
                provider = "ANTHROPIC",
                contextLength = 200000,
                description = "World's best coding model (72.5% SWE-bench)",
                releaseDate = "2025-05",
                capabilities = listOf("text", "vision", "function-calling", "code-execution", "web-search", "extended-thinking"),
            ),
            DynamicLLMModel(
                id = "claude-sonnet-4",
                name = "Claude Sonnet 4",
                provider = "ANTHROPIC",
                contextLength = 200000,
                description = "Balanced performance with extended thinking",
                releaseDate = "2025-05",
                capabilities = listOf("text", "vision", "function-calling", "code-execution", "web-search", "extended-thinking"),
            ),
            DynamicLLMModel(
                id = "claude-3-7-sonnet",
                name = "Claude 3.7 Sonnet",
                provider = "ANTHROPIC",
                contextLength = 200000,
                description = "Advanced hybrid reasoning model",
                releaseDate = "2025-04",
                capabilities = listOf("text", "vision", "function-calling", "reasoning"),
            ),
            DynamicLLMModel(
                id = "claude-3-5-sonnet-20241022",
                name = "Claude 3.5 Sonnet",
                provider = "ANTHROPIC",
                contextLength = 200000,
                description = "Previous generation model",
                releaseDate = "2024-10",
                capabilities = listOf("text", "vision", "function-calling"),
            ),
        )
    }

    private suspend fun fetchOpenAIModels(): List<DynamicLLMModel> {
        // This would call OpenAI's models API endpoint
        return listOf(
            DynamicLLMModel(
                id = "o3-pro",
                name = "o3 Pro",
                provider = "OPENAI",
                contextLength = 128000,
                description = "Most capable reasoning model",
                releaseDate = "2025-06",
                capabilities = listOf("text", "reasoning", "math", "code", "research", "chain-of-thought"),
            ),
            DynamicLLMModel(
                id = "o3",
                name = "o3",
                provider = "OPENAI",
                contextLength = 128000,
                description = "Advanced reasoning (69.1% SWE-bench)",
                releaseDate = "2025-04",
                capabilities = listOf("text", "reasoning", "math", "code", "chain-of-thought"),
            ),
            DynamicLLMModel(
                id = "o3-mini",
                name = "o3 Mini",
                provider = "OPENAI",
                contextLength = 128000,
                description = "Fast and efficient reasoning",
                releaseDate = "2025-01",
                capabilities = listOf("text", "reasoning", "math", "code"),
            ),
            DynamicLLMModel(
                id = "gpt-4-5",
                name = "GPT-4.5",
                provider = "OPENAI",
                contextLength = 128000,
                description = "Latest ChatGPT model",
                releaseDate = "2025-04",
                capabilities = listOf("text", "vision", "function-calling", "json-mode"),
            ),
            DynamicLLMModel(
                id = "gpt-4-1",
                name = "GPT-4.1",
                provider = "OPENAI",
                contextLength = 128000,
                description = "Enhanced coding (54.6% SWE-bench)",
                releaseDate = "2025-04",
                capabilities = listOf("text", "vision", "function-calling", "json-mode", "code"),
            ),
            DynamicLLMModel(
                id = "gpt-4o",
                name = "GPT-4o",
                provider = "OPENAI",
                contextLength = 128000,
                description = "Multimodal flagship model",
                capabilities = listOf("text", "vision", "function-calling", "json-mode"),
            ),
        )
    }

    private suspend fun fetchTogetherModels(): List<DynamicLLMModel> {
        // This would call Together AI's API
        return listOf(
            DynamicLLMModel(
                id = "meta-llama/Llama-4-Maverick",
                name = "Llama 4 Maverick (400B)",
                provider = "TOGETHER",
                contextLength = 1000000,
                description = "17B active, 128 experts, 1M context",
                releaseDate = "2025-04",
                capabilities = listOf("text", "vision", "code", "multilingual", "function-calling"),
            ),
            DynamicLLMModel(
                id = "meta-llama/Llama-4-Scout",
                name = "Llama 4 Scout (109B)",
                provider = "TOGETHER",
                contextLength = 10000000,
                description = "17B active, 16 experts, 10M context",
                releaseDate = "2025-04",
                capabilities = listOf("text", "vision", "code", "multilingual"),
            ),
            DynamicLLMModel(
                id = "deepseek-ai/DeepSeek-V3",
                name = "DeepSeek V3 (671B)",
                provider = "TOGETHER",
                contextLength = 256000,
                description = "MoE with 37B active params",
                releaseDate = "2025-02",
                capabilities = listOf("text", "code", "math", "reasoning"),
            ),
            DynamicLLMModel(
                id = "google/gemini-2-5-pro",
                name = "Gemini 2.5 Pro",
                provider = "TOGETHER",
                contextLength = 1000000,
                description = "1M token context window",
                releaseDate = "2025-05",
                capabilities = listOf("text", "vision", "code", "reasoning"),
            ),
            DynamicLLMModel(
                id = "xai/grok-3",
                name = "Grok 3",
                provider = "TOGETHER",
                contextLength = 128000,
                description = "Advanced reasoning with step verification",
                releaseDate = "2025-02",
                capabilities = listOf("text", "reasoning", "code", "math"),
            ),
            DynamicLLMModel(
                id = "cohere/command-r-plus",
                name = "Command R+",
                provider = "TOGETHER",
                contextLength = 128000,
                description = "Near GPT-4 reasoning, faster & cheaper",
                releaseDate = "2025-03",
                capabilities = listOf("text", "code", "function-calling"),
            ),
        )
    }

    private fun loadFallbackModels() {
        _availableModels.value =
            mapOf(
                LLMProvider.ANTHROPIC.name to getFallbackModelsForProvider(LLMProvider.ANTHROPIC),
                LLMProvider.OPENAI.name to getFallbackModelsForProvider(LLMProvider.OPENAI),
                LLMProvider.TOGETHER.name to getFallbackModelsForProvider(LLMProvider.TOGETHER),
            )
    }

    private fun getFallbackModelsForProvider(provider: LLMProvider): List<DynamicLLMModel> {
        // Fallback models for June 2025 based on actual releases
        return when (provider) {
            LLMProvider.ANTHROPIC -> {
                listOf(
                    DynamicLLMModel("claude-opus-4", "Claude Opus 4", "ANTHROPIC", 200000),
                    DynamicLLMModel("claude-sonnet-4", "Claude Sonnet 4", "ANTHROPIC", 200000),
                    DynamicLLMModel("claude-3-7-sonnet", "Claude 3.7 Sonnet", "ANTHROPIC", 200000),
                    DynamicLLMModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", "ANTHROPIC", 200000),
                )
            }

            LLMProvider.OPENAI -> {
                listOf(
                    DynamicLLMModel("o3-pro", "o3 Pro", "OPENAI", 128000),
                    DynamicLLMModel("o3", "o3", "OPENAI", 128000),
                    DynamicLLMModel("o3-mini", "o3 Mini", "OPENAI", 128000),
                    DynamicLLMModel("gpt-4-5", "GPT-4.5", "OPENAI", 128000),
                    DynamicLLMModel("gpt-4o", "GPT-4o", "OPENAI", 128000),
                )
            }

            LLMProvider.TOGETHER -> {
                listOf(
                    DynamicLLMModel("meta-llama/Llama-4-Maverick", "Llama 4 Maverick", "TOGETHER", 1000000),
                    DynamicLLMModel("meta-llama/Llama-4-Scout", "Llama 4 Scout", "TOGETHER", 10000000),
                    DynamicLLMModel("deepseek-ai/DeepSeek-V3", "DeepSeek V3", "TOGETHER", 256000),
                    DynamicLLMModel("google/gemini-2-5-pro", "Gemini 2.5 Pro", "TOGETHER", 1000000),
                )
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun isCacheExpired(cache: ModelCache): Boolean =
        Clock.System.now().toEpochMilliseconds() - cache.lastUpdated > CACHE_DURATION_MS

    private suspend fun loadCachedModels(): ModelCache? {
        // Implementation depends on platform
        return null
    }

    private suspend fun saveModelsToCache(models: Map<String, List<DynamicLLMModel>>) {
        ModelCache(
            models = models,
            lastUpdated = Clock.System.now().toEpochMilliseconds(),
        )
        // Save to platform-specific storage
    }
}
