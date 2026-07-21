package ai.rever.boss.components.plugin.tab_types.fluck

import androidx.compose.runtime.Composable

actual fun createBrowser(): Any {
    // Android doesn't use JxBrowser - return dummy object
    return Any()
}

actual fun disposeBrowser(browser: Any) {
    // No-op for Android
}

actual fun createBrowserViewState(browser: Any, window: Any?): Any {
    // Android doesn't use JxBrowser - return dummy object
    return Any()
}

actual fun disposeBrowserViewState(browserViewState: Any) {
    // No-op for Android
}

actual fun getBrowserState(
    url: String,
    onOpenInNewTab: ((String) -> Unit)?,
    onBrowserClosed: (() -> Unit)?,
    window: Any?
): Pair<Any, Any>? {
    // Android doesn't support browser state preservation yet
    return null
}

actual suspend fun resetBrowserProfile(): Boolean {
    // No-op for Android - return true as no reset needed
    return true
}

actual fun getEngineGeneration(): Long {
    // Android doesn't use JxBrowser - always return 0
    return 0L
}

actual fun isBrowserValid(browser: Any?): Boolean {
    // Android doesn't use JxBrowser - always return true for non-null
    return browser != null
}

actual fun getEngineInitError(): String? {
    // Android doesn't use JxBrowser
    return null
}

actual fun resetEngineInitialization() {
    // No-op for Android
}

actual fun getMaxInitRetries(): Int = 3  // Default value

actual fun getMaxRecoveryAttempts(): Int = 3  // Default value

@Composable
actual fun collectEngineGeneration(): Long {
    // Android doesn't use JxBrowser - always return 0
    return 0L
}

@Composable
actual fun getCurrentAwtWindow(): Any? {
    // Android doesn't use AWT windows - return null
    return null
}
