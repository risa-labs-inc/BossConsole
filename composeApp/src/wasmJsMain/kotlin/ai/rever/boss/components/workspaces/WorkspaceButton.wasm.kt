package ai.rever.boss.components.workspaces

import kotlinx.browser.window

/**
 * WebAssembly implementation to open workspace directory
 */
actual fun openWorkspaceDirectory(path: String) {
    // In the browser, we can't open a local directory
    // Instead, show an alert with the information
    try {
        window.alert("Workspaces are stored in browser localStorage.\nPrefix: $path")
    } catch (e: Exception) {
        println("Error showing workspace info: ${e.message}")
    }
}