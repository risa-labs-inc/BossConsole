package ai.rever.boss.config

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Parse the on/off value of automatic Picture-in-Picture from a setting, property or environment.
 *
 * Null means the value said nothing usable, and every caller treats that as "no opinion" rather
 * than as off - a typo must not silently remove the feature while the row that would explain it
 * reads as overridden.
 */
fun parseAutoPipEnabled(raw: String?): Boolean? =
    when (raw?.trim()?.lowercase()) {
        "off", "false", "0", "no" -> false
        "on", "true", "1", "yes" -> true
        else -> null
    }

@Serializable
data class AutoPipSettings(
    val enabled: Boolean = true,
)

/**
 * Persists whether a call pops out when its tab is backgrounded, and republishes it for plugins.
 *
 * **One control for two halves, in two repos.** The host decides whether to pop the tab's surface
 * out; the browser plugin's hibernation guard decides whether to keep a popped-out tab alive. They
 * must agree, and they cannot share a Kotlin constant, so the value travels as a system property -
 * the same channel and the same reasoning as [SwipeNavSettingsManager], because
 * `PluginContext.settingsProvider` opens the Settings window and reads nothing.
 *
 * Default on: the feature is the point of BossConsole#282, and a call vanishing on a tab switch is
 * what it exists to prevent. The switch is for someone who does not want their surface moving
 * between windows at all.
 *
 * Live, not restart-scoped: read at each tab switch, so turning it off stops the next pop-out
 * rather than the next launch. That is why it is not folded into `ChromiumFlagsSettingsManager`,
 * whose rows all need a restart.
 */
object AutoPipSettingsManager {
    private val logger = BossLogger.forComponent("AutoPipSettingsManager")

    /** Key shared with the browser plugin. Changing it silently un-couples the two halves. */
    const val KEY: String = "BOSS_BROWSER_AUTO_PIP"

    internal var settingsFile: File = BossDirectories.resolve("auto-pip.json")

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            // Without this a setting equal to the default is OMITTED, so choosing "on" writes `{}`
            // and a later change of default would silently rewrite everyone's explicit choice.
            encodeDefaults = true
        }

    // Lazy so a test can repoint [settingsFile] first; eager construction would read the real
    // ~/.boss file before any test could get a word in.
    private val _settings: MutableStateFlow<AutoPipSettings> by lazy { MutableStateFlow(loadSync()) }
    val settings: StateFlow<AutoPipSettings> get() = _settings.asStateFlow()

    /**
     * The environment's value, or null. Read from the environment ONLY: a system property here
     * would report this object's own publication back to it and the row would look overridden.
     */
    fun envOverride(): String? = System.getenv(KEY)?.takeIf { it.isNotBlank() }

    /**
     * Whether the environment actually decides this, as opposed to merely holding something.
     *
     * `BOSS_BROWSER_AUTO_PIP=maybe` is non-blank but unparseable, so gating on mere presence would
     * disable the Settings row and skip publishing while [isEnabled] quietly used the stored
     * value - the two halves could then disagree, which is the one thing sharing a key prevents.
     */
    fun envDecides(): Boolean = parseAutoPipEnabled(envOverride()) != null

    /** Whether a backgrounded call should pop out right now. Env beats the stored setting. */
    fun isEnabled(): Boolean = parseAutoPipEnabled(envOverride()) ?: _settings.value.enabled

    fun set(enabled: Boolean) {
        _settings.value = AutoPipSettings(enabled)
        persist(_settings.value)
        publish()
    }

    /** Publish for the plugin half. Skipped when the environment owns the key, as elsewhere. */
    fun publish() {
        if (envDecides()) {
            logger.info(LogCategory.BROWSER, "Auto Picture-in-Picture setting ignored; the environment owns $KEY")
            return
        }
        System.setProperty(KEY, isEnabled().toString())
    }

    private fun loadSync(): AutoPipSettings =
        try {
            if (settingsFile.exists()) {
                json.decodeFromString<AutoPipSettings>(settingsFile.readText())
            } else {
                AutoPipSettings()
            }
        } catch (e: SerializationException) {
            logger.warn(
                LogCategory.BROWSER,
                "Could not read auto Picture-in-Picture settings; using the default",
                decodeFailure(e),
            )
            AutoPipSettings()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(
                LogCategory.BROWSER,
                "Could not read auto Picture-in-Picture settings; using the default",
                error = e,
            )
            AutoPipSettings()
        }

    internal fun reloadForTest() {
        _settings.value = loadSync()
    }

    private fun persist(value: AutoPipSettings) {
        try {
            settingsFile.parentFile?.mkdirs()
            // Written to a sibling and moved, like the swipe setting: a kill mid-write would
            // otherwise leave a truncated file the next launch reports as corrupt. Files.move and
            // not File.renameTo - the destination exists from the second write on, and renameTo
            // fails there on Windows, silently, in a return value nobody reads.
            val temp = File(settingsFile.parentFile, "${settingsFile.name}.tmp")
            temp.writeText(json.encodeToString(AutoPipSettings.serializer(), value))
            java.nio.file.Files.move(
                temp.toPath(),
                settingsFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.BROWSER, "Could not save auto Picture-in-Picture settings", error = e)
        }
    }
}
