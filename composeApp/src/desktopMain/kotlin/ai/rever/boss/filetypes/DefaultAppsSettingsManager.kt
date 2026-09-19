package ai.rever.boss.filetypes

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.backupCorrupt
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user has already been asked about default applications.
 *
 * @property promptShown true once the one-time offer has been put in front of
 *   them. The only thing that keeps this a one-time offer.
 * @property declinedCategories categories they said no to. Kept so a later "Set
 *   all" from Settings does not silently re-claim something they refused, and so
 *   the offer can be narrowed rather than repeated.
 */
@Serializable
internal data class DefaultAppsSettings(
    val promptShown: Boolean = false,
    val declinedCategories: Set<String> = emptySet(),
)

/**
 * Persists the default-applications decisions in `~/.boss/default-apps.json`.
 *
 * Same shape as `ScrollbarSettingsManager` and the other settings managers:
 * synchronous load at init, asynchronous save, defaults on any failure.
 *
 * **Persisted, unlike the missing-plugin declines.** Those are per-session
 * because "not now" is an answer about now and a persisted one would leave no way
 * to be asked again. This is the opposite: an offer to take over the user's file
 * associations must be made once and never again unless they go looking for it.
 * Being asked at every launch is precisely the behaviour that makes people
 * distrust a browser.
 */
internal object DefaultAppsSettingsManager {
    private val logger = BossLogger.forComponent("DefaultAppsSettingsManager")

    /**
     * `internal var` so a test can point it at a temp file, matching
     * `RecentBrowserPagesManager`. There is no other seam: the path is resolved
     * from `BossDirectories`, and a round-trip test that wrote to the real
     * `~/.boss/default-apps.json` would clobber the developer's own answer to the
     * first-run prompt.
     */
    internal var settingsFile = BossDirectories.resolve("default-apps.json")

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val _settings = MutableStateFlow(DefaultAppsSettings())
    val settings: StateFlow<DefaultAppsSettings> = _settings.asStateFlow()

    @Volatile
    private var loaded = false

    private val loadMutex = Mutex()

    /**
     * Reads the file once, on IO. Every reader must await this first.
     *
     * There is deliberately no filesystem work in an `init` block. This object is
     * reached from a composable, so an init doing `mkdirs` and `readText` put a
     * file read on the UI thread at first-window composition - and
     * [shouldOfferPrompt] read before the load would answer from defaults and
     * offer a prompt that had already been made.
     *
     * Idempotent and safe from several windows at once; the mutex is what stops
     * two first-launch windows both deciding the prompt has not been shown.
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return@withLock
            withContext(Dispatchers.IO) { load() }
            loaded = true
        }
    }

    /** Test seam: forget the cached load so the next [ensureLoaded] re-reads. */
    internal fun resetForTest() {
        loaded = false
        _settings.value = DefaultAppsSettings()
    }

    private fun load() {
        try {
            settingsFile.parentFile?.mkdirs()
            if (!settingsFile.exists()) return
            _settings.value = json.decodeFromString<DefaultAppsSettings>(settingsFile.readText())
            logger.debug(
                LogCategory.SYSTEM,
                "Loaded default-apps settings",
                mapOf("promptShown" to _settings.value.promptShown),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settingsFile.backupCorrupt(logger, LogCategory.SYSTEM, e)
            // Defaults, which means the prompt may be offered again. Better than
            // the alternative failure direction: a corrupt file that silently
            // suppressed the offer forever would leave no way to discover the
            // feature at all.
            logger.warn(LogCategory.SYSTEM, "Could not read default-apps settings", error = e)
            _settings.value = DefaultAppsSettings()
        }
    }

    /**
     * True when the one-time offer has not been made yet.
     *
     * Only meaningful after [ensureLoaded]; before it this reads the default and
     * answers true for everybody.
     */
    fun shouldOfferPrompt(): Boolean = !_settings.value.promptShown

    /** Categories the user has refused, so nothing re-claims them behind their back. */
    fun declinedCategories(): Set<String> = _settings.value.declinedCategories

    suspend fun markPromptShown() {
        update { it.copy(promptShown = true) }
    }

    suspend fun markDeclined(categoryIds: Collection<String>) {
        update { it.copy(declinedCategories = it.declinedCategories + categoryIds) }
    }

    suspend fun clearDeclined(categoryIds: Collection<String>) {
        update { it.copy(declinedCategories = it.declinedCategories - categoryIds.toSet()) }
    }

    private suspend fun update(transform: (DefaultAppsSettings) -> DefaultAppsSettings) {
        // `update`, not a read-modify-write on `.value`: markPromptShown and
        // markDeclined can run concurrently (the offer dialog records declines
        // while marking itself shown), and the plain assignment loses one of them.
        _settings.update(transform)
        save()
    }

    private suspend fun save() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.atomicWriteText(json.encodeToString(DefaultAppsSettings.serializer(), _settings.value))
            } catch (e: Exception) {
                // Logged, not surfaced: the decision has already taken effect in
                // this session, and the only consequence of a failed write is
                // being asked again next launch.
                logger.warn(LogCategory.SYSTEM, "Could not save default-apps settings", error = e)
            }
        }
}
