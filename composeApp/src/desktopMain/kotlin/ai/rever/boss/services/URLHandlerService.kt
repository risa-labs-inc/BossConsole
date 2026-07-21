package ai.rever.boss.services

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.components.events.URLEventBus
import ai.rever.boss.utils.WindowFocusManager
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service for handling incoming URLs from the operating system
 *
 * When BOSS is set as the default browser, the OS passes http/https URLs
 * to this service, which creates Fluck browser tabs to display them.
 *
 * URL Flow:
 * 1. User clicks http/https link in another app
 * 2. OS passes URL to BOSS via registered protocol handler
 * 3. DeepLinkHandler receives URL
 * 4. URLHandlerService validates and processes URL
 * 5. New Fluck tab created with URL
 * 6. Tab displayed in active window (or new window if none exist)
 */
actual object URLHandlerService {
    private val logger = BossLogger.forComponent("URLHandlerService")

    // Queue for URLs received before the app is ready
    private val urlQueue = mutableListOf<String>()

    // Flag to track if the app is ready to handle URLs
    @Volatile
    private var isAppReady = false

    // Track active URL processing operations
    // Incremented when a coroutine is launched to process a URL
    // Decremented when the URL event emission completes
    // Uses hybrid AtomicInteger + mutableStateOf for thread safety and Compose reactivity
    private val processingCount = AtomicInteger(0)
    private val _isProcessing = mutableStateOf(false)

    /**
     * Check if there are any URLs queued for processing
     *
     * @return true if URLs are waiting to be processed
     */
    actual fun hasQueuedURLs(): Boolean = urlQueue.isNotEmpty()

    /**
     * Check if URLs are currently being processed
     *
     * Returns true while async URL processing operations are in progress,
     * even after the queue has been cleared. This prevents race conditions
     * when checking if tabs are being created.
     *
     * Returns Compose State to trigger recomposition.
     *
     * @return true if URL processing operations are in progress
     */
    actual fun isProcessingURLs(): Boolean = _isProcessing.value

    /**
     * Mark the app as ready to handle URLs and process any queued URLs
     */
    actual fun markAppReady() {
        isAppReady = true
        processQueuedURLs()
    }

    /**
     * Process all URLs that were queued while app was initializing
     */
    private fun processQueuedURLs() {
        if (urlQueue.isEmpty()) return

        logger.debug(LogCategory.BROWSER, "Processing queued URLs", mapOf("count" to urlQueue.size))
        val urls = urlQueue.toList()
        urlQueue.clear()

        urls.forEach { url ->
            handleURLInternal(url)
        }
    }

    /**
     * Handle an incoming URL from the operating system
     *
     * Creates a new Fluck browser tab with the URL and adds it to
     * an existing window, or creates a new window if needed.
     *
     * If the app is not ready yet, queues the URL for later processing.
     *
     * @param url The http/https URL to open
     */
    actual fun handleURL(url: String) {
        if (!isAppReady) {
            logger.debug(LogCategory.BROWSER, "App not ready, queueing URL", mapOf("url" to url))
            urlQueue.add(url)
            return
        }

        handleURLInternal(url)
    }

    /**
     * Internal URL handler that does the actual processing
     *
     * Validates the URL and emits an event through URLEventBus.
     * All windows listen to this event, and the active window will create a tab.
     *
     * Tracks processing state to prevent race conditions when checking if tabs
     * are being created.
     */
    private fun handleURLInternal(url: String) {
        // Track whether THIS specific invocation incremented the counter
        // Used for thread-safe error handling to avoid decrementing other threads' counts
        var incremented = false

        try {
            logger.debug(LogCategory.BROWSER, "Received URL", mapOf("url" to url))

            // Validate URL
            if (!isValidURL(url)) {
                logger.warn(LogCategory.BROWSER, "Invalid URL", mapOf("url" to url))
                return
            }

            // Bring BOSS window to front BEFORE processing URL
            WindowFocusManager.bringToFront()
            logger.debug(LogCategory.BROWSER, "Brought window to front")

            // Get focused window ID for multi-window support
            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            if (focusedWindowId == null) {
                logger.warn(LogCategory.BROWSER, "No window focused, cannot open URL", mapOf("url" to url))
                return
            }

            // Extract domain for tab title
            val title = extractDomain(url) ?: "Loading..."

            // Increment processing counter BEFORE launching coroutine
            val count = processingCount.incrementAndGet()
            _isProcessing.value = (count > 0)
            incremented = true  // Mark that THIS invocation incremented
            logger.debug(LogCategory.BROWSER, "Processing count incremented", mapOf("count" to count, "isProcessing" to _isProcessing.value))

            // Emit URL open event - focused window will handle it
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    URLEventBus.openURL(url, title, sourceWindowId = focusedWindowId)
                    logger.debug(LogCategory.BROWSER, "Emitted URL open event", mapOf("url" to url, "windowId" to focusedWindowId))
                    
                    // CRITICAL: Wait for tab to actually be created before decrementing
                    // The event emission is instant, but tab creation (splitViewState.openUrlInActivePanel)
                    // is async and takes time. If we decrement immediately, BossApp's state check
                    // will see 0 tabs + isProcessing=false and show New Tab Dialog / load Last Session,
                    // which clears panels and destroys the tab being created.
                    // 
                    // Timeline without delay:
                    // - t=0ms: Event emitted, counter decremented to 0
                    // - t=0ms: Tab creation starts (async)
                    // - t=200ms: BossApp debounce checks: tabs=0, isProcessing=false → shows dialog
                    // - t=250ms: Tab creation completes but gets immediately destroyed
                    //
                    // With 500ms delay:
                    // - t=0ms: Event emitted
                    // - t=0ms: Tab creation starts (async)
                    // - t=200ms: BossApp debounce checks: tabs=0, but isProcessing=true → waits
                    // - t=250ms: Tab creation completes, tabs=1
                    // - t=500ms: Counter decremented, isProcessing=false (tab already exists)
                    delay(500)
                } finally {
                    // Decrement counter after tab has time to be created
                    val count = processingCount.decrementAndGet()
                    _isProcessing.value = (count > 0)
                    logger.debug(LogCategory.BROWSER, "Processing count decremented", mapOf("count" to count, "isProcessing" to _isProcessing.value))
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error handling URL", error = e)
            // Only decrement if THIS specific invocation actually incremented
            // This prevents decrementing other threads' counts in multi-threaded scenarios
            if (incremented) {
                val count = processingCount.decrementAndGet()
                _isProcessing.value = (count > 0)
                logger.debug(LogCategory.BROWSER, "Processing count decremented due to error", mapOf("count" to count, "isProcessing" to _isProcessing.value))
            }
        }
    }

    /**
     * Validate that a URL is acceptable for opening
     *
     * Only allows http:// and https:// URLs for security.
     *
     * @param url The URL to validate
     * @return true if the URL is valid and should be opened
     */
    private fun isValidURL(url: String): Boolean {
        // Only allow http and https URLs
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false
        }

        // Basic URL validation - must have at least a protocol and domain
        try {
            // Simple validation: check for protocol and domain separator
            val protocolEnd = url.indexOf("://")
            if (protocolEnd < 0) return false

            val afterProtocol = url.substring(protocolEnd + 3)
            if (afterProtocol.isEmpty()) return false

            // Must have at least a domain name
            val domainEnd = afterProtocol.indexOfAny(charArrayOf('/', '?', '#'))
            val domain = if (domainEnd >= 0) {
                afterProtocol.substring(0, domainEnd)
            } else {
                afterProtocol
            }

            // Domain must not be empty and should contain at least one character
            return domain.isNotEmpty() && domain.contains(".")
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "URL validation error", error = e)
            return false
        }
    }

    /**
     * Extract domain name from URL for display as tab title
     *
     * Examples:
     * - "https://www.example.com/path" -> "example.com"
     * - "http://github.com/user/repo" -> "github.com"
     *
     * @param url The URL to extract domain from
     * @return The domain name, or null if extraction fails
     */
    private fun extractDomain(url: String): String? {
        return try {
            val protocolEnd = url.indexOf("://")
            if (protocolEnd < 0) return null

            val afterProtocol = url.substring(protocolEnd + 3)
            val domainEnd = afterProtocol.indexOfAny(charArrayOf('/', '?', '#'))

            val fullDomain = if (domainEnd >= 0) {
                afterProtocol.substring(0, domainEnd)
            } else {
                afterProtocol
            }

            // Remove port if present
            val domain = fullDomain.substringBefore(':')

            // Remove "www." prefix for cleaner display
            val cleanDomain = if (domain.startsWith("www.")) {
                domain.substring(4)
            } else {
                domain
            }

            cleanDomain.ifEmpty { null }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Domain extraction error", error = e)
            null
        }
    }

    /**
     * Handle multiple URLs at once
     *
     * Useful if the OS passes multiple URLs to open simultaneously.
     *
     * @param urls List of URLs to open
     */
    actual fun handleURLs(urls: List<String>) {
        urls.forEach { url ->
            handleURL(url)
        }
    }
}
