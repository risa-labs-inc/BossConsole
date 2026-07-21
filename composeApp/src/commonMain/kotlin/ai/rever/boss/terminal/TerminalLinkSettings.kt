package ai.rever.boss.terminal

import kotlinx.serialization.Serializable

/**
 * How to open links clicked in terminal.
 */
@Serializable
enum class TerminalLinkOpenMode {
    /** Always ask the user (show dialog) */
    ALWAYS_ASK,
    /** Open in existing split panel (if splits exist) */
    EXISTING_SPLIT,
    /** Open in vertical split alongside the panel */
    VERTICAL_SPLIT,
    /** Open in horizontal split */
    HORIZONTAL_SPLIT,
    /** Open in new tab (default behavior) */
    NEW_TAB,
    /** Open with the operating system default handler (browser/app) */
    SYSTEM_DEFAULT
}

/**
 * How to select target panel when opening in existing split.
 */
@Serializable
enum class ExistingSplitTargetMode {
    /** Use the most recently active panel (excluding current) */
    MOST_RECENT_ACTIVE,
    /** Use the first available panel that isn't the current one */
    FIRST_AVAILABLE
}

/**
 * Settings for terminal link handling.
 * Persisted to ~/.boss/terminal-link-settings.json
 */
@Serializable
data class TerminalLinkSettings(
    val openMode: TerminalLinkOpenMode = TerminalLinkOpenMode.ALWAYS_ASK,
    val existingSplitTarget: ExistingSplitTargetMode = ExistingSplitTargetMode.MOST_RECENT_ACTIVE
)
