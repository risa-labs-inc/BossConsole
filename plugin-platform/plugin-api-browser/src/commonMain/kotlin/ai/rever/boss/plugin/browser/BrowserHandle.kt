package ai.rever.boss.plugin.browser

import androidx.compose.runtime.Composable

/**
 * Types of form fields that can be detected.
 */
enum class FormFieldType {
    USERNAME,   // Username or login field
    PASSWORD,   // Password field
    EMAIL,      // Email field
    TEXT,       // Generic text field
    UNKNOWN     // Cannot determine
}

/**
 * Information about a form field detected in the browser.
 * Used for secret auto-fill integration.
 */
data class FormFieldInfo(
    /** Type of the form field (username, password, email, etc.) */
    val fieldType: FormFieldType,
    /** Field name attribute */
    val fieldName: String,
    /** Field id attribute */
    val fieldId: String,
    /** Field placeholder text */
    val fieldPlaceholder: String,
    /** Current field value */
    val fieldValue: String,
    /** Parent form's action URL if in a form */
    val parentFormAction: String?,
    /** HTML input type attribute */
    val inputType: String,
    /** Autocomplete attribute value */
    val autocomplete: String
) {
    fun isPasswordField(): Boolean = fieldType == FormFieldType.PASSWORD
    fun isUsernameField(): Boolean = fieldType == FormFieldType.USERNAME || fieldType == FormFieldType.EMAIL
}

/**
 * Information about the context where a right-click occurred in the browser.
 */
data class BrowserContextMenuInfo(
    /** URL of link if right-clicked on a link, null otherwise */
    val linkUrl: String? = null,
    /** Selected text if any text was selected, null otherwise */
    val selectedText: String? = null,
    /** Whether the click was on an editable element (input field, textarea, etc.) */
    val isEditable: Boolean = false,
    /** Whether there's a video element at the click position */
    val hasVideo: Boolean = false,
    /** Whether there's an image at the click position */
    val hasImage: Boolean = false,
    /** Image URL if right-clicked on an image, null otherwise */
    val imageUrl: String? = null,
    /** Current page URL */
    val pageUrl: String = "",
    /** Current page title */
    val pageTitle: String = "",
    /** Form field info if right-clicked on a form field (for secret auto-fill) */
    val formFieldInfo: FormFieldInfo? = null
)

/**
 * Callback for handling context menu requests from the browser.
 */
typealias ContextMenuCallback = (info: BrowserContextMenuInfo) -> Unit

/**
 * Describes a popup/new-tab navigation, preserving the HTTP method and body.
 *
 * For most popups [postData] is null (the popup is a plain GET). When a page
 * submits a form with `target="_blank"`, [postData] carries the request body
 * so the host can replay the POST in the new tab's initial load (otherwise
 * the destination server would receive a GET and miss the form data).
 */
data class PopupNavigation(
    /** Destination URL of the popup. */
    val url: String,
    /** POST body bytes, or null for a GET navigation. */
    val postData: ByteArray? = null,
    /** Content-Type for [postData] (e.g. "application/x-www-form-urlencoded"). */
    val contentType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PopupNavigation) return false
        if (url != other.url) return false
        if (contentType != other.contentType) return false
        if (postData == null) return other.postData == null
        if (other.postData == null) return false
        return postData.contentEquals(other.postData)
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (postData?.contentHashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}

interface BrowserHandle {
    /**
     * Unique identifier for this browser handle.
     */
    val id: String

    /**
     * Whether this browser handle is still valid.
     *
     * Returns false if:
     * - The browser has been disposed
     * - The browser was closed externally
     * - The browser engine was reinitialized
     */
    val isValid: Boolean

    /**
     * Load a URL in the browser.
     *
     * @param url The URL to load
     */
    suspend fun loadUrl(url: String)

    /**
     * Load a URL and suspend until the page finishes loading (best-effort: returns
     * after a bounded timeout even if load doesn't complete). Default delegates to
     * [loadUrl] without waiting.
     */
    suspend fun loadUrlAndWait(url: String) { loadUrl(url) }

    /**
     * Execute JavaScript in the page's main frame and return its value (or null on
     * error / no frame). Default returns null for handles that don't support it.
     */
    suspend fun executeJavaScript(script: String): Any? = null

    /**
     * Get the current URL.
     *
     * @return The current URL, or empty string if invalid
     */
    fun getCurrentUrl(): String

    /**
     * Get the current page title.
     *
     * @return The current title, or empty string if invalid
     */
    fun getTitle(): String

    /**
     * Add a listener for navigation events.
     *
     * Called when the browser navigates to a new URL.
     *
     * @param listener Callback receiving the new URL
     */
    fun addNavigationListener(listener: (String) -> Unit)

    /**
     * Remove a navigation listener.
     */
    fun removeNavigationListener(listener: (String) -> Unit)

    /**
     * Add a listener for title changes.
     *
     * @param listener Callback receiving the new title
     */
    fun addTitleListener(listener: (String) -> Unit)

    /**
     * Remove a title listener.
     */
    fun removeTitleListener(listener: (String) -> Unit)

    /**
     * Add a listener for favicon changes.
     *
     * @param listener Callback receiving the favicon URL (or null)
     */
    fun addFaviconListener(listener: (String?) -> Unit)

    /**
     * Remove a favicon listener.
     */
    fun removeFaviconListener(listener: (String?) -> Unit)

    /**
     * Navigate back in history.
     */
    fun goBack()

    /**
     * Navigate forward in history.
     */
    fun goForward()

    /**
     * Reload the current page.
     */
    fun reload()

    /**
     * Stop the current page load.
     */
    fun stop()

    /**
     * Check if back navigation is possible.
     */
    fun canGoBack(): Boolean

    /**
     * Check if forward navigation is possible.
     */
    fun canGoForward(): Boolean

    // ============================================================
    // ZOOM CONTROLS
    // ============================================================

    /**
     * Get the current zoom level.
     *
     * @return Zoom level where 1.0 = 100%, 0.5 = 50%, 2.0 = 200%
     */
    fun getZoomLevel(): Double

    /**
     * Set the zoom level.
     *
     * @param level Zoom level where 1.0 = 100%, 0.5 = 50%, 2.0 = 200%
     */
    fun setZoomLevel(level: Double)

    /**
     * Zoom in by one step (typically 10%).
     */
    fun zoomIn()

    /**
     * Zoom out by one step (typically 10%).
     */
    fun zoomOut()

    /**
     * Reset zoom to 100%.
     */
    fun resetZoom()

    /**
     * Add a listener for zoom level changes.
     *
     * @param listener Callback receiving the new zoom level (1.0 = 100%)
     */
    fun addZoomListener(listener: (Double) -> Unit)

    /**
     * Remove a zoom listener.
     */
    fun removeZoomListener(listener: (Double) -> Unit)

    // ============================================================
    // LOADING STATE
    // ============================================================

    /**
     * Check if the browser is currently loading a page.
     *
     * @return true if loading, false otherwise
     */
    fun isLoading(): Boolean

    /**
     * Add a listener for loading state changes.
     *
     * @param listener Callback receiving true when loading starts, false when loading finishes
     */
    fun addLoadingListener(listener: (Boolean) -> Unit)

    /**
     * Remove a loading listener.
     */
    fun removeLoadingListener(listener: (Boolean) -> Unit)

    // ============================================================
    // SECURITY
    // ============================================================

    /**
     * Check if the current page is served over HTTPS.
     *
     * @return true if the URL scheme is "https", false otherwise
     */
    fun isSecure(): Boolean

    // ============================================================
    // CONTEXT MENU
    // ============================================================

    /**
     * Set a callback to be invoked when the user right-clicks in the browser.
     *
     * The callback receives information about the click context (link URL, selected text, etc.)
     * and should display an appropriate context menu.
     *
     * @param callback The callback to invoke on right-click, or null to use default behavior
     */
    fun setContextMenuCallback(callback: ContextMenuCallback?)

    // ============================================================
    // SECRET AUTO-FILL
    // ============================================================

    /**
     * Fill credentials into form fields on the current page.
     *
     * This finds username and password fields and fills them with the provided values.
     * Used for secret manager auto-fill integration.
     *
     * @param username Username to fill
     * @param password Password to fill
     * @param fillBoth If true, fills both username and password. If false, only fills
     *                 the currently focused field based on its type.
     * @return true if credentials were filled successfully, false otherwise
     */
    suspend fun fillCredentials(username: String, password: String, fillBoth: Boolean = true): Boolean

    // ============================================================
    // CLIPBOARD OPERATIONS
    // ============================================================

    /**
     * Copy the currently selected text to the clipboard.
     */
    fun copySelection()

    /**
     * Paste text from the clipboard at the current cursor position.
     */
    fun paste()

    /**
     * Cut the currently selected text to the clipboard.
     */
    fun cut()

    /**
     * Select all text on the page.
     */
    fun selectAll()

    // ============================================================
    // POPUP AND NEW TAB HANDLING
    // ============================================================

    /**
     * Set a callback to be invoked when a link should open in a new tab.
     *
     * This handles:
     * - Cmd+Click (Mac) / Ctrl+Click (Windows/Linux) on links
     * - Links with target="_blank"
     * - window.open() calls from JavaScript
     *
     * @param callback Receives the URL to open in a new tab
     */
    fun setOpenInNewTabCallback(callback: (String) -> Unit)

    /**
     * Set a callback to be invoked when a link should open in a new tab,
     * with full request details (including POST body) preserved.
     *
     * Prefer this over [setOpenInNewTabCallback] when the popup may be the
     * result of a form-submit with `target="_blank"` (e.g. OncoEMR print) —
     * the host must replay the POST body on the new tab's first load,
     * otherwise the destination receives a GET and the server cannot
     * reconstruct the original request.
     *
     * If both callbacks are set, this one wins. If only the legacy one is set,
     * POST bodies are lost (URL-only handoff).
     *
     * Default implementation is a no-op so this method can be safely added
     * without breaking older hosts that compiled against an earlier API; such
     * hosts simply continue dropping POST bodies on popup→tab handoff.
     *
     * @param callback Receives a [PopupNavigation] describing the request.
     */
    fun setOpenInNewTabWithDataCallback(callback: (PopupNavigation) -> Unit) {
        // Default: no-op for hosts that don't support POST preservation.
    }

    // ============================================================
    // PICTURE IN PICTURE
    // ============================================================

    /**
     * Request Picture-in-Picture mode for videos on the current page.
     *
     * This finds the most appropriate video element and toggles PiP mode:
     * - On YouTube: uses the main video player
     * - Single video: uses that video
     * - Multiple videos: uses the largest visible one
     *
     * If PiP is already active, this exits PiP mode.
     */
    fun requestPictureInPicture()

    // ============================================================
    // FULLSCREEN VIDEO SUPPORT
    // ============================================================

    /**
     * Set up fullscreen handling for video content.
     *
     * When web content requests fullscreen (e.g., clicking fullscreen button on a YouTube video),
     * the browser content is moved to a separate fullscreen window. The plugin should display
     * a placeholder in the tab while in fullscreen mode.
     *
     * @param tabId Unique identifier for this tab (used for state tracking)
     * @param onEnterFullscreen Called when the browser enters fullscreen mode.
     *                          The plugin should show a placeholder UI.
     * @param onExitFullscreen Called when the browser exits fullscreen mode.
     *                         The plugin should restore normal browser display.
     */
    fun setFullscreenHandler(
        tabId: String,
        onEnterFullscreen: () -> Unit,
        onExitFullscreen: () -> Unit
    )

    /**
     * Request exit from fullscreen mode.
     *
     * Call this when the user clicks the fullscreen placeholder to return
     * the browser content to the tab.
     */
    fun requestExitFullscreen()

    // ============================================================
    // CO-BROWSE / TAB SHARING (DOM state-sync)
    // ============================================================

    /**
     * Start streaming rrweb DOM-capture events from this tab.
     *
     * Injects the rrweb recorder into the current page (and subsequent
     * navigations / same-origin frames) and registers a page→host bridge. Each
     * captured event is delivered to [onEvent] as a JSON string (an rrweb event:
     * full snapshot, incremental mutation, scroll, input, etc.). The first event
     * after start is a full snapshot; subsequent events are incremental.
     *
     * Used by the browser tab-sharing feature to mirror this tab to a remote
     * viewer. Only one capture should be active across shared tabs at a time —
     * the host switches the active source as the viewer changes focus.
     *
     * Default implementation is a no-op so the method can be added without
     * breaking older hosts; such hosts simply never emit capture events.
     *
     * @param onEvent Receives each rrweb event as a JSON string.
     * @param maskInputs When true, rrweb masks form-input values (maskAllInputs) so typed
     *   content is not streamed. Passwords are masked regardless. Default false.
     */
    fun startCoBrowseCapture(onEvent: (String) -> Unit, maskInputs: Boolean = false) {
        // Default: no-op for hosts that don't support DOM capture.
    }

    /**
     * Stop DOM capture started by [startCoBrowseCapture]: tears down the
     * recorder, the page-load injection hook, and the page→host bridge.
     * Idempotent. Default no-op.
     */
    fun stopCoBrowseCapture() {
        // Default: no-op.
    }

    /**
     * Whether DOM capture is currently active on this handle.
     */
    fun isCoBrowseCapturing(): Boolean = false

    /**
     * Apply one controlling-viewer semantic event to this tab's real page.
     *
     * [eventJson] is a small JSON object describing an action keyed by an rrweb
     * mirror node id, e.g. `{"kind":"click","id":42}`,
     * `{"kind":"input","id":7,"value":"hi"}`, `{"kind":"scroll","id":1,"x":0,"y":600}`.
     * The host resolves the node id against the live rrweb mirror and dispatches
     * the corresponding DOM event.
     *
     * No-op (returns null) unless remote control has been granted via
     * [setCoBrowseControlEnabled]. Returns a short status string such as
     * "ok" / "denied" / "stale", or null if unsupported.
     *
     * @param eventJson JSON describing the semantic control event.
     * @return A status string, or null on no-op / unsupported.
     */
    suspend fun applyCoBrowseControl(eventJson: String): String? = null

    /**
     * Grant or revoke remote control of this tab. When revoked,
     * [applyCoBrowseControl] becomes a no-op and the in-page guard rejects any
     * control event. Default no-op.
     *
     * @param granted true to allow remote control, false to revoke.
     */
    fun setCoBrowseControlEnabled(granted: Boolean) {
        // Default: no-op.
    }

    /**
     * Dispatch a native input event into the browser engine's input pipeline
     * (trusted input — indistinguishable from local user interaction, unlike
     * the synthetic DOM events of [applyCoBrowseControl]).
     *
     * [inputJson] is a small JSON object:
     * - mouse: `{"kind":"down|up|move|drag","x":10,"y":20,"button":0,"clicks":1}`
     *   (button: 0=primary, 1=middle, 2=secondary; x/y in viewport CSS px)
     * - wheel: `{"kind":"wheel","x":10,"y":20,"dx":0,"dy":-120}`
     * - key:   `{"kind":"keydown|keyup","key":"Enter","code":"KeyA","ch":"a",
     *            "shift":false,"ctrl":false,"alt":false,"meta":false}`
     *
     * No-op unless remote control has been granted via
     * [setCoBrowseControlEnabled]. Default no-op.
     *
     * @param inputJson JSON describing the native input event.
     */
    fun dispatchCoBrowseInput(inputJson: String) {
        // Default: no-op.
    }

    // ============================================================
    // DEVELOPER TOOLS
    // ============================================================

    /**
     * Show the browser's developer tools (DevTools).
     *
     * This opens JxBrowser's built-in DevTools window for debugging,
     * inspecting elements, network requests, console, etc.
     */
    fun showDevTools()

    /**
     * Composable content that renders the browser.
     *
     * This should be called within a Compose hierarchy to display
     * the browser content. The browser will fill the available space.
     */
    @Composable
    fun Content()

    /**
     * Dispose this browser handle and release resources.
     *
     * After calling this, [isValid] will return false and
     * all other methods will be no-ops.
     */
    fun dispose()
}
