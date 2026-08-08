package ai.rever.boss.config

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The user's persisted resource-mode preferences.
 *
 * [selectedMode] is null for "Auto", which is the default and hands the decision to
 * [ResourceModeConfig.resolveResourceMode].
 */
@Serializable
data class ResourceModeSettingsData(
    val selectedMode: String? = null,
    /**
     * A tier requested for the next launch only, consumed and cleared by
     * [ResourceModeConfig.decision].
     *
     * Separate from [selectedMode] so the memory-pressure notice's restart button cannot turn
     * one click under pressure into the permanent choice, and cannot make Settings report the
     * tier as something the user selected.
     */
    val nextLaunchMode: String? = null,
    val liteThresholdGb: Int = ResourceModeConfig.DEFAULT_LITE_THRESHOLD_GB,
    val ultraLiteThresholdGb: Int = ResourceModeConfig.DEFAULT_ULTRA_LITE_THRESHOLD_GB,
    val livePressureEnabled: Boolean = true,
)

/**
 * Reads and writes `~/.boss/resource-mode.json`.
 *
 * Every value here takes effect on the **next launch**, which is a property of the feature
 * rather than a limitation of the storage: the renderer-process limit is a Chromium command-line
 * switch, read once when the engine initialises and not re-readable afterwards. Both the Settings
 * screen and the View menu say so rather than appearing to apply a change that has not happened.
 */
object ResourceModeSettings {
    private val logger = BossLogger.forComponent("ResourceModeSettings")
    private val settingsFile: File by lazy { BossDirectories.resolve("resource-mode.json") }
    private val serializer = ResourceModeSettingsData.serializer()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    // `by lazy` so the disk read still happens on first use rather than at class-init, which is
    // what the hand-rolled double-checked cache here used to buy.
    private val state: MutableStateFlow<ResourceModeSettingsData> by lazy { MutableStateFlow(load()) }

    /**
     * Observable settings, so every surface showing the mode agrees.
     *
     * There are two of them now - the Settings screen and the View menu - and a plain cached read
     * left whichever composed first showing a stale selection until something else recomposed it.
     */
    val settings: StateFlow<ResourceModeSettingsData> get() = state.asStateFlow()

    /** Current persisted settings, defaults when the file is absent or unreadable. */
    fun current(): ResourceModeSettingsData = state.value

    fun update(transform: (ResourceModeSettingsData) -> ResourceModeSettingsData) {
        synchronized(this) {
            val next = transform(current())
            runCatching {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(encode(next))
                state.value = next
            }.onFailure { e ->
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not persist resource-mode settings",
                    mapOf("error" to (e.message ?: "unknown")),
                )
            }
        }
    }

    // readText inside the runCatching, not outside it: current()'s contract is "defaults when the
    // file is absent OR unreadable", and an existing-but-unreadable file throws an IOException
    // that would escape the `by lazy` and take startup's publishToPlugins() with it.
    private fun load(): ResourceModeSettingsData =
        runCatching { if (settingsFile.exists()) settingsFile.readText() else null }
            .getOrNull()
            ?.let { decode(it) }
            ?: ResourceModeSettingsData()

    /**
     * Parses the settings document, falling back to defaults rather than throwing.
     *
     * Split from [load] so the parsing rules are testable without a home directory: this object
     * resolves a real path under `~/.boss`, and a test that wrote there would both pollute the
     * developer's install and make itself order-dependent.
     *
     * Lenient on purpose. `ignoreUnknownKeys` is what lets an older build read a file a newer one
     * wrote - the same additive-migration hazard documented for the Supabase models in AGENTS.md,
     * where one unmodelled field emptied whole lists on installed builds.
     */
    internal fun decode(raw: String): ResourceModeSettingsData =
        runCatching {
            json.decodeFromString(serializer, raw)
        }.getOrElse { e ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not read resource-mode settings - using defaults",
                mapOf("error" to (e.message ?: "unknown")),
            )
            ResourceModeSettingsData()
        }

    /** Encodes [data] exactly as [update] would write it. Used by the round-trip test. */
    internal fun encode(data: ResourceModeSettingsData) = json.encodeToString(serializer, data)
}
