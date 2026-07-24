package ai.rever.boss.theme

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persists the user's host theme choice and keeps the live [BossThemeController]
 * in sync. Follows the BOSS settings pattern (JSON in ~/.boss, sync load on
 * init, async save). Desktop-only — the host app ships desktop only.
 */
object AppThemeSettingsManager {
    private val logger = BossLogger.forComponent("AppThemeSettingsManager")
    private val settingsFile = BossDirectories.resolve("app-theme-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _settings = MutableStateFlow(AppThemeSettings())
    val settings: StateFlow<AppThemeSettings> = _settings.asStateFlow()

    init {
        settingsFile.parentFile?.mkdirs()
        loadSync()
    }

    /**
     * Applies the persisted theme to [BossThemeController] so the app opens in
     * the saved look. Call once early in startup, before the first frame is
     * composed. Idempotent — selecting the same theme again just re-sets the id.
     */
    fun ensureInitialized() {
        BossThemeController.select(_settings.value.appThemeId)
    }

    /** Select a theme: applies it live via [BossThemeController] and persists it. */
    fun select(themeId: String) {
        if (BossThemes.all.none { it.id == themeId }) return
        BossThemeController.select(themeId)
        _settings.value = _settings.value.copy(appThemeId = themeId)
        scope.launch { save() }
    }

    private fun loadSync() {
        try {
            if (settingsFile.exists()) {
                _settings.value =
                    json.decodeFromString(
                        AppThemeSettings.serializer(),
                        settingsFile.readText(),
                    )
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load app theme settings, using default", error = e)
            _settings.value = AppThemeSettings()
        }
    }

    private suspend fun save() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.writeText(json.encodeToString(AppThemeSettings.serializer(), _settings.value))
                logger.debug(LogCategory.SYSTEM, "Saved app theme", mapOf("themeId" to _settings.value.appThemeId))
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Failed to save app theme settings", error = e)
            }
        }
}
