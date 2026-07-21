package ai.rever.boss.components.sidebar

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable

private val logger = BossLogger.forComponent("SidebarVisibilitySettings")

/**
 * How the number of plugin icons shown per sidebar slot is limited.
 * Icons beyond the limit collapse into the slot's "More" overflow menu.
 */
@Serializable
enum class SidebarIconLimitMode {
    /** Show as many icons as fit the sidebar's height; overflow the rest. */
    ADAPTIVE,

    /** Show at most [SidebarVisibilitySettings.fixedIconLimit] icons per slot. */
    FIXED,
}

/**
 * Persistent visibility state for sidebar panel icons.
 *
 * [hiddenPanelIds] is the authoritative *hidden* list (allow-by-default):
 * an unknown panel id is treated as visible, so newly-installed plugins
 * appear in the sidebar without the user having to opt them in.
 *
 * [customizeButtonSlotId] is one of the five rendered sidebar icon
 * slots — the section the user has dragged the three-dot customize
 * button into. Persisted as a string id so we don't have to wrestle
 * with polymorphic Panel serialization.
 *
 * [iconLimitMode] / [fixedIconLimit] cap how many plugin icons each slot
 * shows before the rest collapse into the slot's "More" overflow menu.
 * [fixedIconLimit] only applies in [SidebarIconLimitMode.FIXED] mode and
 * is clamped to [FIXED_ICON_LIMIT_RANGE] at the consuming site (the file
 * is hand-editable, so a persisted 0 or 999 must not break layout).
 */
@Serializable
data class SidebarVisibilitySettings(
    val hiddenPanelIds: Set<String> = emptySet(),
    val customizeButtonSlotId: String = SLOT_LEFT_TOP_BOTTOM,
    val iconLimitMode: SidebarIconLimitMode = SidebarIconLimitMode.ADAPTIVE,
    val fixedIconLimit: Int = DEFAULT_FIXED_ICON_LIMIT,
) {
    companion object {
        const val DEFAULT_FIXED_ICON_LIMIT = 5
        val FIXED_ICON_LIMIT_RANGE = 1..20

        const val SLOT_LEFT_TOP_TOP = "left.top.top"
        const val SLOT_LEFT_TOP_BOTTOM = "left.top.bottom"
        const val SLOT_LEFT_BOTTOM = "left.bottom"
        const val SLOT_RIGHT_TOP_TOP = "right.top.top"
        const val SLOT_RIGHT_TOP_BOTTOM = "right.top.bottom"

        val ALL_SLOT_IDS = setOf(
            SLOT_LEFT_TOP_TOP,
            SLOT_LEFT_TOP_BOTTOM,
            SLOT_LEFT_BOTTOM,
            SLOT_RIGHT_TOP_TOP,
            SLOT_RIGHT_TOP_BOTTOM,
        )

        /** Map a [Panel] to one of the rendered sidebar slot ids, or null. */
        fun slotIdFor(panel: Panel): String? = when (panel) {
            left.top.top -> SLOT_LEFT_TOP_TOP
            left.top.bottom -> SLOT_LEFT_TOP_BOTTOM
            left.bottom -> SLOT_LEFT_BOTTOM
            right.top.top -> SLOT_RIGHT_TOP_TOP
            right.top.bottom -> SLOT_RIGHT_TOP_BOTTOM
            else -> null
        }

        /** Resolve a slot id back to its [Panel]; falls back to left.top.bottom. */
        fun panelFor(slotId: String): Panel = when (slotId) {
            SLOT_LEFT_TOP_TOP -> left.top.top
            SLOT_LEFT_TOP_BOTTOM -> left.top.bottom
            SLOT_LEFT_BOTTOM -> left.bottom
            SLOT_RIGHT_TOP_TOP -> right.top.top
            SLOT_RIGHT_TOP_BOTTOM -> right.top.bottom
            else -> {
                // The fallback is forward-compat (a slot id from a newer
                // version of BOSS persisted in the user's settings would
                // otherwise crash). Logging surfaces typos and stale ids
                // in our own code without changing behaviour.
                logger.warn(LogCategory.UI, "Unknown sidebar slot id, falling back to left.top.bottom", mapOf(
                    "slotId" to slotId
                ))
                left.top.bottom
            }
        }

        /**
         * True if [slotId] belongs to the left sidebar column.
         *
         * Prefer this over string heuristics like `startsWith("left")` —
         * the slot-id naming convention is an implementation detail of
         * this class and shouldn't leak into callers.
         */
        fun isLeftSide(slotId: String): Boolean = when (slotId) {
            SLOT_LEFT_TOP_TOP,
            SLOT_LEFT_TOP_BOTTOM,
            SLOT_LEFT_BOTTOM -> true
            SLOT_RIGHT_TOP_TOP,
            SLOT_RIGHT_TOP_BOTTOM -> false
            else -> {
                logger.warn(LogCategory.UI, "Unknown sidebar slot id, treating as left-side", mapOf(
                    "slotId" to slotId
                ))
                true // matches panelFor's fallback
            }
        }
    }
}
