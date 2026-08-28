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
 * What a two-finger swipe does, and what it looks like doing it.
 *
 * One control for both halves of the gesture: the host's in-page detector on web pages
 * ([ai.rever.boss.plugin.browser.BrowserSwipeNavScript]) and the browser plugin's own detector on
 * the home surface, which has no page to inject into. They must agree - a user who turns the
 * gesture off and finds it still working on home has been told something false - and they cannot
 * share a Kotlin constant, because one of them is in another repo.
 */
enum class SwipeNavStyle {
    /** No gesture at all. */
    OFF,

    /** A chevron tracks the swipe and the page changes when it commits. */
    CHEVRON,

    /** The outgoing page slides away over the incoming one, Safari-style. */
    SLIDE,
    ;

    val settingValue: String get() = name.lowercase()
}

/**
 * Parse a style from a setting, a system property or an environment variable.
 *
 * Accepts the legacy boolean spellings this key shipped with, because `BOSS_BROWSER_SWIPE_NAV`
 * was documented as an on/off switch before it grew a third state, and someone has it exported.
 * `false` still means off; `true` means the gesture, in its default presentation.
 *
 * An unrecognised value returns null, which callers treat as "no opinion" rather than as off - a
 * typo must not silently remove a gesture whose only route back is finding the same typo.
 */
fun parseSwipeNavStyle(raw: String?): SwipeNavStyle? =
    when (raw?.trim()?.lowercase()) {
        "off", "false", "0", "no" -> SwipeNavStyle.OFF
        "chevron", "true", "1", "yes", "on" -> SwipeNavStyle.CHEVRON
        "slide" -> SwipeNavStyle.SLIDE
        else -> null
    }

@Serializable
data class SwipeNavSettings(
    val style: String = SwipeNavStyle.CHEVRON.settingValue,
)

/**
 * Persists the swipe style and republishes it as a system property on every change.
 *
 * **Deliberately not folded into [ChromiumFlagsSettingsManager]**, which it otherwise resembles.
 * Those settings are restart-scoped by construction - `EngineOptions` are fixed when the engine is
 * created, and its KDoc says so - whereas this one is read per gesture and must take effect the
 * moment it is changed. Putting a live setting in a screen whose every other row says "needs a
 * restart" would be a small lie that costs someone an afternoon.
 *
 * The system property is how the setting reaches the **browser plugin**, which runs in this
 * process but in another repo and cannot see this class. `PluginContext.settingsProvider` only
 * opens the Settings window; it reads nothing. Publishing is the pattern this codebase already
 * uses for exactly that gap (see [ChromiumFlagsSettingsManager.applyToSystemProperties]), and
 * unlike that one it republishes on change rather than only at startup, so the plugin sees a new
 * value without a relaunch.
 */
object SwipeNavSettingsManager {
    private val logger = BossLogger.forComponent("SwipeNavSettingsManager")

    /** Key shared with the plugin. Changing it silently un-couples the two halves. */
    const val KEY: String = "BOSS_BROWSER_SWIPE_NAV"

    internal var settingsFile: File = BossDirectories.resolve("swipe-nav.json")

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val _settings = MutableStateFlow(loadSync())
    val settings: StateFlow<SwipeNavSettings> = _settings.asStateFlow()

    /**
     * The environment's value, or null. Read from the environment ONLY: a system property here
     * would report this object's own publication back to it, and every row would look overridden.
     */
    fun envOverride(): String? = System.getenv(KEY)?.takeIf { it.isNotBlank() }

    /**
     * What the gesture should do right now.
     *
     * Env beats the setting, matching how every other tunable here resolves, and the Settings row
     * says so rather than letting the control look broken.
     */
    fun current(): SwipeNavStyle =
        parseSwipeNavStyle(envOverride())
            ?: parseSwipeNavStyle(_settings.value.style)
            ?: SwipeNavStyle.CHEVRON

    fun set(style: SwipeNavStyle) {
        _settings.value = SwipeNavSettings(style.settingValue)
        persist(_settings.value)
        publish()
    }

    /** Publish for the plugin half. Skipped when the environment owns the key, as elsewhere. */
    fun publish() {
        if (envOverride() != null) {
            logger.info(LogCategory.BROWSER, "Swipe style setting ignored; the environment owns $KEY")
            return
        }
        System.setProperty(KEY, current().settingValue)
    }

    private fun loadSync(): SwipeNavSettings =
        try {
            if (settingsFile.exists()) {
                json.decodeFromString<SwipeNavSettings>(settingsFile.readText())
            } else {
                SwipeNavSettings()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A corrupt or half-written file must not stop the app booting over a gesture.
            logger.warn(LogCategory.BROWSER, "Could not read swipe settings; using the default", error = e)
            SwipeNavSettings()
        }

    private fun persist(value: SwipeNavSettings) {
        try {
            settingsFile.parentFile?.mkdirs()
            // Written to a sibling and moved, like the Chromium flags file: a kill mid-write would
            // otherwise leave a truncated file that the next launch reports as corrupt.
            val temp = File(settingsFile.parentFile, "${settingsFile.name}.tmp")
            temp.writeText(json.encodeToString(SwipeNavSettings.serializer(), value))
            temp.renameTo(settingsFile)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.BROWSER, "Could not save swipe settings", error = e)
        }
    }
}
