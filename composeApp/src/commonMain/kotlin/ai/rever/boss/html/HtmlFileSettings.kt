package ai.rever.boss.html

import kotlinx.serialization.Serializable

/**
 * Preference mode for opening HTML files (.html / .htm).
 */
@Serializable
enum class HtmlFileOpenMode {
    /** Always ask the user (show dialog) */
    ALWAYS_ASK,

    /** Open in code editor as source code */
    EDITOR,

    /** Open rendered in browser tab */
    BROWSER,
}

/**
 * Settings for HTML file handling.
 * Persisted to ~/.boss/html-file-settings.json
 */
@Serializable
data class HtmlFileSettings(
    val openMode: HtmlFileOpenMode = HtmlFileOpenMode.ALWAYS_ASK,
)
