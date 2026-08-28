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
 * Parse the on/off value of the swipe gesture from a setting, a property or the environment.
 *
 * Null means the value said nothing usable, and every caller treats that as "no opinion" rather
 * than as off - a typo must not silently remove a gesture whose only route back is finding the
 * same typo.
 */
fun parseSwipeNavEnabled(raw: String?): Boolean? =
    when (raw?.trim()?.lowercase()) {
        "off", "false", "0", "no" -> false
        "on", "true", "1", "yes", "chevron" -> true
        else -> null
    }

@Serializable
data class SwipeNavSettings(
    val enabled: Boolean = true,
)

/**
 * Persists whether the two-finger swipe gesture is on, and republishes it as a system property.
 *
 * One control for both halves of the gesture: the host's in-page detector on web pages
 * ([ai.rever.boss.plugin.browser.BrowserSwipeNavScript]) and the browser plugin's own detector on
 * the home surface, which has no page to inject into. They must agree - a user who turns the
 * gesture off and finds it still working on home has been told something false - and they cannot
 * share a Kotlin constant, because one of them is in another repo.
 *
 * The system property is the channel. `PluginContext.settingsProvider` only opens the Settings
 * window; it reads nothing, so there is no api route for a value, and publishing is the pattern
 * this codebase already uses for exactly that gap (see
 * [ChromiumFlagsSettingsManager.applyToSystemProperties]).
 *
 * **Deliberately not folded into [ChromiumFlagsSettingsManager]**, which it otherwise resembles.
 * Those settings are restart-scoped by construction - `EngineOptions` are fixed when the engine is
 * created, and its KDoc says so - whereas this one is read per gesture and takes effect the moment
 * it changes. A live setting in a screen whose every other row says "needs a restart" would be a
 * small lie that costs someone an afternoon.
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
            // Without this a setting equal to the default is OMITTED, so choosing "on" writes `{}`.
            // That round-trips correctly only for as long as the default never changes: flip it and
            // every stored `{}` silently flips with it, turning a user's explicit choice into
            // whatever the new default is. Writing the value down keeps the file meaning what it
            // says.
            encodeDefaults = true
        }

    // Lazy, so a test can point [settingsFile] somewhere else first. Eager initialisation read the
    // real ~/.boss file during object construction, before any test could get a word in, which made
    // the seam look like it worked while doing nothing.
    private val _settings: MutableStateFlow<SwipeNavSettings> by lazy { MutableStateFlow(loadSync()) }
    val settings: StateFlow<SwipeNavSettings> get() = _settings.asStateFlow()

    /**
     * The environment's value, or null. Read from the environment ONLY: a system property here
     * would report this object's own publication back to it, and the row would look overridden.
     */
    fun envOverride(): String? = System.getenv(KEY)?.takeIf { it.isNotBlank() }

    /**
     * Whether the environment actually decides this, as opposed to merely having something in it.
     *
     * The distinction is the whole of a bug this had. `BOSS_BROWSER_SWIPE_NAV=maybe` is non-blank,
     * so an [envOverride]-based gate disabled the Settings row and skipped publishing - while
     * [isEnabled] parsed the same value to null and quietly used the stored setting. The row said
     * the environment owned a value the app was ignoring, the property was never written, and the
     * plugin half fell back to its own default: the two halves could then disagree, which is the
     * one thing sharing this key exists to prevent.
     */
    fun envDecides(): Boolean = parseSwipeNavEnabled(envOverride()) != null

    /**
     * Whether the gesture is on right now.
     *
     * Env beats the setting, matching how every other tunable here resolves, and the Settings row
     * says so rather than letting the control look broken.
     */
    fun isEnabled(): Boolean =
        parseSwipeNavEnabled(envOverride())
            ?: _settings.value.enabled

    fun set(enabled: Boolean) {
        _settings.value = SwipeNavSettings(enabled)
        persist(_settings.value)
        publish()
    }

    /** Publish for the plugin half. Skipped when the environment owns the key, as elsewhere. */
    fun publish() {
        if (envDecides()) {
            logger.info(LogCategory.BROWSER, "Swipe gesture setting ignored; the environment owns $KEY")
            return
        }
        System.setProperty(KEY, isEnabled().toString())
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
            //
            // Files.move, NOT File.renameTo, and the difference has shipped here before: v9.2.65's
            // notes read "Favicons update on Windows again: File.renameTo fails with
            // ERROR_ALREADY_EXISTS where POSIX rename(2) replaces". The destination exists from the
            // second write onward, so renameTo would persist this setting exactly once and then
            // stop - silently, since it reports failure in a return value nobody reads.
            val temp = File(settingsFile.parentFile, "${settingsFile.name}.tmp")
            temp.writeText(json.encodeToString(SwipeNavSettings.serializer(), value))
            java.nio.file.Files.move(
                temp.toPath(),
                settingsFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.BROWSER, "Could not save swipe settings", error = e)
        }
    }
}
