package ai.rever.boss.config

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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
    val liteThresholdGb: Int = ResourceModeConfig.DEFAULT_LITE_THRESHOLD_GB,
    val ultraLiteThresholdGb: Int = ResourceModeConfig.DEFAULT_ULTRA_LITE_THRESHOLD_GB,
    val livePressureEnabled: Boolean = true,
)

/**
 * Reads and writes `~/.boss/resource-mode.json`.
 *
 * Every value here takes effect on the **next launch**, which is a property of the feature
 * rather than a limitation of the storage: plugin gating happens once during startup, and a
 * classloader that was already built cannot be un-built to reclaim its memory. Settings says
 * so rather than pretending otherwise.
 */
object ResourceModeSettings {
    private val logger = BossLogger.forComponent("ResourceModeSettings")
    private val settingsFile: File by lazy { BossDirectories.resolve("resource-mode.json") }
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @Volatile
    private var cached: ResourceModeSettingsData? = null

    /** Current persisted settings, defaults when the file is absent or unreadable. */
    fun current(): ResourceModeSettingsData =
        cached ?: synchronized(this) {
            cached ?: load().also { cached = it }
        }

    fun update(transform: (ResourceModeSettingsData) -> ResourceModeSettingsData) {
        synchronized(this) {
            val next = transform(current())
            runCatching {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(json.encodeToString(ResourceModeSettingsData.serializer(), next))
                cached = next
            }.onFailure { e ->
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not persist resource-mode settings",
                    mapOf("error" to (e.message ?: "unknown")),
                )
            }
        }
    }

    private fun load(): ResourceModeSettingsData =
        runCatching {
            if (!settingsFile.exists()) return@runCatching ResourceModeSettingsData()
            json.decodeFromString(ResourceModeSettingsData.serializer(), settingsFile.readText())
        }.getOrElse { e ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not read resource-mode settings - using defaults",
                mapOf("error" to (e.message ?: "unknown")),
            )
            ResourceModeSettingsData()
        }
}
