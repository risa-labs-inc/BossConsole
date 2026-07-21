package ai.rever.boss.updater

import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.Version

/**
 * Android implementation of UpdateService
 * Note: Android updates are typically handled through Google Play Store
 */
actual class UpdateService {
    
    actual suspend fun checkForUpdates(): UpdateInfo {
        // For Android, updates are handled by Google Play Store
        // Return no update available
        return UpdateInfo(
            available = false,
            currentVersion = AppVersion.CURRENT,
            latestVersion = AppVersion.CURRENT,
            releaseNotes = "Updates are handled through Google Play Store"
        )
    }
    
    actual suspend fun downloadUpdate(updateInfo: UpdateInfo, onProgress: (progress: Float) -> Unit): String? {
        // Not supported on Android - Play Store handles this
        return null
    }
    
    actual suspend fun installUpdate(downloadPath: String): Boolean {
        // Not supported on Android - Play Store handles this
        return false
    }
    
    actual fun getCurrentPlatform(): String {
        return "Android"
    }
    
    actual fun getExpectedAssetName(version: Version): String {
        // Android apps are distributed as APK/AAB through Play Store
        return "BOSS-${version}.apk"
    }
}
