package ai.rever.boss.plugin.browser

import androidx.compose.runtime.Composable

/**
 * Types of form fields that can be detected.
 */
enum class FormFieldType {
    USERNAME, // Username or login field
    PASSWORD, // Password field
    EMAIL, // Email field
    TEXT, // Generic text field
    UNKNOWN, // Cannot determine
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
    val autocomplete: String,
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
    val formFieldInfo: FormFieldInfo? = null,
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
    val contentType: String? = null,
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

/**
 * The name a page-event script's bridge is bound to: a **function parameter in its own scope**, not
 * a property on `window`.
 *
 * The host wraps the script it is given and passes the bridge in, so the script just uses the name:
 *
 * ```
 * document.addEventListener('submit', function () {
 *     __bossPageEvent.emit(JSON.stringify({ kind: 'submit' }));   // one String argument
 * }, true);
 * ```
 *
 * It is an OBJECT with a single `emit(string)` method, not a callable. **Nothing is left on
 * `window`** - `window.__bossPageEvent` is `undefined`, and a script written against it gets a
 * TypeError swallowed inside the wrapper, which is a channel that silently never fires.
 *
 * Why a parameter: a documented global would be reachable by every script on the page, and for a
 * channel whose first consumer posts a password that means a page could replace it and receive the
 * payload, forge events into the plugin's sink, or detect BOSS by probing for the name. A binding in
 * the script's own scope has none of those. The host does use a `window` slot to hand the object
 * over, under a random per-injection name it deletes in the same evaluation - see `PageEventScripts`.
 *
 * A `const val`, so the literal is compiled into both sides and there is no runtime lookup to get
 * wrong - which also means renaming it would be a compile error at no consumer and a silently dead
 * bridge at every one. See [BrowserHandle.setPageEventScript].
 */
const val PAGE_EVENT_BRIDGE = "__bossPageEvent"

/**
 * The method a page-event script calls on its bridge: `__bossPageEvent.emit(payload)`.
 *
 * A constant for the same reason [PAGE_EVENT_BRIDGE] is: the name appears only inside a JavaScript
 * string, so renaming it host-side is a compile error at no consumer, and every already-built plugin
 * would post into a method that no longer exists with nothing in any log.
 */
const val PAGE_EVENT_EMIT = "emit"

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
    suspend fun loadUrlAndWait(url: String) {
        loadUrl(url)
    }

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
    // AUDIO PLAYBACK STATE
    // ============================================================

    /**
     * Check whether this browser is currently producing sound.
     *
     * Backed by Chromium's own playback state (issue #308) — not a poll — so
     * it is accurate for background tabs and inactive panels alike.
     *
     * @return true if audio is currently playing
     */
    fun isPlayingAudio(): Boolean = false

    /**
     * Add a listener for audio playback state changes.
     *
     * Fired the moment Chromium starts or stops producing sound, and replayed
     * with the current state on registration (same contract as
     * [addLoadingListener] — a listener attached while a video is already
     * playing must not sit at "silent" until the next start event).
     *
     * @param listener Callback receiving true when playback starts, false when it stops
     */
    fun addAudioPlayingListener(listener: (Boolean) -> Unit) {}

    /**
     * Remove an audio playback listener.
     */
    fun removeAudioPlayingListener(listener: (Boolean) -> Unit) {}

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
     * Invoked on a background thread — a browser-engine thread for most targets, and a
     * host worker for editable ones, where the menu is delivered once the form-field
     * detail behind auto-fill resolves (or shortly after, if it doesn't). Dispatch to the
     * UI thread before touching UI state, and don't assume the pointer is still where the
     * click happened.
     *
     * @param callback The callback to invoke on right-click. Passing null does not restore
     *   a built-in menu — there isn't one: right-clicking then produces no menu at all.
     */
    fun setContextMenuCallback(callback: ContextMenuCallback?)

    // ============================================================
    // SECRET AUTO-FILL (deprecated, no-op)
    // ============================================================

    /**
     * Fill credentials into form fields on the current page.
     *
     * **Deprecated and now a no-op that always returns false. Fill through [executeJavaScript]
     * instead, targeting the element you already identified.** Scheduled for removal.
     *
     * The signature is the problem: it cannot say WHICH field, so the host had to guess, and the
     * guess was wrong in the ways real login pages are actually built. Measured on
     * `accounts.google.com`: that page carries a `display: none` password input and the guess took
     * the first `input[type="password"]` in the DOM - that one - so the password went into a hidden
     * field and the screen did not change. The visible identifier box was missed entirely, because
     * `[autocomplete="username"]` is an exact attribute match and the field declares
     * `autocomplete="username webauthn"`, a space-separated token list. The last-resort strategy
     * was "first text input in a `<form>` containing a password", and that page has no `<form>`.
     *
     * Every caller already knows what the guess was reconstructing: a right-click menu was raised
     * on a specific field, an autofill suggestion is anchored to a specific box. Filling that
     * element beats any heuristic, so credential filling belongs on the caller's side of the
     * boundary along with the rules for deciding a field is real. See fluck-browser's
     * `CredentialFill`.
     *
     * **This copy is the one that runs.** `BrowserHandle` is host-compiled and served parent-first,
     * so the boss-plugin-api jar's copy is shadowed - the behaviour change lands here, with this
     * release, and consumers gate on `minBossVersion`.
     *
     * **A no-op body rather than deletion, deliberately.** Removing the declaration would make
     * every caller compiled against an older api throw `NoSuchMethodError` at the call site.
     * fluck-browser 1.2.19 guards its call and would degrade to nothing, but 1.2.18 and earlier
     * call it bare inside a `launch`, where an `Error` reaches the coroutine uncaught and the host
     * tears the whole plugin down - closing every open browser tab. Returning false gives those
     * builds a silent no-op, which is also strictly better than what they do today: writing a
     * password into a hidden input. The declaration goes in a later release, once 1.2.19+ has
     * propagated.
     *
     * @return always false
     */
    @Deprecated(
        message =
            "Cannot express which field to fill, so the host had to guess and guessed wrong on " +
                "real login pages. Now a no-op returning false; fill via executeJavaScript, " +
                "targeting the element you already identified. Scheduled for removal.",
        // Deliberately no ReplaceWith: executeJavaScript is not a drop-in for this, and an
        // auto-applied quick fix would produce code that compiles and fills nothing.
    )
    suspend fun fillCredentials(
        username: String,
        password: String,
        fillBoth: Boolean = true,
    ): Boolean = false

    // ============================================================
    // PAGE EVENT CHANNEL
    // ============================================================

    /**
     * Install [script] into every main-frame document as its context is created, and deliver each
     * [PAGE_EVENT_BRIDGE]`.emit(json)` call it makes to [onEvent].
     *
     * [PAGE_EVENT_BRIDGE] is a **parameter passed to the script**, not a property on `window` - see
     * its KDoc. Writing `window.__bossPageEvent.emit(...)` gets `undefined`, a TypeError the wrapper
     * swallows, and a channel that silently never fires.
     *
     * **How the script is evaluated.** It is wrapped, and the bridge is passed in as a parameter
     * named [PAGE_EVENT_BRIDGE] - `(function (__bossPageEvent) { your script })(bridge)`. So a
     * top-level `return` is legal, and there is nothing to look for on `window`.
     *
     * **The host injects; the caller decides what to look for.** The host evaluates [script] and
     * forwards whatever string it hands the bridge. It does not parse the JSON or know what any event means. That split
     * is the lesson of [fillCredentials] applied to reading instead of writing: a signature that
     * forces the *host* to decide which field matters gets the decision wrong on real pages,
     * because the plugin is the side that knows which box the user acted on.
     *
     * **What this adds that [executeJavaScript] cannot do** is timing, not access. [script] runs
     * before the page's own scripts, and an event is delivered while the document that produced it
     * is still alive - a submit is followed by a navigation that destroys the JS context, so
     * anything latched in the page for a later read is racing its own teardown.
     *
     * **Attribute events by [url], never by anything inside the JSON.** The payload is only as
     * trustworthy as whatever wrote it, and [url] is read by the host from the posting document.
     *
     * Contract:
     * - [url] is the URL of the document that posted, read by the host at the moment of the call.
     *   Authoritative: a forged payload cannot lie about it, and unlike reading the handle's URL
     *   afterwards it cannot be overtaken by a navigation the event itself started.
     * - [onEvent] runs on a **JxBrowser thread**, inside the page's own event dispatch, and MUST
     *   NOT block. Exceptions are swallowed rather than unwinding the page's thread.
     * - Ordered per document; reentrant across documents.
     * - **The host bounds payload size and rate, and DROPS the excess rather than queueing it.** Do
     *   not depend on the exact limits, and do not assume a burst arrives complete.
     * - **Single owner per handle.** One script and one sink: a second caller replaces the first
     *   silently, and the loser gets no signal. Two plugins wanting page events on one tab is not
     *   supported today.
     * - Main frame only, as [executeJavaScript] is.
     * - The host **does** re-inject into the document already loaded when this is first called, so
     *   a caller does not wait for the next navigation.
     * - **[script] may therefore be evaluated more than once in one document, and must tolerate
     *   that.** Reinstalling while a page is open evaluates it again in that page, and replacing a
     *   script does not retract the previous generation from a document already running it - the old
     *   listeners stay, and their events arrive at the new sink.
     *
     *   A guard in the script cannot fix this: each evaluation gets a fresh function scope, so a
     *   script-local flag is invisible to the next run, and the only slot shared across evaluations
     *   is `window` - which is the detectability the parameter shape exists to remove. Tolerating
     *   duplicates is the cheaper side of that trade for an event-driven consumer: two identical
     *   events cost a conflated channel nothing.
     * - [clearPageEventScript] uninstalls. Callers should do so in their `dispose()`: the host
     *   retains [onEvent], whose class comes from the plugin's classloader, and the api layer is
     *   hot-swappable. The host clears its own reference when the browser goes away.
     * - Coexists with [startCoBrowseCapture]; the host multiplexes the single document-start hook,
     *   so arming one does not switch off the other. Note the inverted posture: co-browse masks
     *   what the user typed, while this channel exists to carry it. **The payload may be a
     *   plaintext secret - do not log it.**
     * - A handle whose browser is gone does nothing, and never throws.
     *
     * **This copy is the one that runs.** `BrowserHandle` is host-compiled and served parent-first,
     * so the boss-plugin-api jar's copy is shadowed and its no-op default is what a caller gets
     * from an older host. Consumers gate on `minBossVersion`, not `minApiVersion`.
     *
     * @param script JavaScript source to evaluate at document start.
     * @param onEvent Receives the posting document's URL and the string handed to the bridge.
     */
    fun setPageEventScript(
        script: String,
        onEvent: (url: String, payload: String) -> Unit,
    ) {
        // Default: no-op for hosts without the page-event channel. See supportsPageEventScript,
        // which is how a caller tells that silence apart from a host that delivered nothing.
    }

    /**
     * Uninstall what [setPageEventScript] installed.
     *
     * Its own verb rather than a nullable pair, matching [startCoBrowseCapture] /
     * [stopCoBrowseCapture]. A script already evaluated in a live document is not retracted; it
     * stops being able to reach anything. **Call this from `dispose()`**: the host retains the
     * callback, whose class comes from the plugin's classloader.
     */
    fun clearPageEventScript() {
        // Default: no-op.
    }

    /**
     * Whether this handle implements the page-event channel at all.
     *
     * False where [setPageEventScript] is the no-op default. Same shape as
     * `FileSystemDataProvider.supportsHiddenEntries` and `BookmarkDataProvider.supportsBulkAdd`, and
     * for the same reason: silence otherwise covers an older host, a host-side drop, and the user
     * doing nothing, and a consumer that cannot separate the first has to treat its feature as
     * best-effort.
     */
    val supportsPageEventScript: Boolean get() = false

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

    /**
     * Whether the host is currently showing this browser in a floating pop-out window.
     *
     * True while a backgrounded tab's rendering surface has been reparented into the host's
     * pop-out (an auto-popped-out call). The tab is backgrounded by definition in that state, so
     * anything driven by "this tab is not on screen" - hibernation above all - must consult this
     * or it will dispose the handle and take the pop-out, and the call, with it.
     *
     * A plugin cannot answer this for itself: the pop-out is a host window, and the page inside
     * it knows nothing about which window it is being rendered into. It is the same category of
     * fact as the fullscreen callbacks below, which exist for the same reason.
     *
     * **Defaulted, and plugins must call it reflectively.** A plugin naming a member the host's
     * copy of this interface lacks is rejected wholesale by BinaryCompatibilityValidator, which
     * for a browser plugin reads to the user as "my browser disappeared". The default keeps older
     * hosts answering false rather than failing to load.
     */
    val isPoppedOut: Boolean
        get() = false

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
        onExitFullscreen: () -> Unit,
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
    fun startCoBrowseCapture(
        onEvent: (String) -> Unit,
        maskInputs: Boolean = false,
    ) {
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
     *
     * If this browser owns a fullscreen rendering surface, disposal may wait
     * briefly for UI-thread detachment. Do not call while holding a lock that
     * the UI thread may need.
     */
    fun dispose()
}
