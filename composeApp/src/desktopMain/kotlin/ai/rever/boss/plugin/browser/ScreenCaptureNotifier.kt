package ai.rever.boss.plugin.browser

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import com.teamdev.jxbrowser.browser.callback.StartCaptureSessionCallback
import com.teamdev.jxbrowser.capture.AudioCaptureMode
import com.teamdev.jxbrowser.capture.CaptureSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Event bus for screen capture requests.
 * Bridges JxBrowser's StartCaptureSessionCallback with Compose UI.
 *
 * Shows a custom picker with Tab/Window/Screen tabs.
 */
object ScreenCaptureNotifier {
    private val logger = BossLogger.forComponent("ScreenCaptureNotifier")

    /**
     * Wrapper for a capture source with display info
     */
    data class CaptureSourceItem(
        val name: String,
        val category: Category,
        val index: Int,  // Index in the original JxBrowser list for selection
        val tabInfo: TabInfo? = null,  // Reference to internal tab for favicon loading (browser tabs only)
        val url: String? = null  // URL for high-quality favicon loading (browser tabs only)
    ) {
        enum class Category {
            SCREEN,
            WINDOW,
            BROWSER_TAB
        }

        /** Unique identifier for stable comparison */
        val uniqueId: String
            get() = "${category.name}_$index"
    }

    /**
     * Represents a pending screen capture request
     */
    data class CaptureRequest(
        val requestId: String,
        val screens: List<CaptureSourceItem>,
        val windows: List<CaptureSourceItem>,
        val browsers: List<CaptureSourceItem>
    )

    // Internal storage for callback and sources
    private data class PendingRequest(
        val tell: StartCaptureSessionCallback.Action,
        val sources: CaptureSources
    )

    private val _captureRequest = MutableStateFlow<CaptureRequest?>(null)
    val captureRequest: StateFlow<CaptureRequest?> = _captureRequest.asStateFlow()

    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    // --- Screen-recording permission rationale (shown before the macOS prompt) ---
    private val rationaleSlot = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val _permissionRationale = MutableStateFlow<CompletableDeferred<Boolean>?>(null)
    /** Non-null while an in-app rationale dialog should be shown. */
    val permissionRationale: StateFlow<CompletableDeferred<Boolean>?> = _permissionRationale.asStateFlow()

    /**
     * Block the (JxBrowser) caller thread while an in-app rationale dialog is shown,
     * so the macOS screen-recording prompt is only triggered after the user agrees.
     * Returns true to proceed. Times out to false after [timeoutMs] so a missed
     * dialog never wedges the capture callback.
     */
    fun awaitPermissionRationale(timeoutMs: Long = 60_000): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        if (!rationaleSlot.compareAndSet(null, deferred)) return false // one at a time
        _permissionRationale.value = deferred
        return try {
            runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } } ?: false
        } finally {
            rationaleSlot.set(null)
            _permissionRationale.value = null
        }
    }

    /** Called by the UI when the user responds to the rationale dialog. */
    fun resolvePermissionRationale(proceed: Boolean) {
        rationaleSlot.get()?.complete(proceed)
    }

    /**
     * Called by JxBrowser callback to request screen capture.
     * Shows full picker with Tab/Window/Screen tabs.
     */
    fun requestCapture(
        requestId: String,
        sources: CaptureSources,
        tell: StartCaptureSessionCallback.Action
    ) {
        pendingRequests[requestId] = PendingRequest(tell, sources)

        val totalScreens = sources.screens().size
        val screens = sources.screens().mapIndexed { index, screen ->
            val rawName = screen.name()
            // Replace generic "Screen N" names with friendlier labels
            val displayName = when {
                rawName.isBlank() || rawName.matches(Regex("(?i)screen\\s*\\d+")) -> {
                    if (totalScreens == 1) "Entire Screen"
                    else if (index == 0) "Primary Display"
                    else "Secondary Display${if (totalScreens > 2) " ${index + 1}" else ""}"
                }
                else -> rawName
            }
            CaptureSourceItem(
                name = displayName,
                category = CaptureSourceItem.Category.SCREEN,
                index = index
            )
        }

        val windows = sources.applicationWindows().mapIndexed { index, window ->
            CaptureSourceItem(
                name = window.name().ifBlank { "Window ${index + 1}" },
                category = CaptureSourceItem.Category.WINDOW,
                index = index
            )
        }

        // Collect all internal tabs using TopOfMind pattern for favicon/title enrichment
        val allInternalTabs = mutableListOf<FluckTabInfo>()
        try {
            SplitViewStateRegistry.getAllStates().forEach { (windowId, state) ->
                state.collectAllActiveTabs(null, windowId)
                    .filter { it.tabInfo is FluckTabInfo }
                    .forEach { allInternalTabs.add(it.tabInfo as FluckTabInfo) }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to collect internal tabs", error = e)
        }

        val browsers = sources.browsers().mapIndexed { index, browserSource ->
            val rawName = browserSource.name()

            // Match by title (JxBrowser returns page title as name)
            val matchingTab = allInternalTabs.find { it.title == rawName }

            val tabName = when {
                matchingTab != null -> matchingTab.title
                rawName.isBlank() -> "Loading..."
                rawName.equals("about:blank", ignoreCase = true) -> "New Tab"
                rawName.equals("new tab", ignoreCase = true) -> "New Tab"
                else -> rawName
            }

            CaptureSourceItem(
                name = tabName,
                category = CaptureSourceItem.Category.BROWSER_TAB,
                index = index,
                tabInfo = matchingTab,
                url = matchingTab?.currentUrl
            )
        }

        logger.debug(LogCategory.BROWSER, "Full picker sources", mapOf("screens" to screens.size, "windows" to windows.size, "browsers" to browsers.size))

        _captureRequest.value = CaptureRequest(
            requestId = requestId,
            screens = screens,
            windows = windows,
            browsers = browsers
        )
    }

    /**
     * Called by UI when user selects a source.
     */
    fun selectSource(requestId: String, source: CaptureSourceItem, audioMode: AudioCaptureMode = AudioCaptureMode.CAPTURE) {
        val pending = pendingRequests.remove(requestId)
        if (pending != null) {
            logger.debug(LogCategory.BROWSER, "User selected capture source", mapOf("name" to source.name, "category" to source.category.name))

            when (source.category) {
                CaptureSourceItem.Category.BROWSER_TAB -> {
                    val browsers = pending.sources.browsers()
                    if (source.index < browsers.size) {
                        pending.tell.selectSource(browsers[source.index], audioMode)
                    } else {
                        logger.error(LogCategory.BROWSER, "Invalid browser index", mapOf("index" to source.index, "size" to browsers.size))
                        pending.tell.cancel()
                    }
                }
                CaptureSourceItem.Category.WINDOW -> {
                    val windows = pending.sources.applicationWindows()
                    if (source.index < windows.size) {
                        pending.tell.selectSource(windows[source.index], audioMode)
                    } else {
                        logger.error(LogCategory.BROWSER, "Invalid window index", mapOf("index" to source.index, "size" to windows.size))
                        pending.tell.cancel()
                    }
                }
                CaptureSourceItem.Category.SCREEN -> {
                    val screens = pending.sources.screens()
                    if (source.index < screens.size) {
                        pending.tell.selectSource(screens[source.index], audioMode)
                    } else {
                        logger.error(LogCategory.BROWSER, "Invalid screen index", mapOf("index" to source.index, "size" to screens.size))
                        pending.tell.cancel()
                    }
                }
            }
        } else {
            logger.warn(LogCategory.BROWSER, "No pending request for requestId", mapOf("requestId" to requestId))
        }
        _captureRequest.value = null
    }

    /**
     * Called by UI when user cancels the capture request.
     */
    fun cancel(requestId: String) {
        val pending = pendingRequests.remove(requestId)
        if (pending != null) {
            logger.debug(LogCategory.BROWSER, "User cancelled capture request")
            pending.tell.cancel()
        }
        _captureRequest.value = null
    }

    /**
     * Check if there's a pending request
     */
    fun hasPendingRequest(requestId: String): Boolean {
        return pendingRequests.containsKey(requestId)
    }
}
