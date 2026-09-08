package ai.rever.boss.plugin.browser

/**
 * Service interface for creating and managing browser instances.
 *
 * This service provides plugins with access to the host application's
 * JxBrowser infrastructure without exposing JxBrowser types directly.
 *
 * Browser instances are created on-demand and should be disposed when
 * no longer needed to conserve resources.
 *
 * ## Usage Example
 *
 * ```kotlin
 * class MyBrowserPlugin : Plugin {
 *     private var browserService: BrowserService? = null
 *     private var browserHandle: BrowserHandle? = null
 *
 *     override fun register(context: PluginContext) {
 *         browserService = context.browserService
 *     }
 *
 *     suspend fun createBrowser() {
 *         browserHandle = browserService?.createBrowser(
 *             BrowserConfig(url = "https://example.com")
 *         )
 *     }
 *
 *     override fun dispose() {
 *         browserHandle?.dispose()
 *         browserHandle = null
 *         browserService = null
 *     }
 * }
 * ```
 */
interface BrowserService {
    /**
     * Check if the browser service is available.
     *
     * This may return false if:
     * - JxBrowser license is not configured
     * - Browser engine failed to initialize
     * - Platform doesn't support embedded browsers
     *
     * @return true if browsers can be created
     */
    fun isAvailable(): Boolean

    /**
     * Create a new browser instance with the given configuration.
     *
     * This is a suspend function as browser creation may involve
     * async initialization. A managed named profile already leased by an open browser or
     * pending native disposal is given up to ten seconds to become available; creation
     * returns null if it remains busy. A failed native close can retain the lease until restart.
     *
     * @param config Configuration for the browser
     * @return A browser handle, or null if creation failed
     */
    suspend fun createBrowser(config: BrowserConfig): BrowserHandle?

    /**
     * Dispose a browser instance.
     *
     * This is equivalent to calling [BrowserHandle.dispose] but allows
     * batch disposal operations. It requests native close and profile cleanup without
     * awaiting outstanding renderer calls; those finish in host-owned background work.
     * Returning does not guarantee that native resources have been released.
     *
     * If the browser owns a fullscreen rendering surface, disposal may wait
     * briefly for UI-thread detachment. Do not call while holding a lock that
     * the UI thread may need.
     *
     * @param handle The browser handle to dispose
     */
    suspend fun disposeBrowser(handle: BrowserHandle)

    /**
     * Get the current number of active browsers.
     *
     * Useful for debugging and resource monitoring.
     */
    fun getActiveBrowserCount(): Int

    /**
     * Stash a POST body to be replayed on the next browser creation that loads [url].
     *
     * Used by the popup handler to preserve form-submit data across the tab handoff:
     * when a page submits a form with `target="_blank"`, the popup browser carries
     * the POST request, which is captured here. The host then opens a new tab via
     * the usual URL path; when that tab constructs its browser, the stashed body
     * is consumed and replayed via [BrowserConfig.initialPostData].
     *
     * Stashed entries expire after a few seconds and are removed on first consume,
     * so this never affects later navigations to the same URL.
     *
     * Default implementation is a no-op (older hosts simply lose POST bodies on
     * popup-to-tab handoff, matching pre-existing behavior).
     *
     * @param url The destination URL the next browser will load
     * @param postData Bytes of the POST body to replay
     * @param contentType Content-Type header for [postData]
     */
    fun stashPopupPost(
        url: String,
        postData: ByteArray,
        contentType: String,
    ) {
        // Default: no-op for hosts that don't support POST preservation.
    }

    // ---- Optional named-profile management (for isolated/automation profiles) ----
    // General browser tabs don't use these; automation (e.g. RPA) uses them to
    // pre-seed and reuse persistent profiles. Default no-ops so impls/hosts that
    // don't support managed profiles are unaffected.

    /**
     * Create or refresh a persistent named profile and seed [auth] into it (so a
     * later [createBrowser] with the same `profileName` is pre-authenticated).
     *
     * @throws IllegalStateException if an open browser or pending native disposal still
     * holds the profile after a ten-second wait. Retry after the owning browser closes;
     * a failed native close can retain the lease until restart.
     */
    suspend fun seedProfile(
        profileName: String,
        auth: BrowserAuthSpec?,
    ) {}

    /**
     * Delete a persistent named profile. Returns false if unsupported or in use, including
     * pending native disposal. Calling [disposeBrowser] immediately before this method
     * does not guarantee deletion; retry after native cleanup completes.
     */
    fun deleteProfile(profileName: String): Boolean = false

    /** List the persistent named profiles this service manages. */
    fun listProfiles(): List<BrowserProfileInfo> = emptyList()
}
