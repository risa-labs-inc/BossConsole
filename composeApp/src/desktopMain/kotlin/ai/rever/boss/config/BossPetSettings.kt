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
 * Parse the on/off value of the floating pet from a setting, a property or the environment.
 *
 * Null means the value said nothing usable, treated as "no opinion" rather than as off - the same
 * rule [parseSwipeNavEnabled] follows, so a typo in the environment does not silently hide a feature
 * whose only route back is finding the same typo.
 */
fun parseBossPetEnabled(raw: String?): Boolean? =
    when (raw?.trim()?.lowercase()) {
        "off", "false", "0", "no" -> false
        "on", "true", "1", "yes" -> true
        else -> null
    }

/**
 * Persisted state of the floating BOSS pet: whether it is shown, and where it was last dragged.
 *
 * The position is nullable so a first run has no opinion and the window can place itself in a
 * sensible default corner rather than at (0, 0). Both are `null`-tolerant on read (see
 * [BossPetSettingsManager]) so an older or partial file never stops the app booting.
 */
@Serializable
data class BossPetSettings(
    val enabled: Boolean = false,
    val anchorX: Int? = null,
    val anchorY: Int? = null,
)

/**
 * Persists whether the floating pet is shown and its last position.
 *
 * Mirrors [SwipeNavSettingsManager] deliberately - same atomic-move persistence, same lazy
 * [settingsFile] seam for tests, same env-beats-file precedence - but is its own object because the
 * pet is a host-only surface with no plugin half to keep in sync, so unlike swipe-nav it publishes
 * no system property.
 *
 * **Default off.** A floating, always-on-top window that appeared unasked would be a change to every
 * user's default desktop, so the feature is opt-in: the stored default is disabled and the app's
 * behaviour is unchanged until someone turns it on in Settings or sets [KEY] in the environment.
 */
object BossPetSettingsManager {
    private val logger = BossLogger.forComponent("BossPetSettingsManager")

    /** Environment override, e.g. `BOSS_PET=on`. */
    const val KEY: String = "BOSS_PET"

    internal var settingsFile: File = BossDirectories.resolve("boss-pet.json")

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            // Write the value even when it equals the default, so flipping the default later cannot
            // silently rewrite what a user explicitly chose. Same reasoning as SwipeNavSettings.
            encodeDefaults = true
        }

    private val _settings: MutableStateFlow<BossPetSettings> by lazy { MutableStateFlow(loadSync()) }
    val settings: StateFlow<BossPetSettings> get() = _settings.asStateFlow()

    /** The environment's raw value, or null when unset or blank. */
    fun envOverride(): String? = System.getenv(KEY)?.takeIf { it.isNotBlank() }

    /**
     * Whether the environment actually decides this, as opposed to merely holding something.
     * `BOSS_PET=maybe` is non-blank but parses to null, so it does not own the setting - the same
     * distinction [SwipeNavSettingsManager.envDecides] draws to stop a garbage value disabling the
     * Settings control while the app quietly ignores it.
     */
    fun envDecides(): Boolean = parseBossPetEnabled(envOverride()) != null

    /** Whether the pet is shown right now. Env beats the stored setting, as elsewhere here. */
    fun isEnabled(): Boolean =
        parseBossPetEnabled(envOverride())
            ?: _settings.value.enabled

    fun setEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(enabled = enabled)
        persist(_settings.value)
    }

    /** Remember where the user dragged the pet, so it returns there next launch. */
    fun setAnchor(
        x: Int,
        y: Int,
    ) {
        _settings.value = _settings.value.copy(anchorX = x, anchorY = y)
        persist(_settings.value)
    }

    private fun loadSync(): BossPetSettings =
        try {
            if (settingsFile.exists()) {
                json.decodeFromString<BossPetSettings>(settingsFile.readText())
            } else {
                BossPetSettings()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A corrupt or half-written file must not stop the app booting over a decoration.
            logger.warn(LogCategory.UI, "Could not read pet settings; using the default", error = e)
            BossPetSettings()
        }

    private fun persist(value: BossPetSettings) {
        try {
            settingsFile.parentFile?.mkdirs()
            // Sibling-then-move, like SwipeNavSettings and the Chromium flags file: a kill mid-write
            // must not leave a truncated file the next launch reports as corrupt. Files.move, not
            // File.renameTo, because the destination exists from the second write on and renameTo
            // fails silently there on Windows.
            val temp = File(settingsFile.parentFile, "${settingsFile.name}.tmp")
            temp.writeText(json.encodeToString(BossPetSettings.serializer(), value))
            java.nio.file.Files.move(
                temp.toPath(),
                settingsFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.UI, "Could not save pet settings", error = e)
        }
    }
}
