package ai.rever.boss.updater

import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.Version

/**
 * iOS implementation of UpdateService
 * Note: iOS updates are typically handled through App Store
 */
actual class UpdateService {
    
    actual suspend fun checkForUpdates(): UpdateInfo {
        // For iOS, updates are handled by App Store
        // Return no update available
        return UpdateInfo(
            available = false,
            currentVersion = AppVersion.CURRENT,
            latestVersion = AppVersion.CURRENT,
            releaseNotes = "Updates are handled through App Store"
        )
    }
    
    actual suspend fun downloadUpdate(updateInfo: UpdateInfo, onProgress: (progress: Float) -> Unit): String? {
        // Not supported on iOS - App Store handles this
        return null
    }
    
    actual suspend fun installUpdate(downloadPath: String): Boolean {
        // Not supported on iOS - App Store handles this
        return false
    }
    
    actual fun getCurrentPlatform(): String {
        return "iOS"
    }
    
    actual fun getExpectedAssetName(version: Version): String {
        // iOS apps are distributed through App Store
        return "BOSS-${version}.ipa"
    }
}
