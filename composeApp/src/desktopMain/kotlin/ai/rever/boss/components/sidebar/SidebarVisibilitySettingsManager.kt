package ai.rever.boss.components.sidebar

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Desktop persistence for the sidebar customize menu.
 * Layout matches the BOSS settings convention (sync load on init,
 * async save on write). JSON file lives at
 * `~/.boss/sidebar-visibility-settings.json`.
 */
actual object SidebarVisibilitySettingsManager {
    private val logger = BossLogger.forComponent("SidebarVisibilitySettings")
    private val settingsFile = BossDirectories.resolve("sidebar-visibility-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val _currentSettings = MutableStateFlow(SidebarVisibilitySettings())
    actual val currentSettings: StateFlow<SidebarVisibilitySettings> = _currentSettings.asStateFlow()

    init {
        settingsFile.parentFile?.mkdirs()
        loadSettingsSync()
    }

    // No debounce: settings writes are low-frequency (a few per session)
    // and the file is tiny, so the cost of saving on every toggle is
    // trivial. The previous debounced scheduler risked losing writes if
    // the app closed within the debounce window — fine for a 5 s stats
    // file like DashboardStatsManager, but not for user preferences.
    private suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val content =
                    json.encodeToString(
                        SidebarVisibilitySettings.serializer(),
                        _currentSettings.value,
                    )
                settingsFile.writeText(content)
            } catch (e: Exception) {
                // Log the in-memory state's distinguishing fields so the
                // user's report of "my preferences didn't stick" is
                // diagnosable. saveSettings is async and the UI has already
                // shown the change persisted, so a failure here is silent
                // from the user's perspective without this context.
                val snapshot = _currentSettings.value
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to save sidebar visibility",
                    mapOf(
                        "hiddenCount" to snapshot.hiddenPanelIds.size,
                        "customizeSlot" to snapshot.customizeButtonSlotId,
                        "file" to settingsFile.absolutePath,
                    ),
                    error = e,
                )
            }
        }

    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val settings = json.decodeFromString(SidebarVisibilitySettings.serializer(), content)
                _currentSettings.value = settings
                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded sidebar visibility",
                    mapOf(
                        "hiddenCount" to settings.hiddenPanelIds.size,
                    ),
                )
            } else {
                _currentSettings.value = SidebarVisibilitySettings()
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load sidebar visibility, using defaults", error = e)
            _currentSettings.value = SidebarVisibilitySettings()
        }
    }

    actual suspend fun setHidden(
        panelId: String,
        hidden: Boolean,
    ) {
        // MutableStateFlow.update is CAS-based so two rapid toggles on
        // different panel ids can't clobber each other (the read-modify-
        // write version did — both reads would see the same baseline and
        // the second write would lose the first). The block may run more
        // than once under contention; that's fine, it's idempotent.
        _currentSettings.update { current ->
            if (hidden) {
                if (panelId in current.hiddenPanelIds) {
                    current
                } else {
                    current.copy(hiddenPanelIds = current.hiddenPanelIds + panelId)
                }
            } else {
                if (panelId !in current.hiddenPanelIds) {
                    current
                } else {
                    current.copy(hiddenPanelIds = current.hiddenPanelIds - panelId)
                }
            }
        }
        logger.debug(
            LogCategory.UI,
            "Sidebar panel visibility toggled",
            mapOf(
                "panelId" to panelId,
                "hidden" to hidden.toString(),
            ),
        )
        saveSettings()
    }

    actual suspend fun updateSettings(settings: SidebarVisibilitySettings) {
        _currentSettings.update { settings }
        saveSettings()
    }

    actual suspend fun setCustomizeButtonSlot(slotId: String) {
        if (slotId !in SidebarVisibilitySettings.ALL_SLOT_IDS) return
        val changed = _currentSettings.value.customizeButtonSlotId != slotId
        _currentSettings.update { current ->
            if (current.customizeButtonSlotId == slotId) {
                current
            } else {
                current.copy(customizeButtonSlotId = slotId)
            }
        }
        if (changed) {
            logger.debug(
                LogCategory.UI,
                "Customize button slot moved",
                mapOf(
                    "slotId" to slotId,
                ),
            )
        }
        saveSettings()
    }

    actual suspend fun resetToDefault() {
        updateSettings(SidebarVisibilitySettings())
    }
}
