package ai.rever.boss.services

/**
 * URLHandlerService - Platform-specific URL handling
 *
 * Service for handling incoming URLs from the operating system.
 * When BOSS is set as the default browser, the OS passes http/https URLs
 * to this service, which creates Fluck browser tabs to display them.
 */
expect object URLHandlerService {
    /**
     * Check if there are any URLs queued for processing
     *
     * @return true if URLs are waiting to be processed
     */
    fun hasQueuedURLs(): Boolean

    /**
     * Check if URLs are currently being processed
     *
     * Returns true while async URL processing operations are in progress,
     * even after the queue has been cleared. This prevents race conditions
     * when checking if tabs are being created.
     *
     * @return true if URL processing operations are in progress
     */
    fun isProcessingURLs(): Boolean

    /**
     * Mark the app as ready to handle URLs and process any queued URLs
     */
    fun markAppReady()

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
    fun handleURL(url: String)

    /**
     * Handle multiple URLs at once
     *
     * Useful if the OS passes multiple URLs to open simultaneously.
     *
     * @param urls List of URLs to open
     */
    fun handleURLs(urls: List<String>)
}
