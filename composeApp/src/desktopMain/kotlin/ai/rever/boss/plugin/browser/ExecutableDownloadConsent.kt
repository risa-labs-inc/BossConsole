package ai.rever.boss.plugin.browser

import ai.rever.boss.platform.FileNameSanitizer
import ai.rever.boss.platform.FileSystemUtils

/** Check both names: a save-dialog rename can add or remove an executable extension. */
internal fun executableDownloadAllowed(
    warnForExecutables: Boolean,
    suggestedFileName: String,
    savedFileName: String,
    confirm: (String) -> Boolean,
): Boolean {
    if (!warnForExecutables) return true
    val executable =
        FileNameSanitizer.isExecutableFile(suggestedFileName) || FileNameSanitizer.isExecutableFile(savedFileName)
    return !executable || confirm(savedFileName)
}

/** No terminal listeners exist yet, so release start-time bookkeeping before answering cancel. */
internal fun cancelPendingDownload(
    savePath: String?,
    downloadId: String,
    downloadUrl: String,
    activeDownloadUrls: MutableSet<String>,
    cancel: () -> Unit,
) {
    savePath?.let { FileSystemUtils.releaseFilePath(it, owner = downloadId) }
    activeDownloadUrls.remove(downloadUrl)
    cancel()
}
