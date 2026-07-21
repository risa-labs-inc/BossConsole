package ai.rever.boss.updater

import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.Version

/**
 * WASM implementation of UpdateService
 * Note: Web applications are typically updated by refreshing the page
 */
actual class UpdateService {
    
    actual suspend fun checkForUpdates(): UpdateInfo {
        // For WASM web apps, updates are handled by page refresh
        // Return no update available
        return UpdateInfo(
            available = false,
            currentVersion = AppVersion.CURRENT,
            latestVersion = AppVersion.CURRENT,
            releaseNotes = "Web application updates automatically on page refresh"
        )
    }
    
    actual suspend fun downloadUpdate(updateInfo: UpdateInfo, onProgress: (progress: Float) -> Unit): String? {
        // Not supported on WASM - web apps update automatically
        return null
    }
    
    actual suspend fun installUpdate(downloadPath: String): Boolean {
        // Not supported on WASM - web apps update automatically
        return false
    }
    
    actual fun getCurrentPlatform(): String {
        return "Web"
    }
    
    actual fun getExpectedAssetName(version: Version): String {
        // Web applications don't have downloadable assets
        return "BOSS-${version}-web.js"
    }
}
