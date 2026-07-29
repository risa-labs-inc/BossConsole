package ai.rever.boss.kernel

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A model provider the self-healing orchestrator can be pointed at.
 *
 * **These names cannot be renamed.** They match the host's historical `LLMProvider` enum
 * because `llm_settings.json` is a legacy on-disk format keyed by those names, and
 * [SelfHealingSettingsManager] still reads it as a fallback. That enum is gone — provider
 * configuration now lives in the secret-manager plugin — so nothing checks this coupling
 * for us any more, which is precisely what makes the constraint load-bearing rather than
 * incidental.
 *
 * Self-healing does need its own key: it resolves one before the window opens and long
 * before any plugin registers, so it cannot read the plugin's store. `AI_REPAIR_API_KEY`
 * or the provider's own environment variable is the supported path — see
 * [SelfHealingSettingsManager.hasApiKey].
 */
enum class SelfHealingProvider(
    val displayName: String,
    /** Which HTTP shape the orchestrator should speak; see `AiRepairWire` in boss-orchestrator. */
    val wireProtocol: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val apiKeyEnvVar: String,
) {
    ANTHROPIC(
        displayName = "Anthropic Claude",
        wireProtocol = "anthropic",
        defaultEndpoint = "https://api.anthropic.com/v1/messages",
        defaultModel = "claude-opus-5",
        apiKeyEnvVar = "ANTHROPIC_API_KEY",
    ),
    OPENAI(
        displayName = "OpenAI",
        wireProtocol = "openai",
        defaultEndpoint = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-5.4",
        apiKeyEnvVar = "OPENAI_API_KEY",
    ),
    TOGETHER(
        displayName = "Together AI",
        wireProtocol = "openai",
        defaultEndpoint = "https://api.together.xyz/v1/chat/completions",
        defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        apiKeyEnvVar = "TOGETHER_API_KEY",
    ),
    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        wireProtocol = "openai",
        defaultEndpoint = "",
        defaultModel = "",
        apiKeyEnvVar = "CUSTOM_LLM_API_KEY",
    ),
    ;

    companion object {
        /** The stored provider, or null if the file names one this build doesn't have. */
        fun parse(name: String): SelfHealingProvider? = entries.firstOrNull { it.name == name }

        /** The stored provider, or [ANTHROPIC] as a display default. For the UI, not for routing. */
        fun of(name: String): SelfHealingProvider = parse(name) ?: ANTHROPIC
    }
}

/**
 * How the orchestrator's AI repair should be configured, as the operator left it.
 *
 * Blank means "use the provider's default" for [model] and [endpoint], and "use the built-in
 * instruction" for [systemPrompt] — a cleared field in the UI should restore the default rather
 * than send an empty model name.
 */
@Serializable
data class SelfHealingSettingsData(
    /**
     * Whether the orchestrator may send crash source to a third-party model.
     *
     * Off by default and stays off until someone turns it on: this is the data-egress switch, not a
     * convenience toggle, and it is deliberately separate from whether self-healing runs at all
     * (restart, state reset and escalation need no model and are always available).
     */
    val aiRepairEnabled: Boolean = false,
    val provider: String = SelfHealingProvider.ANTHROPIC.name,
    val model: String = "",
    val endpoint: String = "",
    val systemPrompt: String = "",
    /** The only directory the model may be shown source from. No root, no AI repair. */
    val projectRoot: String = "",
)

/**
 * Reads and writes `~/.boss/self-healing-settings.json`, and turns it into the environment the
 * kernel hands the orchestrator process.
 *
 * Follows the BOSS settings convention: synchronous load on first touch, async save on write.
 */
object SelfHealingSettingsManager {
    private val logger = BossLogger.forComponent("SelfHealingSettings")
    private val settingsFile: File = BossDirectories.resolve("self-healing-settings.json")

    /**
     * Legacy key files, in priority order. The `.migrated` name is what the
     * secret-manager plugin renames the original to once it has imported the keys into
     * the secret store, so both must be tried.
     */
    private val LEGACY_KEY_FILE_NAMES = listOf("llm_settings.json", "llm_settings.json.migrated")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val _currentSettings = MutableStateFlow(SelfHealingSettingsData())
    val currentSettings: StateFlow<SelfHealingSettingsData> = _currentSettings.asStateFlow()

    init {
        settingsFile.parentFile?.mkdirs()
        loadSettingsSync()
    }

    suspend fun updateSettings(settings: SelfHealingSettingsData) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * The environment variables the orchestrator needs to run AI repair.
     *
     * Empty unless every part of the decision is present — switched on, a named source root, and a
     * usable key. A half-configured environment is worse than none: it would start the client and
     * fail per crash, and emitting the key while the feature is off would put a secret in a child
     * process for no reason.
     *
     * Note what this does *not* do. `ProcessSpawner` starts each child from a copy of BOSS's own
     * environment, so an `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` exported into the app's process is
     * inherited by all nine children whatever this returns. What is withheld here is the key the
     * operator entered in settings, and — the part that actually gates anything — `BOSS_AI_REPAIR`
     * and the source root, without which the orchestrator constructs no client at all.
     */
    fun orchestratorEnvironment(): Map<String, String> =
        repairEnvironment(
            settings = _currentSettings.value,
            apiKey = resolveApiKey(SelfHealingProvider.of(_currentSettings.value.provider)),
        )

    /**
     * Whether a key for [provider] can be found, without handing the key out.
     *
     * The settings screen needs this: a missing key silently withholds the whole environment, and
     * an operator who has flipped the switch deserves to be told that nothing will happen.
     */
    fun hasApiKey(provider: SelfHealingProvider): Boolean = !resolveApiKey(provider).isNullOrBlank()

    /**
     * The key for [provider]: `AI_REPAIR_API_KEY`, then the provider's own environment
     * variable, then the legacy `llm_settings.json`.
     *
     * `AI_REPAIR_API_KEY` wins so an operator can point self-healing at a separate, narrower key
     * than the one their assistant chat uses.
     *
     * **An environment variable is the supported way to key self-healing.** AI provider
     * configuration now lives in the secret-manager plugin, and the kernel cannot read it: it
     * spawns services before the window opens and long before any plugin registers, and the
     * store needs a signed-in session the kernel does not have. The legacy file is still read
     * so existing installs keep working, including after the file has been renamed by the
     * plugin's one-shot import — but a key rotated in Settings → AI Providers will *not* be
     * reflected there, so treat it as a fallback, not a source of truth.
     */
    private fun resolveApiKey(provider: SelfHealingProvider): String? =
        System.getenv("AI_REPAIR_API_KEY")?.ifBlank { null }
            ?: System.getenv(provider.apiKeyEnvVar)?.ifBlank { null }
            ?: storedLlmApiKey(provider)

    private fun storedLlmApiKey(provider: SelfHealingProvider): String? =
        legacyKeyFrom(LEGACY_KEY_FILE_NAMES.map { BossDirectories.resolve(it) }, provider.name)

    /**
     * First key for [providerName] found across [files], in order.
     *
     * Takes the file list rather than resolving it so this is reachable from a test:
     * it is the riskiest logic in this file and fails quietly. The `.migrated` name
     * exists because the secret-manager plugin renames the original after importing
     * keys, and without it enabling self-healing would appear to break exactly on
     * upgraded installs.
     *
     * A file that exists but has no entry for *this* provider falls through to the next
     * rather than short-circuiting, and an unreadable file is skipped rather than
     * aborting the search. Warns at most once per call, since [hasApiKey] re-runs on
     * every settings change and a corrupt file would otherwise log per candidate.
     */
    internal fun legacyKeyFrom(
        files: List<File>,
        providerName: String,
    ): String? {
        var reported = false
        return files.firstNotNullOfOrNull { file ->
            if (!file.exists()) {
                null
            } else {
                try {
                    json
                        .decodeFromString(StoredLlmKeys.serializer(), file.readText())
                        .apiKeys[providerName]
                        ?.ifBlank { null }
                } catch (e: SerializationException) {
                    if (!reported) {
                        reportUnreadableKeys(e)
                        reported = true
                    }
                    null
                } catch (e: IOException) {
                    if (!reported) {
                        reportUnreadableKeys(e)
                        reported = true
                    }
                    null
                }
            }
        }
    }

    /**
     * Says the key file could not be read, without saying what was in it.
     *
     * Deliberately logs only the exception type: kotlinx.serialization embeds a snippet of the
     * offending JSON near the failure offset, and that file holds API keys.
     */
    private fun reportUnreadableKeys(e: Exception) {
        logger.warn(
            LogCategory.SYSTEM,
            "Could not read stored LLM API keys",
            mapOf("exception" to (e::class.simpleName ?: "Exception")),
        )
    }

    private suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.atomicWriteText(
                    json.encodeToString(SelfHealingSettingsData.serializer(), _currentSettings.value),
                )
            } catch (e: IOException) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to save self-healing settings",
                    mapOf("file" to settingsFile.absolutePath),
                    error = e,
                )
            }
        }

    private fun loadSettingsSync() {
        if (!settingsFile.exists()) return
        try {
            _currentSettings.value =
                json.decodeFromString(SelfHealingSettingsData.serializer(), settingsFile.readText())
        } catch (e: SerializationException) {
            logger.warn(LogCategory.SYSTEM, "Unreadable self-healing settings — using defaults", error = e)
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Could not load self-healing settings — using defaults", error = e)
        }
    }

    /** Just the key map out of `llm_settings.json`; the rest of that file is not ours to interpret. */
    @Serializable
    private data class StoredLlmKeys(
        val apiKeys: Map<String, String> = emptyMap(),
    )
}

/**
 * Turn a settings snapshot plus a resolved key into the orchestrator's environment.
 *
 * Pure and separate from the manager because it is the egress decision in one place: which of these
 * three conditions is missing decides whether any source ever leaves the machine, and that should be
 * assertable without a filesystem or an environment.
 */
internal fun repairEnvironment(
    settings: SelfHealingSettingsData,
    apiKey: String?,
): Map<String, String> {
    // A name this build doesn't know means the file was written by a newer version, so the endpoint
    // beside it belongs to a provider this build cannot speak to. Defaulting the *wire* while
    // keeping that *endpoint* would post the key — resolved under the defaulted provider's name — to
    // a host the operator never chose for it. Unknown provider is unconfigured.
    val provider = SelfHealingProvider.parse(settings.provider)

    // Each of these has to be present for AI repair to do anything, so they are gathered first and
    // checked as a set — a configuration missing any one of them yields no environment at all.
    val required =
        mapOf(
            "BOSS_REPAIR_PROJECT_ROOT" to settings.projectRoot.trim(),
            "AI_REPAIR_API_URL" to settings.endpoint.trim().ifBlank { provider?.defaultEndpoint.orEmpty() },
            "AI_REPAIR_MODEL" to settings.model.trim().ifBlank { provider?.defaultModel.orEmpty() },
            "AI_REPAIR_API_KEY" to apiKey.orEmpty().trim(),
        )
    val configured = settings.aiRepairEnabled && required.values.none { it.isBlank() }

    return if (provider == null || !configured) {
        emptyMap()
    } else {
        required +
            buildMap {
                put("BOSS_AI_REPAIR", "true")
                put("AI_REPAIR_PROVIDER", provider.wireProtocol)
                settings.systemPrompt.ifBlank { null }?.let { put("AI_REPAIR_SYSTEM_PROMPT", it) }
            }
    }
}
