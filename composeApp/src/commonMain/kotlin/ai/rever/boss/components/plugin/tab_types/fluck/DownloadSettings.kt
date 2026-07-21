package ai.rever.boss.components.plugin.tab_types.fluck

/**
 * Settings for controlling download behavior.
 *
 * @property alwaysAskWhereToSave If true, always show save dialog for each download
 * @property defaultDownloadDirectory Default directory for auto-saving downloads
 * @property lastUsedDirectory Last directory used for saving (null if never used)
 * @property warnForExecutables If true, show warning before downloading executable files
 */
data class DownloadSettings(
    val alwaysAskWhereToSave: Boolean = false,
    val defaultDownloadDirectory: String = getDefaultDownloadsDirectory(),
    val lastUsedDirectory: String? = null,
    val warnForExecutables: Boolean = true
)

/**
 * Returns the platform-specific default downloads directory.
 * - Windows: %USERPROFILE%\Downloads
 * - macOS: $HOME/Downloads
 * - Linux: XDG user-dirs or $HOME/Downloads
 */
expect fun getDefaultDownloadsDirectory(): String
