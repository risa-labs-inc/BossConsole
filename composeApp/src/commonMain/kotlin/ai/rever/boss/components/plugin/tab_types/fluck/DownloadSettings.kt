package ai.rever.boss.components.plugin.tab_types.fluck

/**
 * Settings for controlling download behavior.
 *
 * @property alwaysAskWhereToSave If true, always show save dialog for each download
 * @property defaultDownloadDirectory Default directory for auto-saving downloads
 * @property lastUsedDirectory Last directory used for saving (null if never used)
 *
 * The executable-download warning does not live here: it is `BrowserSettings.warnForExecutables`,
 * persisted by `BrowserSettingsManager` and read live at download time. A second, unpersisted
 * copy here would be a switch that silently does nothing.
 */
data class DownloadSettings(
    val alwaysAskWhereToSave: Boolean = false,
    val defaultDownloadDirectory: String = getDefaultDownloadsDirectory(),
    val lastUsedDirectory: String? = null,
)

/**
 * Returns the platform-specific default downloads directory.
 * - Windows: %USERPROFILE%\Downloads
 * - macOS: $HOME/Downloads
 * - Linux: XDG user-dirs or $HOME/Downloads
 */
expect fun getDefaultDownloadsDirectory(): String
