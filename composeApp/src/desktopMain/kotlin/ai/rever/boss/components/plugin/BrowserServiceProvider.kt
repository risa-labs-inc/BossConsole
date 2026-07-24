package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserServiceImpl
import java.util.concurrent.ConcurrentHashMap

private val windowBrowserServices = ConcurrentHashMap<String, WindowScopedBrowserService>()

/**
 * Desktop implementation of BrowserService provider.
 *
 * Returns a window-scoped view of the BrowserServiceImpl singleton that wraps
 * FluckEngine. The wrapper keeps plugin browser ownership isolated per window.
 */
actual fun getBrowserServiceInstance(windowId: String?): BrowserService? =
    windowId?.let { windowBrowserServices.computeIfAbsent(it, ::WindowScopedBrowserService) }
        ?: BrowserServiceImpl

private class WindowScopedBrowserService(
    private val windowId: String
) : BrowserService by BrowserServiceImpl {
    private val lifecycleLock = Any()
    private var closed = false

    override suspend fun createBrowser(config: BrowserConfig): BrowserHandle? {
        val creationStarted = synchronized(lifecycleLock) {
            if (closed) {
                false
            } else {
                BrowserServiceImpl.tryBeginBrowserCreation(windowId)
            }
        }
        if (!creationStarted) return null

        return try {
            BrowserServiceImpl.createBrowserForWindow(windowId, config)
        } finally {
            BrowserServiceImpl.finishBrowserCreation(windowId)
        }
    }

    override fun getActiveBrowserCount(): Int =
        BrowserServiceImpl.getActiveBrowserCountForWindow(windowId)

    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            BrowserServiceImpl.disposeAllForWindow(windowId)
        }
    }
}

internal actual fun disposePluginBrowsers(windowId: String) {
    windowBrowserServices.remove(windowId)?.close()
        ?: BrowserServiceImpl.disposeAllForWindow(windowId)
}
