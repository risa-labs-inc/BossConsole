package ai.rever.boss.components.sidebar

import kotlinx.coroutines.flow.StateFlow

/**
 * Settings manager for which sidebar panel icons are hidden. Backs the
 * three-dot "Customize sidebar" menu in `BossLeftSideBar`.
 *
 * Reads on startup, writes asynchronously on each toggle, exposes a
 * [StateFlow] so the sidebar recomposes the moment a checkbox flips.
 */
expect object SidebarVisibilitySettingsManager {
    val currentSettings: StateFlow<SidebarVisibilitySettings>

    /**
     * Hide or show a single panel id. Persists asynchronously; the
     * StateFlow updates synchronously so the UI reacts immediately.
     */
    suspend fun setHidden(
        panelId: String,
        hidden: Boolean,
    )

    /** Replace the entire hidden set. */
    suspend fun updateSettings(settings: SidebarVisibilitySettings)

    /**
     * Persist which sidebar slot the customize button lives in. Accepts
     * any value in [SidebarVisibilitySettings.ALL_SLOT_IDS] — left- and
     * right-side slots are both supported (see BossLeftSideBar /
     * BossRightSideBar for the rendered placements). Other values are
     * ignored.
     */
    suspend fun setCustomizeButtonSlot(slotId: String)

    /** Reset to defaults — everything visible. */
    suspend fun resetToDefault()
}
