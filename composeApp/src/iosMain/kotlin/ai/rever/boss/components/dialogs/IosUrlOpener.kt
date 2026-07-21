package ai.rever.boss.components.dialogs

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * iOS implementation of URL opening
 */
@OptIn(ExperimentalForeignApi::class)
actual fun openUrlInBrowser(url: String) {
    try {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl != null && UIApplication.sharedApplication.canOpenURL(nsUrl)) {
            UIApplication.sharedApplication.openURL(nsUrl)
            println("iOS: Opened URL in browser: $url")
        } else {
            println("iOS: Cannot open URL: $url")
        }
    } catch (e: Exception) {
        println("iOS: Failed to open URL: ${e.message}")
    }
}
