package ai.rever.boss.components.dialogs

import kotlinx.browser.window

/**
 * WASM implementation of URL opening
 */
actual fun openUrlInBrowser(url: String) {
    try {
        window.open(url, "_blank")
        println("WASM: Opened URL in browser: $url")
    } catch (e: Exception) {
        println("WASM: Failed to open URL: ${e.message}")
    }
}
