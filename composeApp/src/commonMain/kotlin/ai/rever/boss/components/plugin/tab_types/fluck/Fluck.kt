package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.components.registery.*
import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.tabfullscreen.TabFullscreenStateManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write


// Tab info for dynamic title and icon updates
// Thread Safety: Navigation methods (_currentUrl mutations) use @Synchronized for thread-safe access.
// All callers should ideally be on Main thread, but @Synchronized provides defensive protection.
// Note: This is a regular class (not data class) to provide explicit control over equals() and copy().
// equals() compares id AND display content for Compose change detection; hashCode() is ID-only for HashMap efficiency.
class FluckTabInfo(
    override val id: String,
    override val typeId: TabTypeId,
    private var _title: String,
    private var _icon: ImageVector = Icons.Outlined.Language,
    private var _tabIcon: TabIcon? = null,
    val url: String = "", // Initial URL
    @Volatile private var _currentUrl: String = url, // Current URL being viewed (volatile for visibility)
    val navigationHistory: MutableList<Pair<String, String>> = mutableListOf(), // List of (title, url) pairs
    @Volatile var historyIndex: Int = -1, // Current position in navigation history
    private var _currentZoomLevel: Double = 1.0, // Current zoom level (1.0 = 100%)
    var faviconCacheKey: String? = null // Cache key for persisted favicon
) : TabInfo {
    override val title: String get() = _title

    // On the home page the tab shows its own Home identity instead of the generic
    // browser globe — unless an explicit icon override (_tabIcon via
    // updateIcon/updateTabIcon) is in place.
    private val isOnHomePage: Boolean get() = isHomeUrl(_currentUrl)
    override val icon: ImageVector get() = if (isOnHomePage) Icons.Outlined.Home else _icon
    override val tabIcon: TabIcon? get() = _tabIcon ?: ai.rever.boss.plugin.api.TabIcon.Vector(icon)
    val currentUrl: String @Synchronized get() = _currentUrl
    val currentZoomLevel: Double get() = _currentZoomLevel

    // Explicit equals() based on id AND content that affects display
    // This ensures Compose detects when tab content changes (title, URL, etc.)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is FluckTabInfo) return false

        return id == other.id &&
               _title == other._title &&
               _currentUrl == other._currentUrl &&
               _icon == other._icon &&
               _tabIcon == other._tabIcon &&
               faviconCacheKey == other.faviconCacheKey &&
               _currentZoomLevel == other._currentZoomLevel
    }

    // Keep hashCode based on ID only for HashMap performance
    override fun hashCode(): Int = id.hashCode()

    // Explicit copy() method that creates independent navigationHistory list (fixes Issue #406)
    // Note: id and typeId default to original values but can be overridden for split view use cases
    fun copy(
        id: String = this.id,
        typeId: TabTypeId = this.typeId,
        _title: String = this._title,
        _icon: ImageVector = this._icon,
        _tabIcon: TabIcon? = this._tabIcon,
        url: String = this.url,
        _currentUrl: String = this._currentUrl,
        _currentZoomLevel: Double = this._currentZoomLevel,
        faviconCacheKey: String? = this.faviconCacheKey,
        navigationHistory: MutableList<Pair<String, String>>? = null,
        historyIndex: Int = this.historyIndex
    ): FluckTabInfo {
        val newTab = FluckTabInfo(
            id = id,
            typeId = typeId,
            _title = _title,
            _icon = _icon,
            _tabIcon = _tabIcon,
            url = url,
            _currentUrl = _currentUrl,
            _currentZoomLevel = _currentZoomLevel,
            faviconCacheKey = faviconCacheKey
        )

        // Copy navigation history list to prevent shared reference issues (fixes Issue #406)
        // Pairs are immutable so they're safely shared; we just need independent list instances
        if (navigationHistory != null) {
            newTab.navigationHistory.addAll(navigationHistory)
        } else {
            newTab.navigationHistory.addAll(this.navigationHistory)
        }
        newTab.historyIndex = historyIndex

        return newTab
    }

    fun updateTitle(newTitle: String): FluckTabInfo {
        return copy(_title = newTitle)
    }

    fun updateIcon(newIcon: ImageVector): FluckTabInfo {
        return copy(_icon = newIcon, _tabIcon = ai.rever.boss.plugin.api.TabIcon.Vector(newIcon))
    }

    fun updateTabIcon(newTabIcon: TabIcon): FluckTabInfo {
        return copy(_tabIcon = newTabIcon)
    }

    fun updateFaviconCacheKey(newCacheKey: String?): FluckTabInfo {
        return copy(faviconCacheKey = newCacheKey)
    }

    fun updateZoomLevel(newLevel: Double): FluckTabInfo {
        return copy(_currentZoomLevel = newLevel)
    }

    fun updateNavigation(title: String, url: String): FluckTabInfo {
        // Calculate new history and index WITHOUT mutating
        val newHistory = navigationHistory.toMutableList()
        var newIndex = historyIndex

        // Truncate forward history if needed
        if (newIndex < newHistory.size - 1) {
            while (newHistory.size > newIndex + 1) {
                newHistory.removeAt(newHistory.size - 1)
            }
        }

        // Add new entry if not duplicate
        if (newHistory.isEmpty() || newHistory.lastOrNull()?.second != url) {
            newHistory.add(Pair(title, url))
            newIndex = newHistory.size - 1
        }

        // Track page visit
        RecentBrowserPagesManager.recordPageVisit(url, title, faviconCacheKey)
        DashboardStatsManager.recordPageVisit()

        // Return NEW instance with updated values (no mutation!)
        // Note: We DON'T update _title here because onTitleUpdate handles that separately
        // to avoid race conditions between the two callbacks
        return copy(
            _currentUrl = url,
            navigationHistory = newHistory,
            historyIndex = newIndex
        )
    }

    /**
     * @deprecated Use updateNavigation() instead for immutable updates.
     * This mutable method is kept for backward compatibility but creates inconsistent state update patterns.
     * Prefer: `val updatedTab = tab.updateNavigation(title, url); parent.updateTab(index, updatedTab)`
     */
    @Deprecated(
        message = "Use updateNavigation() for immutable state updates",
        replaceWith = ReplaceWith("updateNavigation(title, url)"),
        level = DeprecationLevel.WARNING
    )
    @Synchronized
    fun navigateToPage(title: String, url: String) {
        // Update current URL
        _currentUrl = url

        // If we're not at the end of history, truncate forward history
        if (historyIndex < navigationHistory.size - 1) {
            // Remove all entries after current index
            while (navigationHistory.size > historyIndex + 1) {
                navigationHistory.removeAt(navigationHistory.size - 1)
            }
        }

        // Don't add duplicate consecutive entries
        if (navigationHistory.isEmpty() || navigationHistory.lastOrNull()?.second != url) {
            navigationHistory.add(Pair(title, url))
            historyIndex = navigationHistory.size - 1
        }

        // Track page visit in dashboard
        RecentBrowserPagesManager.recordPageVisit(url, title, faviconCacheKey)
        DashboardStatsManager.recordPageVisit()
    }

    @Synchronized
    fun navigateBack() {
        if (historyIndex > 0) {
            historyIndex--
            _currentUrl = navigationHistory[historyIndex].second
        }
    }

    @Synchronized
    fun navigateForward() {
        if (historyIndex < navigationHistory.size - 1) {
            historyIndex++
            _currentUrl = navigationHistory[historyIndex].second
        }
    }

    companion object {
        /** Tab title shown while a browser tab is on the home page. */
        const val HOME_TITLE = "Home"

        /**
         * The home (dashboard) state: a blank URL or about:blank renders the
         * dashboard instead of web content. Single definition shared by the
         * tab's icon logic and BossTabUpdateProvider's title/favicon handling
         * so the two can't drift.
         */
        fun isHomeUrl(url: String): Boolean = url.isBlank() || url == "about:blank"
    }
}

// Platform-specific browser creation
expect fun createBrowser(): Any

// Platform-specific browser reset (clears profile, cache, cookies)
// This is a suspend function to avoid blocking the UI thread during I/O operations
expect suspend fun resetBrowserProfile(): Boolean

// Platform-specific engine generation - increments when engine reinitializes
// Browser tabs can use this to detect when their browser instance is stale
expect fun getEngineGeneration(): Long

// Platform-specific browser validity check - returns true if browser is still open and usable
// Used to detect when underlying browser instance has been closed (e.g., engine shutdown)
expect fun isBrowserValid(browser: Any?): Boolean

// Platform-specific engine initialization error message
// Returns user-friendly error message if engine failed to initialize (e.g., license validation, network error)
expect fun getEngineInitError(): String?

// Platform-specific engine initialization reset
// Clears initialization error state to allow retry after fixing network issues
expect fun resetEngineInitialization()

// Platform-specific settings for browser retry/recovery limits (configurable via Settings)
expect fun getMaxInitRetries(): Int
expect fun getMaxRecoveryAttempts(): Int

// Platform-specific composable to observe engine generation changes
@Composable
expect fun collectEngineGeneration(): Long

// Platform-specific browser view state creation
// Returns null if no valid window is available
// window: Optional AWT window to use (from LocalAwtWindow) for correct multi-window support
expect fun createBrowserViewState(browser: Any, window: Any? = null): Any?

// Platform-specific browser view state recreation
// Used when re-acquiring rendering surface after fullscreen exit
// Returns a NEW BrowserViewState instance for the existing browser
expect fun recreateBrowserViewState(browser: Any, window: Any? = null): Any?

// Platform-specific browser view state closing
// Must be called before recreating to release the browser
expect fun closeBrowserViewState(viewState: Any)

// Platform-specific browser disposal
expect fun disposeBrowser(browser: Any)

// Platform-specific browser view state disposal
expect fun disposeBrowserViewState(browserViewState: Any)

// Platform-specific browser state retrieval
// onBrowserClosed is called when the browser is closed (e.g., engine shutdown)
// This enables event-driven recovery instead of polling
// window: Optional AWT window to use (from LocalAwtWindow) for correct multi-window support
expect fun getBrowserState(
    url: String,
    onOpenInNewTab: ((String) -> Unit)? = null,
    onBrowserClosed: (() -> Unit)? = null,
    window: Any? = null
): Pair<Any, Any>?

// Platform-specific function to get the current AWT window from CompositionLocal
// Returns null on non-desktop platforms
@Composable
expect fun getCurrentAwtWindow(): Any?

// Platform-specific FluckTabComponent creation
expect fun createFluckTabComponent(
    config: TabInfo,
    componentContext: ComponentContext,
    onTitleUpdate: (String) -> Unit,
    onIconUpdate: (ImageVector) -> Unit,
    onTabIconUpdate: (TabIcon) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onNavigationUpdate: ((String, String) -> Unit)? = null,
    onFaviconCacheKeyUpdate: ((String?) -> Unit)? = null,
    onCloseTab: (() -> Unit)? = null
): FluckTabComponent

open class FluckTabComponent(
    override val config: TabInfo,
    private val componentContext: ComponentContext,
    private val onTitleUpdate: (String) -> Unit,
    private val onIconUpdate: (ImageVector) -> Unit,
    private val onTabIconUpdate: (TabIcon) -> Unit,
    private val onOpenInNewTab: (String) -> Unit,
    private val onNavigationUpdate: ((String, String) -> Unit)? = null,
    private val onFaviconCacheKeyUpdate: ((String?) -> Unit)? = null,
    private val onCloseTab: (() -> Unit)? = null
) : TabComponentWithUI, ComponentContext by componentContext {
    private val logger = BossLogger.forComponent("FluckTabComponent")

    // Cache the FluckTabInfo cast to avoid repeated casting during recompositions
    private val fluckTabInfo: FluckTabInfo? = config as? FluckTabInfo

    // Dynamically get the URL to load - use currentUrl if available, otherwise initial url
    // This must be a computed property (not val) to reflect navigation changes for recovery/reload
    // Issue #379: Previously was a val, causing stale URL on browser recovery
    private val currentUrlForBrowser: String
        get() = fluckTabInfo?.let {
            it.currentUrl.ifEmpty { it.url }
        } ?: "https://www.risalabs.ai"

    // Browser state will be initialized lazily in Content() - NOT during construction
    // This prevents blocking the UI thread during window initialization
    private var browserError: Throwable? = null
    private var browserState: Pair<Any, Any>? = null
    val browser: Any? get() = browserState?.first
    val browserViewState: Any? get() = browserState?.second

    // Track which engine generation this browser was created with (Issue #351)
    // When engine reinitializes, this becomes stale and browser needs reload
    private var browserEngineGeneration: Long = -1L

    // Thread-safe disposal flag using AtomicBoolean to prevent race conditions
    // between UI thread checks and IO thread disposal
    private val isDisposedAtomic = AtomicBoolean(false)

    // Read-write lock for thread-safe browser access
    // Read lock: Multiple threads can check browser state simultaneously
    // Write lock: Exclusive access during disposal
    // Internal visibility allows JxBrowserCompose to acquire read locks
    internal val browserLock = ReentrantReadWriteLock()

    // Component-scoped coroutine that survives recomposition (Issue #351)
    // Unlike rememberCoroutineScope() which dies when composable leaves composition,
    // this scope lives as long as the Component and is cancelled in dispose()
    private val componentScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Convenience property for backward compatibility
    private val isDisposed: Boolean get() = isDisposedAtomic.get()

    // Retry mechanism for browser initialization (Issue #162)
    private var retryCount = 0
    private val maxRetries: Int get() = getMaxInitRetries()  // Configurable via Settings
    private var retryTrigger by mutableStateOf(0)

    // Recovery loop prevention - track consecutive recovery attempts
    private var recoveryAttempts = 0
    private val maxRecoveryAttempts: Int get() = getMaxRecoveryAttempts()  // Configurable via Settings

    /**
     * Resets browser state to trigger recovery/reinitialization.
     * Call sites must also set localBrowserState = null separately since it's a local Compose state.
     *
     * @return true if recovery was triggered, false if max recovery attempts reached
     */
    private fun resetForRecovery(reason: String): Boolean {
        recoveryAttempts++

        // Prevent infinite recovery loops
        if (recoveryAttempts > maxRecoveryAttempts) {
            logger.error(LogCategory.BROWSER, "Max recovery attempts reached", mapOf("maxAttempts" to maxRecoveryAttempts, "tabId" to config.id))
            browserError = Exception("Browser recovery failed after $maxRecoveryAttempts attempts. Please close and reopen this tab.")
            return false
        }

        logger.info(LogCategory.BROWSER, "Triggering recovery", mapOf("reason" to reason, "tabId" to config.id, "attempt" to "$recoveryAttempts/$maxRecoveryAttempts"))
        browserState = null
        browserError = null
        retryCount = 0
        browserEngineGeneration = -1L
        retryTrigger++
        return true
    }

    /**
     * Reset recovery counter on successful browser initialization.
     * Called when browser is successfully created.
     */
    private fun resetRecoveryCounter() {
        if (recoveryAttempts > 0) {
            logger.info(LogCategory.BROWSER, "Browser recovered successfully, resetting recovery counter", mapOf("tabId" to config.id))
            recoveryAttempts = 0
        }
    }

    // Method to be overridden by platform-specific classes
    open fun reload() {
        // Default implementation does nothing
        // Platform-specific implementations will override this
    }

    open fun zoomIn() {
        // Default implementation does nothing
        // Platform-specific implementations will override this
    }

    open fun zoomOut() {
        // Default implementation does nothing
        // Platform-specific implementations will override this
    }

    open fun actualSize() {
        // Default implementation does nothing
        // Platform-specific implementations will override this
    }

    override val tabTypeInfo = FluckTabType

    @Composable
    override fun Content() {
        // Observe engine generation changes (Issue #351)
        // When engine reinitializes, existing browsers become stale
        val currentEngineGeneration = collectEngineGeneration()

        // Get the current AWT window from CompositionLocal (multi-window fix)
        // This ensures browsers get the correct window handle for their containing window
        val currentWindow = getCurrentAwtWindow()

        // Observe fullscreen exit recreation signal
        // When exiting fullscreen, we need to recreate BrowserViewState to re-acquire rendering surface
        val needsRecreation by TabFullscreenStateManager.needsViewStateRecreation.collectAsState()

        // Local Compose state to trigger recomposition when browser is ready
        // Initialized from class-level browserState which persists across tab switches
        var localBrowserState by remember(config.id) {
            mutableStateOf(this@FluckTabComponent.browserState)
        }

        // Handle BrowserViewState recreation after fullscreen exit
        // JxBrowser can only have one active rendering surface per browser instance.
        // After fullscreen exit, the Swing BrowserView releases the surface, but the
        // existing Compose BrowserViewState doesn't automatically re-acquire it.
        // Creating a fresh BrowserViewState forces JxBrowser to establish a new rendering connection.
        LaunchedEffect(needsRecreation) {
            if (needsRecreation == config.id) {
                browserState?.let { (browser, oldViewState) ->
                    logger.info(LogCategory.BROWSER, "Recreating BrowserViewState after fullscreen exit", mapOf("tabId" to config.id))

                    // Close the old BrowserViewState first to release the browser
                    closeBrowserViewState(oldViewState)

                    val newViewState = recreateBrowserViewState(browser, currentWindow)
                    if (newViewState != null) {
                        this@FluckTabComponent.browserState = Pair(browser, newViewState)
                        localBrowserState = this@FluckTabComponent.browserState
                        logger.info(LogCategory.BROWSER, "BrowserViewState recreated successfully", mapOf("tabId" to config.id))
                    } else {
                        logger.warn(LogCategory.BROWSER, "Failed to recreate BrowserViewState", mapOf("tabId" to config.id))
                    }
                    TabFullscreenStateManager.clearRecreationSignal()
                }
            }
        }

        // Detect engine reinitialization and invalidate stale browser (Issue #351)
        LaunchedEffect(currentEngineGeneration) {
            if (browserEngineGeneration >= 0 && currentEngineGeneration > browserEngineGeneration) {
                // Engine was reinitialized - our browser is stale
                if (resetForRecovery("Engine generation changed from $browserEngineGeneration to $currentEngineGeneration")) {
                    localBrowserState = null
                }
            }
        }

        // Initialize browser only once - check class-level browserState
        // Retry mechanism: LaunchedEffect re-runs when retryTrigger changes (Issue #162)
        LaunchedEffect(config.id, retryTrigger) {
            // Early exit if already disposed (Issue #351)
            // This prevents "coroutine scope left the composition" errors during session restore
            if (isDisposed) {
                logger.warn(LogCategory.BROWSER, "Skipping browser init - tab already disposed", mapOf("tabId" to config.id))
                return@LaunchedEffect
            }

            if (this@FluckTabComponent.browserState == null && browserError == null) {
                // Check if we should retry
                if (retryCount >= maxRetries) {
                    logger.error(LogCategory.BROWSER, "Max retries exhausted", mapOf("maxRetries" to maxRetries, "tabId" to config.id))
                    browserError = Exception("Failed to initialize browser after $maxRetries attempts")
                    return@LaunchedEffect
                }

                try {
                    // Exponential backoff: 100ms, 200ms, 400ms
                    val delayMs = 100L * (1 shl retryCount)

                    if (retryCount > 0) {
                        logger.info(LogCategory.BROWSER, "Browser retry attempt", mapOf("attempt" to "${retryCount + 1}/$maxRetries", "tabId" to config.id, "delayMs" to delayMs))
                    }

                    kotlinx.coroutines.delay(delayMs)

                    // Check again after delay - tab may have been disposed during wait (Issue #351)
                    if (isDisposed) {
                        logger.warn(LogCategory.BROWSER, "Tab disposed during init delay", mapOf("tabId" to config.id))
                        return@LaunchedEffect
                    }

                    // Pass callbacks to configure popup handler and browser close detection
                    // OAuth popups with dimensions will be real popups, regular links will be tabs
                    // onBrowserClosed triggers recovery when browser is closed (event-driven, no polling)
                    // Pass currentWindow (from LocalAwtWindow) to ensure correct window handle for multi-window
                    val state = getBrowserState(
                        url = currentUrlForBrowser,
                        onOpenInNewTab = onOpenInNewTab,
                        onBrowserClosed = {
                            // Browser was closed - trigger recovery only if tab is not being disposed
                            // This prevents recovery when user intentionally closes the tab
                            // Note: This callback runs on JxBrowser's thread, so we dispatch to Main
                            // for thread-safe Compose state updates
                            if (!isDisposed) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    if (resetForRecovery("Browser closed unexpectedly")) {
                                        localBrowserState = null
                                    }
                                }
                            } else {
                                logger.debug(LogCategory.BROWSER, "Browser closed for disposed tab, skipping recovery", mapOf("tabId" to config.id))
                            }
                        },
                        window = currentWindow
                    )

                    if (state != null) {
                        this@FluckTabComponent.browserState = state
                        localBrowserState = state  // Update local state to trigger recomposition

                        // Track which engine generation this browser was created with (Issue #351)
                        browserEngineGeneration = getEngineGeneration()

                        if (retryCount > 0) {
                            logger.info(LogCategory.BROWSER, "Browser retry succeeded", mapOf("attempt" to "${retryCount + 1}/$maxRetries", "tabId" to config.id))
                        }

                        // Reset retry count and recovery counter on success
                        retryCount = 0
                        resetRecoveryCounter()
                    } else {
                        // State creation failed, increment retry and try again
                        retryCount++

                        // Check if there's a known engine initialization error
                        val engineError = getEngineInitError()
                        if (engineError != null) {
                            logger.warn(LogCategory.BROWSER, "Browser init failed", mapOf("attempt" to "$retryCount/$maxRetries", "error" to engineError))
                        } else {
                            logger.warn(LogCategory.BROWSER, "Browser init failed - window not ready", mapOf("attempt" to "$retryCount/$maxRetries"))
                        }

                        if (retryCount < maxRetries) {
                            // Trigger retry by incrementing retryTrigger
                            retryTrigger++
                        } else {
                            // Max retries reached - use engine error if available for better feedback
                            browserError = if (engineError != null) {
                                Exception(engineError)
                            } else {
                                Exception("Could not initialize browser after $maxRetries attempts - window not ready")
                            }
                        }
                    }
                } catch (e: Exception) {
                    retryCount++
                    logger.warn(LogCategory.BROWSER, "Browser retry failed", mapOf("attempt" to "$retryCount/$maxRetries", "error" to (e.message ?: "unknown")))

                    if (retryCount < maxRetries) {
                        // Trigger retry by incrementing retryTrigger
                        retryTrigger++
                    } else {
                        // Max retries reached - check for engine error first
                        val engineError = getEngineInitError()
                        logger.error(LogCategory.BROWSER, "Max retries exhausted", mapOf("tabId" to config.id, "error" to (engineError ?: e.message ?: "unknown")))
                        browserError = if (engineError != null) {
                            Exception(engineError)
                        } else {
                            e
                        }
                    }
                }
            } else if (this@FluckTabComponent.browserState != null) {
                // Browser already exists (tab switch), verify it's still valid before using
                val existingBrowser = this@FluckTabComponent.browserState?.first
                if (existingBrowser != null && isBrowserValid(existingBrowser)) {
                    localBrowserState = this@FluckTabComponent.browserState
                } else {
                    // Browser became invalid while tab was inactive - trigger recovery
                    if (resetForRecovery("Browser invalid on tab switch")) {
                        localBrowserState = null
                    }
                }
            }
        }

        if (!isDisposed) {
            // Capture localBrowserState in a local val to prevent race conditions
            // This ensures thread-safe access - the value won't change during the when block
            val currentBrowserState = localBrowserState

            when {
                browserError != null -> {
                    // Show error message instead of browser with retry/reset options (Issue #162)
                    BrowserErrorView(
                        error = browserError!!,
                        url = currentUrlForBrowser,
                        retryCount = retryCount,
                        maxRetries = maxRetries,
                        onRetry = {
                            // Clear error and trigger retry
                            browserError = null
                            retryTrigger++
                        },
                        onReset = {
                            // Full reset: clear error, reset counter, clear browser state
                            // Also reset engine initialization state to allow fresh retry (Issue #358)
                            resetEngineInitialization()
                            browserError = null
                            retryCount = 0
                            this@FluckTabComponent.browserState = null
                            retryTrigger++
                        },
                        onResetBrowser = {
                            // Reset browser profile to fix persistent issues (Issue #340)
                            // Use componentScope to survive recomposition (Issue #351)
                            componentScope.launch {
                                val success = resetBrowserProfile()
                                if (success) {
                                    // Clear error and retry after browser reset
                                    browserError = null
                                    retryCount = 0
                                    this@FluckTabComponent.browserState = null
                                    retryTrigger++
                                }
                            }
                        },
                        onRetryEngine = {
                            // Reset engine initialization state to allow fresh retry (network error recovery)
                            // This clears the failed initialization flag so engine can attempt to start again
                            resetEngineInitialization()
                            browserError = null
                            retryCount = 0
                            this@FluckTabComponent.browserState = null
                            retryTrigger++
                        }
                    )
                }
                currentBrowserState != null && isBrowserValid(currentBrowserState.first) -> {
                    val browser = currentBrowserState.first
                    val browserViewState = currentBrowserState.second

                    // Wrap FluckView in key() to ensure proper state isolation per browser instance
                    // This prevents URL bar state from being shared across tabs (fixes #151)
                    key(browser) {
                        FluckView(
                            fileId = config.id,
                            content = currentUrlForBrowser,
                            browser = browser,
                            browserViewState = browserViewState,
                            browserLock = browserLock,
                            onContentChange = { }, // Not used for browser
                            onTitleChange = onTitleUpdate,
                            onIconChange = onIconUpdate,
                            onTabIconUpdate = onTabIconUpdate,
                            onOpenInNewTab = onOpenInNewTab,
                            onNavigationUpdate = onNavigationUpdate,
                            onNavigationStateChange = { isBack ->
                                // Handle back/forward navigation
                                if (config is FluckTabInfo) {
                                    if (isBack) {
                                        (config as? FluckTabInfo)?.navigateBack()
                                    } else {
                                        (config as? FluckTabInfo)?.navigateForward()
                                    }
                                }
                            },
                            onFaviconCached = { cacheKey ->
                                // Update favicon cache key through proper callback (Issue #160)
                                onFaviconCacheKeyUpdate?.invoke(cacheKey)
                            },
                            onCloseTab = onCloseTab
                        )
                    }
                }
                currentBrowserState != null && !isBrowserValid(currentBrowserState.first) -> {
                    // Browser exists but is invalid (Issue #351)
                    // Trigger immediate recovery
                    LaunchedEffect(Unit) {
                        if (resetForRecovery("Browser invalid at render")) {
                            localBrowserState = null
                        }
                    }
                    // Show recovery message (or error if max attempts reached)
                    if (browserError != null) {
                        BrowserErrorView(
                            error = browserError!!,
                            url = currentUrlForBrowser,
                            retryCount = maxRecoveryAttempts,
                            maxRetries = maxRecoveryAttempts,
                            onReset = {
                                // Allow user to reset and try again
                                browserError = null
                                recoveryAttempts = 0
                                retryCount = 0
                                retryTrigger++
                            }
                        )
                    } else {
                        BrowserRecoveryView(url = currentUrlForBrowser)
                    }
                }
                else -> {
                    // Loading state - show URL and give user control
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .background(Color(0xFF1E1E1E))
                    ) {
                        // URL bar with controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2D2D2D))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Stop button - closes the tab
                            IconButton(
                                onClick = { onCloseTab?.invoke() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Stop and close tab",
                                    tint = Color(0xFF999999),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Retry button
                            IconButton(
                                onClick = {
                                    // Retry loading
                                    retryCount = 0
                                    browserError = null
                                    retryTrigger++
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = Color(0xFF999999),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // URL being loaded with spinner
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color(0xFF1E1E1E))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = Color(0xFF4A90E2)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = currentUrlForBrowser.ifEmpty { "New Tab" },
                                    fontSize = 13.sp,
                                    color = Color(0xFFAAAAAA),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun dispose() {
        // Use compareAndSet for atomic thread-safe disposal flag update
        if (isDisposedAtomic.compareAndSet(false, true)) {
            // Cancel component scope first to stop any pending coroutines (Issue #351)
            // This prevents "coroutine scope left the composition" errors during session restore
            componentScope.cancel()

            // Dispose the browser and view state on background thread to avoid blocking UI
            // The composable's DisposableEffect and disposal guards will handle cleanup coordination
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // First: Dispose view state with write lock (quick operation)
                    browserLock.write {
                        browserViewState?.let { disposeBrowserViewState(it) }
                    }

                    // Delay OUTSIDE the lock to prevent blocking read lock acquisitions
                    // During this delay, event handlers and RPA polling can still acquire read locks
                    // This allows JxBrowser's internal RPC queue to drain without freezing other operations
                    // Issue #255: 150ms delay prevents race condition in SharedMemoryTransport
                    delay(150)

                    // Finally: Dispose browser with write lock (ensures exclusive access for closure)
                    browserLock.write {
                        browser?.let { disposeBrowser(it) }
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Error disposing browser", mapOf("error" to (e.message ?: "unknown")))
                }
            }
        }
    }

    /**
     * Synchronously dispose the browser tab.
     * Called when the window is closing to ensure JxBrowser instances
     * are fully closed before AWT window destruction.
     *
     * Unlike dispose(), this method blocks until the browser is closed.
     * This prevents crashes when closing parent windows by ensuring browsers
     * are fully disposed before the AWT window handle is destroyed.
     */
    fun disposeBlocking() {
        // Use compareAndSet for atomic thread-safe disposal flag update
        if (isDisposedAtomic.compareAndSet(false, true)) {
            // Cancel component scope first to stop any pending coroutines
            componentScope.cancel()

            // Synchronously dispose browser resources
            // This MUST complete before returning to prevent crashes
            try {
                // First: Dispose view state with write lock
                browserLock.write {
                    browserViewState?.let { disposeBrowserViewState(it) }
                }

                // Close the browser synchronously - this is the critical part
                // JxBrowser's close() is synchronous and will stop all rendering
                // Note: We skip the 150ms delay used in async dispose() since
                // the window is being destroyed anyway
                browserLock.write {
                    browser?.let { disposeBrowser(it) }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error disposing browser (blocking)", mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }
}

@Composable
fun BrowserErrorView(
    error: Throwable,
    url: String,
    retryCount: Int = 0,
    maxRetries: Int = 3,
    onRetry: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    onResetBrowser: (() -> Unit)? = null,
    onRetryEngine: (() -> Unit)? = null
) {
    // Detect if this is a network/license validation error
    val errorMessage = error.message?.lowercase() ?: ""
    val isNetworkError = errorMessage.contains("license") ||
            errorMessage.contains("validation") ||
            errorMessage.contains("network") ||
            errorMessage.contains("connect") ||
            errorMessage.contains("internet") ||
            errorMessage.contains("timeout") ||
            errorMessage.contains("unreachable")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp,
            backgroundColor = Color(0xFF2B2D30)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = "Error",
                    tint = if (isNetworkError) Color(0xFFFFB347) else Color(0xFFFF6B6B),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isNetworkError) "Connection Required" else "Browser Not Available",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Show prominent network message for connection errors
                if (isNetworkError) {
                    Text(
                        text = "Please check your internet connection",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFFB347),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "The browser requires an internet connection for license validation.",
                        fontSize = 14.sp,
                        color = Color(0xFFCCCCCC),
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "Unable to initialize the web browser component.",
                        fontSize = 14.sp,
                        color = Color(0xFFCCCCCC)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "URL: $url",
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Error: ${error.message ?: error.toString()}",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center
                )

                // Show retry progress if retries were attempted
                if (retryCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Attempted $retryCount/$maxRetries times with exponential backoff",
                        fontSize = 12.sp,
                        color = Color(0xFF888888),
                        fontStyle = FontStyle.Italic
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // For network errors, show a prominent "Try Again" button that resets engine state
                if (isNetworkError && onRetryEngine != null) {
                    Button(
                        onClick = onRetryEngine,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4A90E2))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Try Again",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Try Again")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Ensure you have an active internet connection, then try again",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )
                } else if (retryCount < maxRetries && onRetry != null) {
                    // Still have retries left - show Retry button
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4A90E2))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry Loading (Attempt ${retryCount + 1}/$maxRetries)")
                    }
                } else if (retryCount >= maxRetries && onReset != null) {
                    // Max retries exhausted - show Reset Tab button
                    Button(
                        onClick = onReset,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE2724A))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reset Tab")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Resets retry counter and attempts to load the browser again",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )

                    // Show Reset Browser option for persistent issues
                    if (onResetBrowser != null) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "If the issue persists, try resetting the browser:",
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = onResetBrowser,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE05555))
                        ) {
                            Text("Reset Browser", color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "This clears browser cache, cookies, and sessions",
                            fontSize = 10.sp,
                            color = Color(0xFF777777),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // No callbacks provided - show fallback message
                    Text(
                        text = "Try using the code editor or terminal tabs instead.",
                        fontSize = 14.sp,
                        color = Color(0xFFCCCCCC)
                    )
                }
            }
        }
    }
}

/**
 * View shown when browser becomes invalid and is being recovered.
 * Shows a friendly message while the browser reinitializes.
 */
@Composable
fun BrowserRecoveryView(url: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp,
            backgroundColor = Color(0xFF2B2D30)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color(0xFF4A90E2)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Reconnecting Browser",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "The browser connection was lost. Reinitializing...",
                    fontSize = 14.sp,
                    color = Color(0xFFCCCCCC),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = url,
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

