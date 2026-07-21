package ai.rever.boss.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * Manager for terminal link settings.
 * Uses expect/actual pattern for platform-specific persistence.
 */
expect object TerminalLinkSettingsManager {
    /** Current settings as a reactive StateFlow */
    val currentSettings: StateFlow<TerminalLinkSettings>

    /** Save current settings to disk */
    suspend fun saveSettings()

    /** Update settings and persist to disk */
    suspend fun updateSettings(settings: TerminalLinkSettings)

    /** Set the open mode preference */
    suspend fun setOpenMode(mode: TerminalLinkOpenMode)

    /** Set the existing split target mode preference */
    suspend fun setExistingSplitTarget(mode: ExistingSplitTargetMode)

    /** Reset settings to default (ALWAYS_ASK) */
    suspend fun resetToDefault()
}
