package ai.rever.boss.plugin.browser

import ai.rever.boss.cache.FaviconCache
import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.plugin.api.BrowserNavigationType
import ai.rever.boss.plugin.window.LocalWindowId
import ai.rever.boss.tabfullscreen.FullscreenBrowserWindow
import ai.rever.boss.utils.MacOSGestureHandler
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.CreatePopupCallback
import com.teamdev.jxbrowser.browser.callback.InjectJsCallback
import com.teamdev.jxbrowser.browser.callback.OpenPopupCallback
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.browser.event.BrowserClosed
import com.teamdev.jxbrowser.browser.event.FaviconChanged
import com.teamdev.jxbrowser.browser.event.TitleChanged
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.event.Subscription
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.js.JsObject
import com.teamdev.jxbrowser.media.MediaType
import com.teamdev.jxbrowser.menu.ContextMenuContentType
import com.teamdev.jxbrowser.navigation.LoadUrlParams
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import com.teamdev.jxbrowser.navigation.event.LoadStarted
import com.teamdev.jxbrowser.navigation.event.NavigationFinished
import com.teamdev.jxbrowser.navigation.event.NavigationStarted
import com.teamdev.jxbrowser.net.ByteData
import com.teamdev.jxbrowser.net.HttpHeader
import com.teamdev.jxbrowser.net.NetError
import com.teamdev.jxbrowser.net.callback.BeforeSendUploadDataCallback
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.KeyModifiers
import com.teamdev.jxbrowser.ui.MouseButton
import com.teamdev.jxbrowser.ui.Point
import com.teamdev.jxbrowser.ui.Rect
import com.teamdev.jxbrowser.ui.ScrollType
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyReleased
import com.teamdev.jxbrowser.ui.event.KeyTyped
import com.teamdev.jxbrowser.ui.event.MouseDragged
import com.teamdev.jxbrowser.ui.event.MouseMoved
import com.teamdev.jxbrowser.ui.event.MousePressed
import com.teamdev.jxbrowser.ui.event.MouseReleased
import com.teamdev.jxbrowser.ui.event.MouseWheel
import com.teamdev.jxbrowser.view.compose.BrowserView
import com.teamdev.jxbrowser.view.compose.BrowserViewState
import com.teamdev.jxbrowser.zoom.ZoomLevel
import com.teamdev.jxbrowser.zoom.ZoomMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Longest inline (`data:`) image source worth carrying into a menu. No menu action needs
 * the encoded bytes, and this is the first path that hands a source URL to plugins.
 */
internal const val MAX_INLINE_IMAGE_URL_LENGTH = 2048

private val contextMenuLogger = BossLogger.forComponent("ContextMenuTarget")

/** What Chromium reports about the element a right-click landed on. */
internal data class ContextMenuTarget(
    val contentTypes: List<ContextMenuContentType> = emptyList(),
    val mediaType: MediaType = MediaType.NONE,
    val srcUrl: String = "",
    val linkUrl: String = "",
    val selectedText: String = "",
    val isMainFrame: Boolean = true,
)

/**
 * Map what Chromium reports about a right-click *target* onto the plugin-facing
 * [BrowserContextMenuInfo].
 *
 * Split out of the callback so the truth table is unit-testable without a live
 * `ShowContextMenuCallback.Params`. Scoped to the target only — the caller fills in the
 * page identity, which is carried through untouched.
 *
 * Two deliberate narrowings:
 * - [BrowserContextMenuInfo.hasImage] is only reported together with a resolvable
 *   [BrowserContextMenuInfo.imageUrl]. Chromium reports MEDIA_IMAGE for targets that have
 *   no source URL (`<canvas>`, CSS backgrounds, some inline SVG), and every image action a
 *   menu can offer needs the URL — so "image with no address" is not worth advertising.
 *   An inline image's source is a `data:` URL of the whole encoded image, which this is
 *   the first path to hand to plugins; past [MAX_INLINE_IMAGE_URL_LENGTH] it counts as no
 *   address rather than shipping megabytes of base64 into every menu.
 * - Editable is reported for the main frame only. Every action it unlocks —
 *   `cut`/`copySelection`/`paste`/`selectAll` and `fillCredentials` — runs against
 *   `browser.mainFrame()` and `document.activeElement`. Offering them for a field inside an
 *   iframe would act on the wrong frame, and in the credential case could write a password
 *   into whatever main-frame input happens to be focused. Frame-accurate detection has to
 *   wait for a frame-accurate fill path.
 */
internal fun ContextMenuTarget.toContextMenuInfo(
    pageUrl: String,
    pageTitle: String,
): BrowserContextMenuInfo {
    // The cap applies to data: only. A signed CDN address can carry a long policy and
    // signature and still be a perfectly usable URL; capping those would silently drop the
    // image actions for them, which is invisible to the user and hard to report.
    val oversizedInline = srcUrl.startsWith("data:") && srcUrl.length > MAX_INLINE_IMAGE_URL_LENGTH
    if (oversizedInline) {
        // A 3KB inline PNG is ordinary, so this drop is reachable in normal browsing and
        // costs the user their image actions with no other trace — the same invisibility
        // the comment above refuses for http URLs. One line makes it reportable.
        contextMenuLogger.debug(
            LogCategory.BROWSER,
            "Inline image source too large for a context menu - reporting no image",
            mapOf("length" to srcUrl.length.toString(), "cap" to MAX_INLINE_IMAGE_URL_LENGTH.toString()),
        )
    }
    val source = srcUrl.takeIf { it.isNotBlank() && !oversizedInline }
    val isImage =
        (mediaType == MediaType.IMAGE || contentTypes.contains(ContextMenuContentType.MEDIA_IMAGE)) &&
            source != null
    val isVideo =
        mediaType == MediaType.VIDEO ||
            contentTypes.contains(ContextMenuContentType.MEDIA_VIDEO)
    return BrowserContextMenuInfo(
        linkUrl = linkUrl.takeIf { it.isNotBlank() },
        selectedText = selectedText.takeIf { it.isNotBlank() },
        isEditable = isMainFrame && contentTypes.contains(ContextMenuContentType.EDITABLE),
        hasVideo = isVideo,
        hasImage = isImage,
        imageUrl = source.takeIf { isImage },
        pageUrl = pageUrl,
        pageTitle = pageTitle,
    )
}

/**
 * Desktop implementation of [BrowserHandle] that wraps a JxBrowser [Browser] instance.
 *
 * @param browser The underlying JxBrowser Browser instance
 * @param config The configuration used to create this browser
 * @param engineGeneration The engine generation at the time this browser was created
 */
internal class BrowserHandleImpl(
    private val browser: Browser,
    private val config: BrowserConfig,
    private val engineGeneration: Long,
    private val ownerWindowId: String,
) : BrowserHandle {
    private val logger = BossLogger.forComponent("BrowserHandleImpl")

    override val id: String = UUID.randomUUID().toString()

    /**
     * Per-tab engagement accounting. Owned here because dwell time and navigation depth are
     * only meaningful per tab, and this is the one object with that identity plus the full
     * navigation lifecycle. Publishes nothing itself — see [BrowserAnalytics].
     */
    private val visitTracker = BrowserVisitTracker(windowId = ownerWindowId)

    /**
     * Host-side kill switch for browser telemetry. Consent for *sending* analytics lives in
     * the analytics plugin, which gates egress for every event source alike; this is a
     * separate, blunter control for deployments that want no telemetry script running in
     * pages at all, whatever a plugin later decides to do with the events.
     */
    private val browserTelemetryEnabled: Boolean =
        System.getenv("BOSS_BROWSER_TELEMETRY_DISABLED")?.lowercase() != "true"

    /**
     * Authority of the page currently loaded in this tab, as last seen by the navigation
     * handler. Volatile because it is written from a JxBrowser navigation callback and read
     * from the JS thread that delivers interaction batches.
     *
     * Cached rather than resolved on demand: reading it used to call `getCurrentUrl()` →
     * `browser.url()` from inside `emit()`, which runs on the page's JS thread. `runCatching`
     * covers a throw but not a stall, and [BrowserInteractionBridge] documents that `emit`
     * must not block that thread — so the old version contradicted its own contract. The
     * navigation handler is what would observe an SPA route change anyway, so freshness is
     * identical and each batch is cheaper.
     */
    @Volatile private var currentPageAuthority: String? = null

    /** Receives in-page interaction batches, attributed to the page that is actually loaded. */
    private val interactionBridge =
        BrowserInteractionBridge(
            authorityProvider = { currentPageAuthority },
            windowId = ownerWindowId,
        )

    private val disposed = AtomicBoolean(false)
    private val subscriptions = mutableListOf<Subscription>()

    private val navigationListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val titleListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val faviconListeners = CopyOnWriteArrayList<(String?) -> Unit>()
    private val loadingListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val zoomListeners = CopyOnWriteArrayList<(Double) -> Unit>()

    // Track loading state
    private var _isLoading = false

    // Context menu callback. Volatile because it is set from the UI thread and read from a
    // JxBrowser callback thread; a stale null read there means no menu at all.
    @Volatile private var contextMenuCallback: ContextMenuCallback? = null

    // Last title Chromium reported, kept so building a context menu never has to call into
    // the live Browser. browser.title() can be slow as well as throw, and the menu path is
    // where that costs the user something visible.
    @Volatile private var lastKnownTitle: String = ""

    // Callback for opening links in new tabs (cmd+click, target="_blank", window.open)
    private var openInNewTabCallback: ((String) -> Unit)? = null

    // Callback variant that also carries POST body for form-submit popups.
    // When set, this wins over [openInNewTabCallback].
    private var openInNewTabWithDataCallback: ((PopupNavigation) -> Unit)? = null

    // BrowserViewState for Compose rendering - managed per Content() call
    private var currentViewState: BrowserViewState? = null

    /**
     * Which window [currentViewState] was built against, so a retained surface is only reused
     * while the tab is still in that window. Null when no surface exists.
     */
    private var currentViewStateWindowId: String? = null

    // True while the pointer hovers this handle's rendered BrowserView. Gates the
    // window-wide macOS pinch listener so a pinch only zooms the browser under the
    // cursor, not one sitting in a background tab or another split.
    //
    // OFF_SCREEN only. Under HARDWARE_ACCELERATED this stays false forever — see
    // [shouldAllowPinch] for why, and for what replaces it.
    @Volatile private var pointerOverBrowserView = false

    // This view's bounds in Compose-root coordinates, refreshed on every layout pass.
    // The HARDWARE_ACCELERATED substitute for hover: Compose knows where the view IS
    // even when it never learns the pointer entered it. Null until first layout.
    //
    // IN DEVICE PIXELS. Pairs with [browserViewDensity]; see [pointerInsideBounds].
    @Volatile private var browserViewBoundsInWindow: androidx.compose.ui.geometry.Rect? = null

    // Density for converting an AWT pointer into the pixel space the bounds above are measured
    // in. Refreshed from composition rather than read once, because it changes when a window is
    // dragged between a Retina panel and an external display — a stale value would misplace the
    // gate by exactly that ratio.
    @Volatile private var browserViewDensity: Float = 1f

    // The AWT window the pinch listener and the browser surface are both bound to,
    // needed to put the screen-space pointer into the same coordinate space as
    // [browserViewBoundsInWindow]. Set alongside the gesture registration.
    @Volatile private var gestureHostWindow: Window? = null

    /**
     * Whether the pointer currently sits inside this view, decided by geometry rather
     * than by hover events.
     *
     * Returns null when it cannot be determined — no layout yet, no window, or no
     * pointer (headless, or the cursor left the display) — so the caller can tell
     * "outside" apart from "unknown" instead of reading a bare false as a rejection.
     *
     * Coordinate spaces: Compose reports `boundsInWindow()` relative to the Compose
     * root, which fills the window's content pane, so the pointer is converted into
     * the CONTENT PANE and not the window. Converting to the window instead would be
     * wrong by the title-bar height — enough to zoom while pointing just above the
     * view and to refuse near its bottom edge.
     */
    private fun pointerInsideBrowserView(): Boolean? {
        val bounds = browserViewBoundsInWindow
        val window = gestureHostWindow
        if (bounds == null || window == null) return null
        return try {
            // Read INSIDE the try, unlike before: getPointerInfo throws HeadlessException rather
            // than returning null in a headless JVM, so reading it outside made the KDoc's promise
            // of "null when there is no pointer" false in exactly the case it names.
            val pointer =
                java.awt.MouseInfo
                    .getPointerInfo()
                    ?.location
            val origin = (window as? javax.swing.RootPaneContainer)?.contentPane ?: window
            // convertPointFromScreen requires a showing component. Checked rather than relied on
            // because a window mid-teardown is an ordinary state here, not an error.
            if (pointer != null && origin.isShowing) {
                javax.swing.SwingUtilities.convertPointFromScreen(pointer, origin)
                pointerInsideBounds(
                    boundsPx = bounds,
                    pointerLogical =
                        androidx.compose.ui.geometry
                            .Offset(pointer.x.toFloat(), pointer.y.toFloat()),
                    density = browserViewDensity,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            // The component can stop showing between the check above and the call.
            logger.debug(
                LogCategory.BROWSER,
                "Could not locate pointer relative to browser view",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    // Runs a pinch-triggered zoom only when the pointer is over this view and the
    // handle is alive; logs suppressions with both inputs, because a wrong gate
    // presents as pinch silently not working (or zooming a non-hovered view) and the
    // two causes are indistinguishable from the outside.
    private inline fun gatedPinchZoom(
        direction: String,
        zoom: () -> Unit,
    ) {
        val geometric = pointerInsideBrowserView()
        if (shouldAllowPinch(
                mode = JxBrowserConfig.renderingMode,
                isValid = isValid,
                pointerOverComposeView = pointerOverBrowserView,
                pointerInsideBounds = geometric,
            )
        ) {
            zoom()
        } else {
            logger.debug(
                LogCategory.BROWSER,
                "Pinch zoom suppressed",
                mapOf(
                    "direction" to direction,
                    "mode" to JxBrowserConfig.renderingMode.name,
                    "hovered" to pointerOverBrowserView.toString(),
                    "pointerInsideBounds" to geometric.toString(),
                    "bounds" to browserViewBoundsInWindow.toString(),
                    "valid" to isValid.toString(),
                ),
            )
        }
    }

    // --- Co-browse / tab sharing (DOM state-sync) ---
    // Whether the rrweb recorder is actively streaming this tab to viewers.
    @Volatile private var coBrowseCapturing = false

    // Whether a remote viewer is allowed to actuate this tab (gates applyCoBrowseControl).
    @Volatile private var coBrowseControlGranted = false

    // Whether rrweb masks form-input values (maskAllInputs) for this capture.
    @Volatile private var coBrowseMaskInputs = false

    // Sink for rrweb events (set by the plugin's share manager). MUST be non-blocking.
    @Volatile private var coBrowseSink: ((String) -> Unit)? = null

    // True once the InjectJsCallback is registered (kept inert when not capturing).
    @Volatile private var coBrowseInjectRegistered = false

    // Page→host bridge injected onto window.__bossCoBrowse; its onEvent is repointed per capture.
    private val coBrowseBridge = CoBrowseBridge()

    // Main-thread scope for injection/teardown (rrweb inject + executeJavaScript run on Main).
    private val coBrowseScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Off-thread executor for context-menu detail lookups. The form-field inspection is a
    // blocking JS round-trip, and it must not run on the JxBrowser callback thread (which
    // is answering the menu request) nor on the UI thread.
    //
    // The blocking call gets its own single thread rather than Dispatchers.IO: it cannot be
    // cancelled (nothing interrupts it), so against a wedged renderer each right-click would
    // park a shared-pool worker indefinitely. Confined here, the cost is one parked thread
    // and later lookups queue behind it.
    private val contextMenuExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "boss-context-menu-$id").apply { isDaemon = true }
        }
    private val contextMenuLookupDispatcher = contextMenuExecutor.asCoroutineDispatcher()

    // The coroutine that *waits* on that lookup must NOT share its thread. Both on one
    // thread, the timeout cannot fire: resuming the awaiting continuation needs a dispatch
    // onto the thread the blocking lookup is holding, so the wait lasts as long as the
    // renderer takes and the bound is no bound at all.
    private val contextMenuScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    // Otherwise an escaping failure lands on the default handler and prints
                    // to stderr, bypassing BossLogger.
                    logger.warn(LogCategory.BROWSER, "Context-menu lookup failed", error = error)
                },
        )

    // Lock for thread-safe browser operations
    private val browserLock = ReentrantReadWriteLock()

    // Helper to create LockedBrowser for FormFieldDetector/FormFieldInjector
    private fun createLockedBrowser(): LockedBrowser = LockedBrowser(browser, browserLock)

    /** Expose the raw JxBrowser instance for internal use (e.g. RPA recorder). */
    internal fun getRawBrowser(): Browser = browser

    /** Expose the browser lock for creating [LockedBrowser] wrappers externally. */
    internal fun getBrowserLock(): ReentrantReadWriteLock = browserLock

    init {
        // Scope zoom to this browser instance. Chromium's default PER_ORIGIN mode
        // propagates a zoom change to every browser on the same domain, so pinching
        // one tab yanked all same-URL tabs with it. Per-domain zoom persistence is
        // unaffected: it's applied per tab on navigation via ZoomSettingsProvider.
        try {
            browser.zoom().mode(ZoomMode.PER_BROWSER)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not set per-browser zoom mode", error = e)
        }

        setupEventListeners()
        setupBrowserHandlers()

        // A tab exists from here on, whether or not it ever loads a page. Reporting the open
        // before the initial navigation keeps opens and closes balanced for tabs the user
        // shuts before anything renders.
        visitTracker.opened(config.url.takeIf { it.isNotBlank() }?.let(::suggestableHost))

        // Load initial URL
        if (config.url.isNotBlank()) {
            val postData = config.initialPostData
            val contentType = config.initialPostContentType
            if (postData != null && contentType != null) {
                // Replay a form-submit popup as POST on first navigation.
                val params =
                    LoadUrlParams
                        .newBuilder(config.url)
                        .uploadData(ByteData.of(postData))
                        .addExtraHeader(HttpHeader.of("Content-Type", contentType))
                        .build()
                browser.navigation().loadUrl(params)
            } else {
                browser.navigation().loadUrl(config.url)
            }
        }
    }

    private fun setupEventListeners() {
        // Navigation started - track loading state
        subscriptions +=
            browser.navigation().on(NavigationStarted::class.java) { _ ->
                _isLoading = true
                loadingListeners.forEach { listener ->
                    try {
                        listener(true)
                    } catch (e: Exception) {
                        logger.warn(LogCategory.BROWSER, "Loading listener threw exception", error = e)
                    }
                }
            }

        // Navigation finished - track loading state, notify URL change, and inject trackers
        subscriptions +=
            browser.navigation().on(NavigationFinished::class.java) { event ->
                // Record the outcome BEFORE notifying anyone: the loading, navigation and
                // title callbacks below are what feed the URL history and the dashboard's
                // recent pages, and they must see whether this navigation actually landed
                // on a page. A mistyped host (youtube.como) commits an error page and still
                // fires all three, so without this it gets recorded as a visited page.
                recordNavigationOutcome(event)

                _isLoading = false
                loadingListeners.forEach { listener ->
                    try {
                        listener(false)
                    } catch (e: Exception) {
                        logger.warn(LogCategory.BROWSER, "Loading listener threw exception", error = e)
                    }
                }

                // Only notify navigation listeners for main frame navigations
                // This prevents iframe navigations (which often load about:blank) from
                // incorrectly updating the URL bar in plugins
                if (event.isInMainFrame) {
                    val url = event.url()
                    navigationListeners.forEach { listener ->
                        try {
                            listener(url)
                        } catch (e: Exception) {
                            logger.warn(LogCategory.BROWSER, "Navigation listener threw exception", error = e)
                        }
                    }

                    // Engagement tracking rides the same two gates the URL history uses: a
                    // real http(s) host, and a page that actually loaded. recordNavigationOutcome
                    // ran at the top of this handler, so didFail is already accurate for this
                    // navigation — a mistyped host commits an error page and must not count as
                    // a visit any more than it counts as history.
                    // Set unconditionally, including to null: an interaction arriving after a
                    // navigation to something unreportable (a dev server, an IP) must not be
                    // attributed to whatever site preceded it.
                    val authority = suggestableHost(url)
                    currentPageAuthority = authority
                    if (authority != null && !NavigationOutcomeTracker.didFail(url)) {
                        visitTracker.pageViewed(authority)
                    }

                    // Skip injection for about:blank pages (used for dashboard display)
                    // Only inject into actual web pages
                    if (url.isNotEmpty() && url != "about:blank") {
                        injectPageHelpers()
                    }
                }
            }

        // Title changed
        subscriptions +=
            browser.on(TitleChanged::class.java) { event ->
                val title = event.title()
                lastKnownTitle = title
                titleListeners.forEach { listener ->
                    try {
                        listener(title)
                    } catch (e: Exception) {
                        logger.warn(LogCategory.BROWSER, "Title listener threw exception", error = e)
                    }
                }
            }

        // Favicon changed - save to cache and notify listeners with cache key
        subscriptions +=
            browser.on(FaviconChanged::class.java) { event ->
                try {
                    val favicon = event.favicon()
                    if (favicon == null || favicon.size().isEmpty) {
                        // No favicon, notify with null
                        faviconListeners.forEach { listener ->
                            try {
                                listener(null)
                            } catch (e: Exception) {
                                logger.warn(LogCategory.BROWSER, "Favicon listener threw exception", error = e)
                            }
                        }
                    } else {
                        // Convert JxBrowser Bitmap to AWT BufferedImage then to Compose ImageBitmap
                        val size = favicon.size()
                        val width = size.width()
                        val height = size.height()

                        val bufferedImage = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        val pixels = favicon.pixels()

                        // Convert BGRA bytes to ARGB integers and set pixels
                        var pixelIndex = 0
                        for (y in 0 until height) {
                            for (x in 0 until width) {
                                val b = pixels[pixelIndex++].toInt() and 0xFF
                                val g = pixels[pixelIndex++].toInt() and 0xFF
                                val r = pixels[pixelIndex++].toInt() and 0xFF
                                val a = pixels[pixelIndex++].toInt() and 0xFF
                                val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                                bufferedImage.setRGB(x, y, argb)
                            }
                        }

                        val imageBitmap = bufferedImage.toComposeImageBitmap()
                        val currentUrl = browser.url()
                        val cacheKey = FaviconCache.saveFavicon(currentUrl, imageBitmap)

                        faviconListeners.forEach { listener ->
                            try {
                                listener(cacheKey)
                            } catch (e: Exception) {
                                logger.warn(LogCategory.BROWSER, "Favicon listener threw exception", error = e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Error processing favicon", error = e)
                }
            }

        // Browser closed
        subscriptions +=
            browser.on(BrowserClosed::class.java) {
                logger.debug(LogCategory.BROWSER, "Browser closed", mapOf("handleId" to id))
                disposed.set(true)
                // Stop streaming: the underlying page is gone.
                coBrowseCapturing = false
                coBrowseSink = null
                coBrowseBridge.onEvent = null
            }
    }

    /**
     * Publish whether a finished main-frame navigation actually loaded a page, so the URL
     * history and the dashboard's recent pages can skip the ones that didn't.
     *
     * Two kinds of eviction follow a failure, because publishing a verdict is not enough
     * on its own. The loading and navigation callbacks below run inside this handler and
     * are guaranteed to see it, but **title callbacks are a separate event stream**
     * (`TitleChanged`, wired in [setupEventListeners]) and Chromium sets an error page's
     * title around commit time — so a visit can already have been recorded by the time
     * this runs. Retracting anything recorded in the last few seconds closes that race
     * for every failure class, not just the ones that evict unconditionally.
     *
     * Failures that mean "this address does not exist" evict regardless of age, which is
     * what retires a typo recorded before this gating existed. Failures about the
     * connection rather than the address (offline, timeout, aborted) only ever retract the
     * racing entry — a site you can't reach right now is still a site you visited.
     */
    private fun recordNavigationOutcome(event: NavigationFinished) {
        try {
            val error =
                try {
                    event.error()
                } catch (e: Exception) {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Navigation error state unavailable",
                        mapOf("error" to e.toString()),
                    )
                    NetError.OK
                }
            val url = event.url()
            val verdict =
                classifyNavigation(
                    isMainFrame = event.isInMainFrame,
                    hasUrl = url.isNotBlank(),
                    isErrorPage = event.isErrorPage,
                    hasCommitted = event.hasCommitted(),
                    hasNetworkError = error != NetError.OK,
                )

            when (verdict) {
                NavigationVerdict.IGNORED -> {
                    return
                }

                NavigationVerdict.LOADED -> {
                    NavigationOutcomeTracker.recordSuccess(url)
                    // This host can serve pages, so it is never a candidate for the
                    // "address does not exist" eviction below, however it fails later.
                    suggestableHost(url)?.let(ResolvedHostsStore::recordLoaded)
                }

                NavigationVerdict.FAILED -> {
                    NavigationOutcomeTracker.recordFailure(url)

                    // "The address does not exist" needs more than a name error. Two
                    // things have to hold: something else resolved recently, so this isn't
                    // a total DNS outage; and this host has never served a page, so it
                    // isn't an address the user relies on that happens to be unreachable
                    // from where they are — the split-horizon case, where a developer off
                    // the VPN watches `jira.internal.corp` fail while public DNS is fine.
                    val host = suggestableHost(url)
                    val addressIsGone =
                        error in ADDRESS_DOES_NOT_EXIST_ERRORS &&
                            NavigationOutcomeTracker.hasLoadedRecently() &&
                            (host == null || !ResolvedHostsStore.hasEverLoaded(host))

                    val window =
                        when (retractionScopeFor(event.isErrorPage, addressIsGone)) {
                            RetractionScope.EVICT_ALL -> null
                            RetractionScope.RETRACT_RECENT -> RACE_RETRACTION_MS
                            RetractionScope.LEAVE_ALONE -> return
                        }
                    // Off the engine's event thread: this walks the whole history.
                    retractionScope.launch {
                        UrlHistoryManager.removeMatchingUrls(url, window)
                    }
                    RecentBrowserPagesManager.removeMatchingPages(url, window)
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to record navigation outcome", error = e)
        }
    }

    private fun setupBrowserHandlers() {
        // Setup download handler if enabled
        if (config.enableDownloads) {
            FluckEngine.setupBrowserDownloadHandler(browser)
        }

        // Setup keyboard interceptor for menu shortcuts
        FluckEngine.setupKeyboardInterceptor(browser, ownerWindowId)

        // Let a click in the page close any Swing popup menu open over it
        FluckEngine.setupSwingPopupDismissOnPageClick(browser)

        // Setup screen capture handler
        FluckEngine.setupCaptureSessionHandler(browser)

        // Setup context menu handler
        setupContextMenuHandler()
    }

    /**
     * Hand a built menu to the plugin.
     *
     * The callback is plugin code, reached from a JxBrowser thread on one path and this
     * handle's own thread on the other; letting it throw there would take out the caller
     * rather than the plugin. Bounded so a misbehaving plugin loses only its own menu.
     */
    private fun deliverContextMenu(
        callback: ContextMenuCallback,
        info: BrowserContextMenuInfo,
    ) {
        try {
            callback(info)
        } catch (e: LinkageError) {
            // Named ahead of the general branch: a plugin compiled against a different
            // boss-plugin-api than the one loaded fails exactly this way, and it is the
            // most likely thing to go wrong here.
            logger.warn(LogCategory.BROWSER, "Context menu callback failed to link", error = e)
        } catch (e: AssertionError) {
            // Named for the same reason: a plugin's failed `assert`/`error()` is an Error,
            // not an Exception, and the promise here is that a misbehaving plugin loses only
            // its own menu. Listed individually rather than catching Throwable because
            // detekt keeps TooGenericExceptionCaught active on purpose — and a genuinely
            // fatal Error (OOM, StackOverflow) is not this boundary's to swallow.
            logger.warn(LogCategory.BROWSER, "Context menu callback failed an assertion", error = e)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Context menu callback threw", error = e)
        }
    }

    /**
     * Answers Chromium, and never throws while doing it.
     *
     * `close()` can fail — already answered, or the browser torn down mid-callback — and both call
     * sites reach it from a JxBrowser thread, one of them from a `finally`. An escaping exception
     * there is uncaught and off the EDT, which is the failure class this handler is built around.
     */
    @Suppress("TooGenericExceptionCaught") // Error must propagate; see deliverContextMenu.
    private fun closeQuietly(tell: ShowContextMenuCallback.Action) {
        try {
            tell.close()
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not answer the context-menu callback", error = e)
        }
    }

    private fun setupContextMenuHandler() {
        browser.set(
            ShowContextMenuCallback::class.java,
            ShowContextMenuCallback { params, tell ->
                val callback = contextMenuCallback
                if (callback == null) {
                    // Nobody is going to draw a menu, so hand the request back rather than
                    // leaving it unanswered — an un-responded async callback shows nothing
                    // at all, and Chromium keeps waiting on it.
                    closeQuietly(tell)
                    return@ShowContextMenuCallback
                }

                // Everything about the click target comes from params: it is resolved by
                // Chromium against the frame that was actually right-clicked. Reading it
                // back out of the page (the old approach) answered for the MAIN frame and
                // for document.activeElement, so a click inside an iframe reported the
                // previous click's link, and a click anywhere after focusing an input
                // reported "editable".
                // Answering is in a finally for the same reason it moved to the front:
                // nothing between the right-click and tell.close() may lose the menu, and
                // these reads can throw when teardown races the callback. Every params read
                // lives in here, including the frame — they are all part of the target.
                //
                // The title comes from lastKnownTitle rather than browser.title(): that was
                // the last call on this path reaching the live Browser, where being slow
                // hurts as much as throwing and a try/catch only covers the latter.
                val read =
                    try {
                        val target =
                            ContextMenuTarget(
                                contentTypes = params.contentTypes(),
                                mediaType = params.mediaType(),
                                srcUrl = params.srcUrl(),
                                linkUrl = params.linkUrl(),
                                selectedText = params.selectedText(),
                                isMainFrame = params.isMainFrame(),
                            ).toContextMenuInfo(
                                pageUrl = params.pageUrl(),
                                pageTitle = lastKnownTitle,
                            )
                        target to params.frame().orElse(null)
                    } catch (e: Exception) {
                        logger.debug(
                            LogCategory.BROWSER,
                            "Could not read the context-menu target",
                            mapOf("error" to e.toString()),
                        )
                        null
                    } finally {
                        // Suppresses JxBrowser's native menu and releases the request.
                        closeQuietly(tell)
                    }

                if (read == null) return@ShowContextMenuCallback
                val (info, frame) = read

                if (!info.isEditable) {
                    // Runs on a JxBrowser thread; deliverContextMenu bounds a throwing plugin.
                    deliverContextMenu(callback, info)
                    return@ShowContextMenuCallback
                }

                // Secret auto-fill needs details Chromium does not report (field name, id,
                // autocomplete). That means a JS round-trip, so it happens off this thread —
                // against the main frame, which isEditable is gated to.
                contextMenuScope.launch {
                    // Raced rather than wrapped: executeJavaScript blocks, so a cancelled
                    // withTimeoutOrNull would have no suspension point to land on and could
                    // not interrupt it. Awaiting a separate job does bound the wait — worst
                    // case the menu opens without the autofill entries instead of never
                    // opening, which is the failure the rest of this handler exists to avoid.
                    //
                    // On timeout the lookup is NOT cancelled — nothing can interrupt it —
                    // so it keeps its thread until the renderer answers. That is the
                    // dedicated single thread rather than a shared pool worker, so a wedged
                    // page costs one parked thread in total; later lookups queue behind it
                    // and time out on schedule, opening without autofill. The wait itself
                    // runs on Dispatchers.Default so the timeout can actually fire.
                    //
                    // (If dispose() lands between the launch and this dispatch, the executor
                    // rejects it and kotlinx reroutes to Dispatchers.IO — the coroutine is
                    // already cancelled by then, so the blocking body never runs and the
                    // "never a shared-pool thread" property still holds.)
                    // runCatching inside the child, not around the await: the child is a
                    // sibling of the timeout rather than inside it (that is what lets the
                    // timeout fire), so a failure would reach the parent job the moment it
                    // happened and cancel this launch before the menu is delivered. Losing
                    // the autofill detail is acceptable; losing the menu is the thing this
                    // handler exists to prevent.
                    val lookup =
                        async(contextMenuLookupDispatcher) {
                            runCatching { frame?.let { getFormFieldInfoFromJS(it) } }.getOrNull()
                        }
                    val formFieldInfo = withTimeoutOrNull(FORM_FIELD_LOOKUP_TIMEOUT_MS) { lookup.await() }
                    // The plugin may have deregistered while we waited — a tab switch or an
                    // unload — and `disposed` only covers this handle being torn down.
                    val current = contextMenuCallback ?: return@launch
                    // The lookup can outlive dispose(): cancelling contextMenuScope cannot
                    // interrupt the blocking call either, so check before delivering rather
                    // than pushing a menu at a tab that is gone.
                    if (disposed.get()) return@launch
                    deliverContextMenu(current, info.copy(formFieldInfo = formFieldInfo))
                }
            },
        )
    }

    /**
     * Get form field info from JavaScript for secret auto-fill.
     *
     * Reads `document.activeElement`, i.e. it assumes the right-click focused the field it
     * landed on. Chromium does that on mousedown, so the common case holds — but a page
     * that calls `preventDefault()` on mousedown (custom form widgets do) leaves focus
     * where it was, and this then describes the *previously* focused field while
     * [BrowserContextMenuInfo.isEditable], which comes from the click target, correctly
     * describes the clicked one. The fill path (`fillCredentials`, `FormFieldInjector`)
     * targets `activeElement` too, so the two agree with each other and can jointly be
     * wrong about which field the user meant.
     *
     * Resolving from `params.location()` via `elementFromPoint`, or deferring resolution
     * until a fill action is actually chosen, would remove the assumption.
     */
    private fun getFormFieldInfoFromJS(frame: com.teamdev.jxbrowser.frame.Frame): FormFieldInfo? {
        return try {
            val jsonString =
                frame.executeJavaScript<String?>(
                    """
                    (function() {
                        var field = document.activeElement;
                        if (!field || (field.tagName !== 'INPUT' && field.tagName !== 'TEXTAREA')) {
                            return null;
                        }
                        var form = field.closest('form');
                        return JSON.stringify({
                            type: field.type || 'text',
                            name: field.name || '',
                            id: field.id || '',
                            placeholder: field.placeholder || '',
                            value: field.value || '',
                            formAction: form ? form.action : '',
                            autocomplete: field.getAttribute('autocomplete') || '',
                            className: field.className || ''
                        });
                    })()
                    """.trimIndent(),
                )

            if (jsonString.isNullOrBlank() || jsonString == "null") {
                return null
            }

            // Parse JSON manually (simple extraction)
            val extractValue = { key: String ->
                val pattern = "\"$key\":\"([^\"]*)\""
                val regex = Regex(pattern)
                regex.find(jsonString)?.groupValues?.getOrNull(1) ?: ""
            }

            val inputType = extractValue("type").ifEmpty { "text" }
            val fieldName = extractValue("name")
            val fieldId = extractValue("id")
            val placeholder = extractValue("placeholder")
            val value = extractValue("value")
            val formAction = extractValue("formAction").ifEmpty { null }
            val autocomplete = extractValue("autocomplete")

            // Determine field type
            val fieldType =
                when {
                    inputType == "password" -> FormFieldType.PASSWORD

                    inputType == "email" -> FormFieldType.EMAIL

                    autocomplete.contains("username", ignoreCase = true) -> FormFieldType.USERNAME

                    autocomplete.contains("email", ignoreCase = true) -> FormFieldType.EMAIL

                    autocomplete.contains("password", ignoreCase = true) -> FormFieldType.PASSWORD

                    fieldName.contains("user", ignoreCase = true) ||
                        fieldId.contains("user", ignoreCase = true) ||
                        fieldName.contains("login", ignoreCase = true) ||
                        fieldId.contains("login", ignoreCase = true) -> FormFieldType.USERNAME

                    fieldName.contains("email", ignoreCase = true) ||
                        fieldId.contains("email", ignoreCase = true) -> FormFieldType.EMAIL

                    fieldName.contains("pass", ignoreCase = true) ||
                        fieldId.contains("pass", ignoreCase = true) -> FormFieldType.PASSWORD

                    inputType == "text" -> FormFieldType.TEXT

                    else -> FormFieldType.UNKNOWN
                }

            FormFieldInfo(
                fieldType = fieldType,
                fieldName = fieldName,
                fieldId = fieldId,
                fieldPlaceholder = placeholder,
                fieldValue = value,
                parentFormAction = formAction,
                inputType = inputType,
                autocomplete = autocomplete,
            )
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Failed to get form field info", mapOf("error" to e.message))
            null
        }
    }

    /**
     * Injects the page-side helpers that outlive a navigation:
     * 1. Cmd+Click (Mac) / Ctrl+Click (Win/Linux) on links → `window.open()` in a new tab
     * 2. Form field detection for secret auto-fill
     *
     * The context menu no longer needs anything injected — Chromium reports the click
     * target natively (see [setupContextMenuHandler]), and the trackers this used to
     * install could only ever answer for the main frame.
     */
    private fun injectPageHelpers() {
        browser.mainFrame().ifPresent { frame ->
            try {
                // Inject Cmd+Click / Ctrl+Click handler for opening links in new tabs
                frame.executeJavaScript<Unit>(BrowserJavaScripts.injectCmdClickHandler)

                // Inject form field detection script for secret auto-fill
                FormFieldDetector.injectFormDetectionScript(createLockedBrowser())

                injectInteractionCollector(frame)

                logger.debug(LogCategory.BROWSER, "Page helpers injected", mapOf("handleId" to id))
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to inject page helpers", error = e)
            }
        }
    }

    /**
     * Publish the interaction bridge on this page and start the collector.
     *
     * Re-run per navigation because each document gets a fresh JS context; the script
     * guards itself against double-injection into the same one.
     *
     * The authority is resolved lazily, at the moment a batch arrives, rather than captured
     * here — a single-page app can navigate without a new document, and stamping events
     * with the authority that happened to be current at injection time would attribute a
     * later page's interactions to an earlier site.
     */
    private fun injectInteractionCollector(frame: Frame) {
        if (!browserTelemetryEnabled) return
        try {
            val window = frame.executeJavaScript<JsObject>("window")
            window?.putProperty(BrowserInteractionScript.BRIDGE_PROPERTY, interactionBridge)
            frame.executeJavaScript<Any?>(BrowserInteractionScript.source)
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Interaction collector injection failed",
                mapOf("handleId" to id, "error" to e.toString()),
            )
        }
    }

    // ============================================================
    // CO-BROWSE / TAB SHARING (DOM state-sync)
    // ============================================================

    /**
     * Inject the rrweb recorder + page→host bridge into [frame] (main frame only).
     * rrweb captures same-origin iframes natively, so we never start a second
     * recorder in subframes. Must run on the JxBrowser/Main thread.
     */
    private fun injectCoBrowseRecorder(frame: Frame) {
        try {
            // Expose the page→host bridge on window, then start the recorder.
            val window = frame.executeJavaScript<JsObject>("window")
            window?.putProperty("__bossCoBrowse", coBrowseBridge)
            frame.executeJavaScript<Any?>(CoBrowseScripts.recordInjection(coBrowseMaskInputs))
            // Re-assert the control guard for this fresh JS context.
            frame.executeJavaScript<Any?>(CoBrowseScripts.setControlGuard(coBrowseControlGranted))
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Co-browse recorder injection failed", mapOf("handleId" to id), error = e)
        }
    }

    /**
     * Register the script-context-creation hook once. It re-injects the recorder
     * into every future main-frame navigation while capture is active, and is
     * inert otherwise (gated by [coBrowseCapturing]). Left registered after
     * [stopCoBrowseCapture] to avoid re-register races; removed in [dispose].
     */
    private fun ensureCoBrowseInjectCallback() {
        if (coBrowseInjectRegistered) return
        coBrowseInjectRegistered = true
        try {
            browser.set(
                InjectJsCallback::class.java,
                InjectJsCallback { params ->
                    try {
                        if (coBrowseCapturing && params.frame().isMain) {
                            injectCoBrowseRecorder(params.frame())
                        }
                    } catch (e: Exception) {
                        logger.warn(LogCategory.BROWSER, "InjectJsCallback failed", error = e)
                    }
                    InjectJsCallback.Response.proceed()
                },
            )
        } catch (e: Exception) {
            coBrowseInjectRegistered = false
            logger.warn(LogCategory.BROWSER, "Failed to register InjectJsCallback", error = e)
        }
    }

    override fun startCoBrowseCapture(
        onEvent: (String) -> Unit,
        maskInputs: Boolean,
    ) {
        if (!isValid) return
        if (CoBrowseScripts.recorderLib.isBlank()) {
            logger.error(LogCategory.BROWSER, "Co-browse recorder bundle missing; capture not started", mapOf("handleId" to id))
            return
        }
        coBrowseMaskInputs = maskInputs
        coBrowseBridge.onEvent = onEvent
        coBrowseSink = onEvent
        coBrowseCapturing = true
        ensureCoBrowseInjectCallback()
        // InjectJsCallback only fires on future contexts, so inject into the page that's already loaded.
        coBrowseScope.launch {
            try {
                browser.mainFrame().ifPresent { frame -> injectCoBrowseRecorder(frame) }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Co-browse immediate injection failed", error = e)
            }
        }
        logger.debug(LogCategory.BROWSER, "Co-browse capture started", mapOf("handleId" to id))
    }

    override fun stopCoBrowseCapture() {
        if (!coBrowseCapturing && coBrowseSink == null) return
        coBrowseCapturing = false
        coBrowseControlGranted = false
        coBrowseSink = null
        coBrowseBridge.onEvent = null
        coBrowseScope.launch {
            try {
                browser.mainFrame().ifPresent { frame ->
                    frame.executeJavaScript<Any?>(CoBrowseScripts.recordStop)
                    frame.executeJavaScript<Any?>(CoBrowseScripts.setControlGuard(false))
                }
            } catch (_: Exception) {
                // Page may already be gone; nothing to tear down.
            }
        }
        logger.debug(LogCategory.BROWSER, "Co-browse capture stopped", mapOf("handleId" to id))
    }

    override fun isCoBrowseCapturing(): Boolean = coBrowseCapturing

    override fun setCoBrowseControlEnabled(granted: Boolean) {
        coBrowseControlGranted = granted
        coBrowseScope.launch {
            try {
                browser.mainFrame().ifPresent { it.executeJavaScript<Any?>(CoBrowseScripts.setControlGuard(granted)) }
            } catch (_: Exception) {
            }
        }
        logger.debug(LogCategory.BROWSER, "Co-browse control ${if (granted) "granted" else "revoked"}", mapOf("handleId" to id))
    }

    override fun dispatchCoBrowseInput(inputJson: String) {
        if (!isValid || !coBrowseControlGranted) return
        val o =
            try {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(inputJson)
                    .jsonObject
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Co-browse input unparsable", mapOf("handleId" to id), error = e)
                return
            }

        fun int(
            k: String,
            d: Int = 0,
        ) = o[k]?.jsonPrimitive?.intOrNull ?: d

        fun fl(
            k: String,
            d: Float = 0f,
        ) = o[k]?.jsonPrimitive?.floatOrNull ?: d

        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""

        fun bool(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
        val kind = str("kind")
        coBrowseScope.launch {
            try {
                val point = Point.of(int("x"), int("y"))
                when (kind) {
                    "down", "up" -> {
                        val button =
                            when (int("button")) {
                                1 -> MouseButton.MIDDLE
                                2 -> MouseButton.SECONDARY
                                else -> MouseButton.PRIMARY
                            }
                        val clicks = int("clicks", 1)
                        if (kind == "down") {
                            browser.dispatch(
                                MousePressed
                                    .newBuilder(point)
                                    .button(button)
                                    .clickCount(clicks)
                                    .build(),
                            )
                        } else {
                            browser.dispatch(
                                MouseReleased
                                    .newBuilder(point)
                                    .button(button)
                                    .clickCount(clicks)
                                    .build(),
                            )
                        }
                    }

                    "move" -> {
                        browser.dispatch(MouseMoved.newBuilder(point).build())
                    }

                    "drag" -> {
                        browser.dispatch(MouseDragged.newBuilder(point).button(MouseButton.PRIMARY).build())
                    }

                    "wheel" -> {
                        browser.dispatch(
                            MouseWheel
                                .newBuilder(point)
                                .deltaX(fl("dx"))
                                .deltaY(fl("dy"))
                                .scrollType(ScrollType.UNIT_SCROLL)
                                .build(),
                        )
                    }

                    "keydown", "keyup" -> {
                        val keyCode = jsKeyToKeyCode(str("key"), str("code"))
                        val ch = str("ch").firstOrNull() ?: '\u0000'
                        val mods =
                            KeyModifiers
                                .newBuilder()
                                .shiftDown(bool("shift"))
                                .controlDown(bool("ctrl"))
                                .altDown(bool("alt"))
                                .metaDown(bool("meta"))
                                .build()
                        if (kind == "keydown") {
                            browser.dispatch(
                                KeyPressed
                                    .newBuilder(keyCode)
                                    .keyChar(ch)
                                    .keyModifiers(mods)
                                    .build(),
                            )
                            // KeyTyped delivers the character to the focused field; only for
                            // printable input (modifier chords and control keys must not type).
                            if (ch != '\u0000' && !ch.isISOControl() && !bool("ctrl") && !bool("meta")) {
                                browser.dispatch(
                                    KeyTyped
                                        .newBuilder(keyCode)
                                        .keyChar(ch)
                                        .keyModifiers(mods)
                                        .build(),
                                )
                            }
                        } else {
                            browser.dispatch(KeyReleased.newBuilder(keyCode).keyModifiers(mods).build())
                        }
                    }

                    else -> {
                        logger.warn(LogCategory.BROWSER, "Co-browse input unknown kind", mapOf("handleId" to id, "kind" to kind))
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Co-browse input dispatch failed", mapOf("handleId" to id, "kind" to kind), error = e)
            }
        }
    }

    /** Map a JS KeyboardEvent key/code pair onto the engine's key codes. */
    private fun jsKeyToKeyCode(
        key: String,
        code: String,
    ): KeyCode =
        when {
            code.length == 4 && code.startsWith("Key") -> {
                runCatching { KeyCode.valueOf("KEY_CODE_${code[3]}") }.getOrDefault(KeyCode.UNKNOWN)
            }

            code.length == 6 && code.startsWith("Digit") -> {
                runCatching { KeyCode.valueOf("KEY_CODE_${code[5]}") }.getOrDefault(KeyCode.UNKNOWN)
            }

            else -> {
                when (key) {
                    "Enter" -> KeyCode.KEY_CODE_RETURN
                    "Backspace" -> KeyCode.KEY_CODE_BACK
                    "Tab" -> KeyCode.KEY_CODE_TAB
                    "Escape" -> KeyCode.KEY_CODE_ESCAPE
                    " ", "Spacebar" -> KeyCode.KEY_CODE_SPACE
                    "ArrowLeft" -> KeyCode.KEY_CODE_LEFT
                    "ArrowRight" -> KeyCode.KEY_CODE_RIGHT
                    "ArrowUp" -> KeyCode.KEY_CODE_UP
                    "ArrowDown" -> KeyCode.KEY_CODE_DOWN
                    "Delete" -> KeyCode.KEY_CODE_DELETE
                    "Home" -> KeyCode.KEY_CODE_HOME
                    "End" -> KeyCode.KEY_CODE_END
                    "PageUp" -> KeyCode.KEY_CODE_PRIOR
                    "PageDown" -> KeyCode.KEY_CODE_NEXT
                    "Shift" -> KeyCode.KEY_CODE_SHIFT
                    "Control" -> KeyCode.KEY_CODE_CONTROL
                    "Alt" -> KeyCode.KEY_CODE_MENU
                    else -> KeyCode.UNKNOWN
                }
            }
        }

    override suspend fun applyCoBrowseControl(eventJson: String): String? {
        if (!isValid || !coBrowseControlGranted) {
            logger.warn(
                LogCategory.BROWSER,
                "Co-browse control refused by handle guard",
                mapOf("handleId" to id, "valid" to isValid.toString(), "granted" to coBrowseControlGranted.toString()),
            )
            return null
        }
        return withContext(Dispatchers.Main) {
            try {
                val status =
                    browser
                        .mainFrame()
                        .map { frame ->
                            frame.executeJavaScript<String?>(CoBrowseScripts.applyControl(eventJson))
                        }.orElse(null)
                if (status != "ok") {
                    // Non-ok statuses ("stale"/"denied"/"nomirror"/"err:…") are how
                    // control failures surface — keep them visible for live debugging.
                    logger.warn(
                        LogCategory.BROWSER,
                        "Co-browse control not applied",
                        mapOf("handleId" to id, "status" to (status ?: "null"), "event" to eventJson.take(120)),
                    )
                }
                status
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Co-browse control apply failed", mapOf("handleId" to id), error = e)
                "err"
            }
        }
    }

    override val isValid: Boolean
        get() =
            !disposed.get() && !browser.isClosed &&
                FluckEngine.currentEngineGeneration == engineGeneration

    override suspend fun loadUrl(url: String) {
        if (!isValid) {
            logger.warn(LogCategory.BROWSER, "Cannot load URL - browser invalid", mapOf("handleId" to id))
            return
        }
        // Someone asked for this destination by name (URL bar, bookmark, deep link) rather
        // than clicking through to it. Only these four entry points can say how a navigation
        // started; anything reaching the handler without a hint came from the page.
        visitTracker.expect(BrowserNavigationType.TYPED)
        browser.navigation().loadUrl(url)
    }

    override suspend fun loadUrlAndWait(url: String) {
        if (!isValid) {
            logger.warn(LogCategory.BROWSER, "Cannot load URL - browser invalid", mapOf("handleId" to id))
            return
        }
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Boolean>()
            val sub = browser.navigation().on(LoadFinished::class.java) { done.complete(true) }
            try {
                browser.navigation().loadUrl(url)
                // Best-effort: returns null on timeout (no throw); real cancellation still propagates.
                withTimeoutOrNull(LOAD_TIMEOUT_MS) { done.await() }
            } finally {
                sub.unsubscribe()
            }
        }
    }

    override suspend fun executeJavaScript(script: String): Any? {
        if (!isValid) return null
        return withContext(Dispatchers.Main) {
            try {
                browser.mainFrame().map { it.executeJavaScript<Any?>(script) }.orElse(null)
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "JS execution error", mapOf("handleId" to id, "error" to (e.message ?: "unknown")))
                null
            }
        }
    }

    override fun getCurrentUrl(): String {
        if (!isValid) return ""
        return browser.url()
    }

    override fun getTitle(): String {
        if (!isValid) return ""
        return browser.title()
    }

    override fun addNavigationListener(listener: (String) -> Unit) {
        navigationListeners.add(listener)
    }

    override fun removeNavigationListener(listener: (String) -> Unit) {
        navigationListeners.remove(listener)
    }

    override fun addTitleListener(listener: (String) -> Unit) {
        titleListeners.add(listener)
    }

    override fun removeTitleListener(listener: (String) -> Unit) {
        titleListeners.remove(listener)
    }

    override fun addFaviconListener(listener: (String?) -> Unit) {
        faviconListeners.add(listener)
    }

    override fun removeFaviconListener(listener: (String?) -> Unit) {
        faviconListeners.remove(listener)
    }

    override fun goBack() {
        if (isValid && browser.navigation().canGoBack()) {
            visitTracker.expect(BrowserNavigationType.BACK_FORWARD)
            browser.navigation().goBack()
        }
    }

    override fun goForward() {
        if (isValid && browser.navigation().canGoForward()) {
            visitTracker.expect(BrowserNavigationType.BACK_FORWARD)
            browser.navigation().goForward()
        }
    }

    override fun reload() {
        if (isValid) {
            visitTracker.expect(BrowserNavigationType.RELOAD)
            browser.navigation().reload()
        }
    }

    override fun stop() {
        if (isValid) {
            browser.navigation().stop()
        }
    }

    override fun canGoBack(): Boolean = isValid && browser.navigation().canGoBack()

    override fun canGoForward(): Boolean = isValid && browser.navigation().canGoForward()

    // ============================================================
    // ZOOM CONTROLS
    // ============================================================

    override fun getZoomLevel(): Double {
        if (!isValid) return 1.0
        return browser.zoom().level().value()
    }

    override fun setZoomLevel(level: Double) {
        if (!isValid) return
        browser.zoom().level(ZoomLevel.of(level))
        notifyZoomListeners()
    }

    override fun zoomIn() {
        if (!isValid) return
        browser.zoom().`in`()
        notifyZoomListeners()
    }

    override fun zoomOut() {
        if (!isValid) return
        browser.zoom().out()
        notifyZoomListeners()
    }

    override fun resetZoom() {
        if (!isValid) return
        browser.zoom().reset()
        notifyZoomListeners()
    }

    override fun addZoomListener(listener: (Double) -> Unit) {
        zoomListeners.add(listener)
    }

    override fun removeZoomListener(listener: (Double) -> Unit) {
        zoomListeners.remove(listener)
    }

    private fun notifyZoomListeners() {
        val currentZoom = getZoomLevel()
        zoomListeners.forEach { listener ->
            try {
                listener(currentZoom)
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Zoom listener threw exception", error = e)
            }
        }
    }

    // ============================================================
    // LOADING STATE
    // ============================================================

    override fun isLoading(): Boolean = _isLoading

    override fun addLoadingListener(listener: (Boolean) -> Unit) {
        loadingListeners.add(listener)
    }

    override fun removeLoadingListener(listener: (Boolean) -> Unit) {
        loadingListeners.remove(listener)
    }

    // ============================================================
    // SECURITY
    // ============================================================

    override fun isSecure(): Boolean {
        if (!isValid) return false
        val url = browser.url()
        return url.startsWith("https://")
    }

    // ============================================================
    // CONTEXT MENU
    // ============================================================

    override fun setContextMenuCallback(callback: ContextMenuCallback?) {
        contextMenuCallback = callback
    }

    // ============================================================
    // POPUP AND NEW TAB HANDLING
    // ============================================================

    override fun setOpenInNewTabCallback(callback: (String) -> Unit) {
        openInNewTabCallback = callback
        setupPopupHandler()
    }

    override fun setOpenInNewTabWithDataCallback(callback: (PopupNavigation) -> Unit) {
        openInNewTabWithDataCallback = callback
        setupPopupHandler()
    }

    /**
     * Sets up JxBrowser popup handlers to route target="_blank" links and window.open()
     * calls to new tabs instead of spawning popup windows.
     *
     * How it works:
     * 1. CreatePopupCallback allows JxBrowser to create a temporary popup browser
     * 2. OpenPopupCallback intercepts before the popup is shown:
     *    - Empty bounds (Rect.empty()) indicates target="_blank" or cmd+click → route to new tab
     *    - Non-empty bounds indicates OAuth window or actual popup → allow to proceed
     */
    private fun setupPopupHandler() {
        // Phase 1: Allow popup browser creation
        browser.set(
            CreatePopupCallback::class.java,
            CreatePopupCallback {
                CreatePopupCallback.Response.create()
            },
        )

        // Phase 2: Handle popup display based on bounds
        // Based on the original BrowserFunctions.kt implementation
        browser.set(
            OpenPopupCallback::class.java,
            OpenPopupCallback { params ->
                val popupBrowser = params.popupBrowser()
                val initialBounds = params.initialBounds()
                val targetUrl = popupBrowser.url()

                // Check if popup has specific window dimensions
                val isEmptyBounds = initialBounds == Rect.empty()

                if (isEmptyBounds) {
                    // No dimensions = regular link (target="_blank", cmd+click, form.submit with target="_blank")
                    // Open as tab in BOSS instead of OS window. Race-resolve a destination URL and
                    // (for POST navigations) the upload body, then dispatch via the data-aware
                    // callback if registered, else the legacy URL-only one.
                    installUploadCallbackIfNeeded(popupBrowser.engine())
                    val captureDeferred = CompletableDeferred<PopupCapture?>()
                    pendingPopupCaptures[popupBrowser] = captureDeferred

                    val urlDeferred = CompletableDeferred<String>()
                    val cleanedUp = AtomicBoolean(false)
                    var subscription: Subscription? = null
                    val scope = CoroutineScope(Dispatchers.Default + Job())

                    fun resolveUrlIfReady() {
                        if (urlDeferred.isCompleted) return
                        val u =
                            try {
                                popupBrowser.url()
                            } catch (_: Exception) {
                                ""
                            }
                        if (u.isNotEmpty() && u != "about:blank") urlDeferred.complete(u)
                    }

                    if (targetUrl.isNotEmpty() && targetUrl != "about:blank") {
                        urlDeferred.complete(targetUrl)
                    } else {
                        subscription =
                            popupBrowser.navigation().on(LoadStarted::class.java) {
                                resolveUrlIfReady()
                            }
                    }

                    scope.launch {
                        try {
                            // Wait up to 3s for a real URL.
                            val url = withTimeoutOrNull(3_000) { urlDeferred.await() } ?: ""
                            // Brief grace period for the POST upload callback to fire after URL is known.
                            // For POST navigations the upload typically fires within tens of ms of LoadStarted.
                            val capture = withTimeoutOrNull(500) { captureDeferred.await() }

                            if (cleanedUp.compareAndSet(false, true)) {
                                subscription?.unsubscribe()
                                pendingPopupCaptures.remove(popupBrowser)
                                if (!popupBrowser.isClosed) {
                                    popupBrowser.close()
                                }
                            }

                            val finalUrl = capture?.url?.takeIf { it.isNotEmpty() } ?: url
                            if (finalUrl.isEmpty() || finalUrl == "about:blank") {
                                logger.warn(LogCategory.BROWSER, "Popup navigation produced no URL, dropping")
                                return@launch
                            }

                            val withDataCb = openInNewTabWithDataCallback
                            if (withDataCb != null) {
                                val nav =
                                    PopupNavigation(
                                        url = finalUrl,
                                        postData = capture?.body,
                                        contentType = capture?.contentType,
                                    )
                                withContext(Dispatchers.Main) { withDataCb(nav) }
                            } else {
                                withContext(Dispatchers.Main) { openInNewTabCallback?.invoke(finalUrl) }
                            }

                            logger.debug(
                                LogCategory.BROWSER,
                                "Popup dispatched",
                                mapOf(
                                    "url" to finalUrl,
                                    "hasPost" to (capture != null).toString(),
                                ),
                            )
                        } catch (e: Exception) {
                            if (cleanedUp.compareAndSet(false, true)) {
                                subscription?.unsubscribe()
                                pendingPopupCaptures.remove(popupBrowser)
                                if (!popupBrowser.isClosed) {
                                    popupBrowser.close()
                                }
                            }
                            logger.warn(LogCategory.BROWSER, "Popup handler error", error = e)
                        } finally {
                            scope.cancel()
                        }
                    }
                } else {
                    // Has dimensions = OAuth/payment popup (window.open with features)
                    // Create Swing window to display the popup browser
                    SwingUtilities.invokeLater {
                        try {
                            // Create JFrame for the popup
                            val frame = JFrame()
                            val subscriptions = mutableListOf<Subscription>()

                            frame.title = "Popup" // Will be updated by page title
                            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE

                            // Set position and size from bounds
                            frame.setLocation(initialBounds.origin().x(), initialBounds.origin().y())
                            frame.setSize(initialBounds.size().width(), initialBounds.size().height())

                            // Create BrowserView (Swing version) and add to frame
                            val browserView =
                                com.teamdev.jxbrowser.view.swing.BrowserView
                                    .newInstance(popupBrowser)
                            frame.contentPane.add(browserView)

                            // Replaces JxBrowser's built-in Swing context menu, which crashes the
                            // EDT here: it positions itself from getLocationOnScreen() inside an
                            // invokeLater, and an OAuth popup closes itself the moment the flow
                            // completes, so a right-click landing on that boundary asks a disposed
                            // component where it is (BossConsole-Releases#17).
                            installPopupWindowChrome(popupBrowser, browserView)

                            // Update frame title when page title changes
                            subscriptions +=
                                popupBrowser.on(TitleChanged::class.java) { event ->
                                    SwingUtilities.invokeLater {
                                        frame.title = event.title()
                                    }
                                }

                            // Close frame when browser closes
                            subscriptions +=
                                popupBrowser.on(BrowserClosed::class.java) {
                                    SwingUtilities.invokeLater {
                                        subscriptions.forEach { it.unsubscribe() }
                                        frame.dispose()
                                    }
                                }

                            // Close browser when frame closes
                            frame.addWindowListener(
                                object : java.awt.event.WindowAdapter() {
                                    override fun windowClosing(e: java.awt.event.WindowEvent?) {
                                        subscriptions.forEach {
                                            try {
                                                it.unsubscribe()
                                            } catch (_: Exception) {
                                                // Ignore errors during cleanup
                                            }
                                        }
                                        if (!popupBrowser.isClosed) {
                                            popupBrowser.close()
                                        }
                                    }
                                },
                            )

                            // Show the popup window
                            frame.isVisible = true
                        } catch (e: Exception) {
                            logger.error(LogCategory.BROWSER, "Error creating popup window", error = e)
                            if (!popupBrowser.isClosed) {
                                popupBrowser.close()
                            }
                        }
                    }
                }

                // Return proceed() to notify the engine we've handled the popup
                OpenPopupCallback.Response.proceed()
            },
        )

        logger.debug(LogCategory.BROWSER, "Popup handler configured", mapOf("handleId" to id))
    }

    // ============================================================
    // PICTURE IN PICTURE
    // ============================================================

    override fun requestPictureInPicture() {
        if (!isValid) return
        browser.mainFrame().ifPresent { frame ->
            try {
                frame.executeJavaScript<Unit>(BrowserJavaScripts.enablePictureInPicture)
                logger.debug(LogCategory.BROWSER, "Requested Picture-in-Picture mode")
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to request Picture-in-Picture", error = e)
            }
        }
    }

    // ============================================================
    // FULLSCREEN VIDEO SUPPORT
    // ============================================================

    override fun setFullscreenHandler(
        tabId: String,
        onEnterFullscreen: () -> Unit,
        onExitFullscreen: () -> Unit,
    ) {
        if (!isValid || tabId.isEmpty()) return

        FluckEngine.setupFullscreenHandler(
            browser = browser,
            tabId = tabId,
            ownerWindowId = ownerWindowId,
            onFullscreenEnter = {
                logger.info(LogCategory.BROWSER, "Tab entered fullscreen", mapOf("tabId" to tabId, "handleId" to id))
                onEnterFullscreen()
            },
            onFullscreenExit = {
                logger.info(LogCategory.BROWSER, "Tab exited fullscreen", mapOf("tabId" to tabId, "handleId" to id))
                onExitFullscreen()
            },
        )

        logger.debug(LogCategory.BROWSER, "Fullscreen handler configured", mapOf("tabId" to tabId, "handleId" to id))
    }

    override fun requestExitFullscreen() {
        FullscreenBrowserWindow.requestExit(browser)
    }

    // ============================================================
    // DEVELOPER TOOLS
    // ============================================================

    override fun showDevTools() {
        if (!isValid) return
        try {
            browser.devTools().show()
            logger.debug(LogCategory.BROWSER, "DevTools opened", mapOf("handleId" to id))
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to open DevTools", error = e)
        }
    }

    // ============================================================
    // SECRET AUTO-FILL
    // ============================================================

    override suspend fun fillCredentials(
        username: String,
        password: String,
        fillBoth: Boolean,
    ): Boolean {
        if (!isValid) return false
        return try {
            val mode =
                if (fillBoth) {
                    FormFieldInjector.FillMode.BOTH
                } else {
                    // Determine which field to fill based on focused field type
                    val focusedType =
                        browser
                            .mainFrame()
                            .map { frame ->
                                frame.executeJavaScript<String?>(
                                    """
                                    (function() {
                                        var el = document.activeElement;
                                        if (!el || el.tagName !== 'INPUT') return null;
                                        return el.type || 'text';
                                    })()
                                    """.trimIndent(),
                                )
                            }.orElse(null)

                    when (focusedType) {
                        "password" -> FormFieldInjector.FillMode.PASSWORD_ONLY
                        else -> FormFieldInjector.FillMode.USERNAME_ONLY
                    }
                }

            val lockedBrowser = createLockedBrowser()
            val result = FormFieldInjector.fillCredentials(lockedBrowser, username, password, mode)
            result is FormFieldInjector.FillResult.Success || result is FormFieldInjector.FillResult.PartialSuccess
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to fill credentials", error = e)
            false
        }
    }

    // ============================================================
    // CLIPBOARD OPERATIONS
    // ============================================================

    override fun copySelection() {
        if (!isValid) return
        browser.mainFrame().ifPresent { frame ->
            frame.executeJavaScript<Unit>("document.execCommand('copy')")
        }
    }

    override fun paste() {
        if (!isValid) return
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val clipboardText = clipboard.getData(DataFlavor.stringFlavor) as? String
            if (!clipboardText.isNullOrEmpty()) {
                browser.mainFrame().ifPresent { frame ->
                    val escapedText =
                        clipboardText
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n")
                            .replace("\r", "")
                    frame.executeJavaScript<Unit>(
                        """
                        (function() {
                            var el = document.activeElement;
                            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                                if (el.isContentEditable) {
                                    document.execCommand('insertText', false, '$escapedText');
                                } else {
                                    var start = el.selectionStart || 0;
                                    var end = el.selectionEnd || 0;
                                    var value = el.value || '';
                                    el.value = value.substring(0, start) + '$escapedText' + value.substring(end);
                                    el.selectionStart = el.selectionEnd = start + '$escapedText'.length;
                                    el.dispatchEvent(new Event('input', { bubbles: true }));
                                }
                            }
                        })()
                        """.trimIndent(),
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to paste from clipboard", error = e)
        }
    }

    override fun cut() {
        if (!isValid) return
        browser.mainFrame().ifPresent { frame ->
            frame.executeJavaScript<Unit>("document.execCommand('cut')")
        }
    }

    override fun selectAll() {
        if (!isValid) return
        browser.mainFrame().ifPresent { frame ->
            frame.executeJavaScript<Unit>("document.execCommand('selectAll')")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Content() {
        if (!isValid) {
            // Show nothing if browser is invalid
            return
        }

        // Create BrowserViewState on first composition
        // Retain the browser surface across tab switches in HARDWARE_ACCELERATED mode.
        //
        // The default lifecycle closes the surface whenever this composable leaves composition
        // (switching to another tab) and rebuilds it on return. For an off-screen bitmap that is
        // cheap; for a heavyweight GPU surface it tears down and re-initialises native resources,
        // and the tab paints BLANK on the way back (A->B->A). BossConsoleLite hit this on its
        // Windows fleet and calls it the "fast-switch blank".
        //
        // Retaining is gated on the rendering mode, NOT applied unconditionally as Lite does:
        // Lite defaults HARDWARE everywhere so the two are the same thing there, but here
        // OFF_SCREEN is still the macOS/Linux default and they must keep the exact lifecycle they
        // have today. Safe because the surface is still closed for real in dispose(), which runs
        // when the tab is actually closed rather than merely hidden.
        val retainSurfaceAcrossTabSwitches = shouldRetainSurface(JxBrowserConfig.renderingMode)

        // The window actually hosting this composition, resolved through the app's window
        // registry. In a multi-window setup the "first showing window" fallback can resolve a
        // different window than the one this view renders in, which would bind the view state and
        // the pinch gesture listener where the browser isn't (gesture events are delivered per
        // window). Keying the effect on the id also rebinds both when a tab moves across windows.
        //
        // Read HERE, above the seed, and not only inside the effect: the seed needs the same
        // window-identity guard, or the first frame renders a surface bound to the window the tab
        // came from and the effect then closes that very object while it is composed.
        val hostWindowId = LocalWindowId.current

        // Seeded from the retained surface so re-entry paints immediately instead of blank - but
        // only when it belongs to THIS window, matching the reuse condition in the effect below.
        var viewState by remember {
            mutableStateOf(
                currentViewState?.takeIf {
                    retainSurfaceAcrossTabSwitches &&
                        hostWindowId != null &&
                        currentViewStateWindowId == hostWindowId
                },
            )
        }

        // Tab visibility drives the active-time counter. Leaving composition means this tab
        // was hidden (the surface is retained; dispose() owns real closure), which is exactly
        // the moment engagement should stop accruing — a portal left open behind three other
        // tabs is not being read. Kept as its own effect rather than folded into the surface
        // effect below, which is keyed on the host window and would double-fire on a move.
        //
        // Window-level focus is deliberately not consulted here: a visible tab in a
        // background window still counts as active. WindowFocusEvent is reported separately,
        // so a consumer that cares can intersect the two.
        DisposableEffect(Unit) {
            visitTracker.setFocused(true)
            onDispose { visitTracker.setFocused(false) }
        }

        // Track last navigation time for debouncing mouse button navigation
        var lastNavigationTime by remember { mutableStateOf(0L) }

        DisposableEffect(browser, hostWindowId) {
            // Find a valid window to associate with the BrowserView
            val awtWindow =
                hostWindowId?.let { WindowFocusManager.getWindow(it) }
                    ?: Window.getWindows().firstOrNull { window ->
                        try {
                            window.isDisplayable && window.isShowing
                        } catch (e: Exception) {
                            // Window can be mid-disposal - treat as not a candidate
                            logger.debug(
                                LogCategory.BROWSER,
                                "Window state probe failed - skipping window",
                                mapOf("error" to e.toString()),
                            )
                            false
                        }
                    }

            // Reuse a retained surface ONLY while it still belongs to this window. This effect is
            // keyed on hostWindowId precisely so a tab moved to another window rebinds (see the
            // comment above); reusing unconditionally would short-circuit that rebind and leave
            // the surface — and the pinch-gesture listener — attached to the window the tab came
            // from. Retention is meant to survive hiding, not relocation.
            // hostWindowId != null is load-bearing, not defensive: with a null id the equality
            // check below is `null == null` for every window, so a surface would be reused after
            // the "first showing window" fallback had resolved a DIFFERENT window than it was
            // built against — exactly the case the hostWindowId key exists to catch. Without an
            // id we cannot prove the window is the same, so we rebuild rather than assume.
            val retained =
                currentViewState?.takeIf {
                    retainSurfaceAcrossTabSwitches &&
                        hostWindowId != null &&
                        currentViewStateWindowId == hostWindowId
                }
            if (retained != null) {
                // Coming back to a tab whose surface was kept alive - reuse it rather than
                // building a second one, which is the whole point of retaining.
                viewState = retained
            } else if (awtWindow != null) {
                // A retained surface bound to a different window must be closed, not orphaned:
                // nothing else will, since onDispose no longer closes while retaining.
                currentViewState?.let { stale ->
                    runCatching { stale.close() }
                        .onFailure {
                            logger.debug(
                                LogCategory.BROWSER,
                                "Closing a browser surface bound to a previous window failed",
                                mapOf("error" to it.toString()),
                            )
                        }
                    currentViewState = null
                }
                try {
                    val newState = BrowserViewState(browser, MainScope(), awtWindow)
                    viewState = newState
                    currentViewState = newState
                    currentViewStateWindowId = hostWindowId
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Failed to create BrowserViewState", error = e)
                }
            } else {
                logger.warn(LogCategory.BROWSER, "No valid window available for BrowserViewState")
            }

            // Set up the macOS trackpad pinch-to-zoom handler on the same window
            // the view is bound to. The gesture APIs only allow listening on a
            // Swing component, and the Compose BrowserView has no dedicated one,
            // so the listener sits on the window's root pane and receives pinches
            // made anywhere in that window. Two guards keep that from zooming the
            // wrong browser: callbacks are gated on the pointer actually hovering
            // this view, and the listener is removed when this view leaves
            // composition (hidden tab, closed split).
            var gesturePane: javax.swing.JComponent? = null
            var gestureToken: Any? = null
            if (awtWindow != null && MacOSGestureHandler.isSupported()) {
                try {
                    val rootPane = (awtWindow as? javax.swing.RootPaneContainer)?.rootPane

                    if (rootPane != null) {
                        gestureToken =
                            MacOSGestureHandler.addMagnificationListener(
                                rootPane,
                                onZoomIn = { gatedPinchZoom("in") { zoomIn() } },
                                onZoomOut = { gatedPinchZoom("out") { zoomOut() } },
                            )
                        if (gestureToken != null) {
                            gesturePane = rootPane
                            // The gate needs this window to place the pointer in the same
                            // coordinate space as the Compose bounds, so it is published only
                            // once a listener actually exists to be gated.
                            gestureHostWindow = awtWindow
                            logger.debug(LogCategory.BROWSER, "Added macOS pinch-to-zoom gesture handler")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Failed to set up pinch-to-zoom gestures", error = e)
                }
            }

            onDispose {
                pointerOverBrowserView = false
                // Both gate inputs must go stale together with the listener they gate.
                // A retained HARDWARE surface outlives this effect, so leaving stale
                // bounds behind would let a pinch aimed at whatever now occupies that
                // rectangle zoom a hidden tab.
                browserViewBoundsInWindow = null
                gestureHostWindow = null
                val pane = gesturePane
                val token = gestureToken
                if (pane != null && token != null) {
                    MacOSGestureHandler.removeMagnificationListener(pane, token)
                }
                // When retaining, leaving composition means "this tab was hidden", not "this tab
                // was closed" - so the surface stays alive and dispose() owns closing it. Compose
                // still detaches the heavyweight AWT component, so a hidden tab's surface is not
                // visible and cannot bleed through.
                if (!retainSurfaceAcrossTabSwitches) {
                    viewState?.close()
                    viewState = null
                    currentViewState = null
                    currentViewStateWindowId = null
                }
            }
        }

        // HARDWARE_ACCELERATED browser-surface vertical correction, tunable per install.
        //
        // In HARDWARE mode the heavyweight GPU surface can land higher than its Compose slot,
        // overlapping the chrome above it (the URL bar in a browser tab, the header in a plugin
        // panel) and leaving a matching gap at the bottom. offset(y) shifts the surface DOWN
        // without shrinking it — BossConsoleLite tried padding first and it shrank the surface,
        // leaving the bottom gap. This composable is the single chokepoint for every
        // BrowserHandle surface, so correcting it here fixes the browser tab and every
        // browser-hosting plugin at once.
        //
        // DEFAULT IS 0, deliberately different from Lite's 24. The misalignment is not universal:
        // measured on BossConsole on Windows 11 / 150% scaling (2026-07-31) with a marker page,
        // the surface is correctly flush under the URL bar at 0, and 24 introduces a visible gap
        // and pushes the page's bottom edge off-screen. Lite's fleet needed 24 — it is a
        // browser-only build with different chrome heights — so the amount belongs to the install,
        // not to the platform. Set BOSS_BROWSER_TOP_INSET_DP if a given machine shows the overlap.
        // OFF_SCREEN composites correctly and is always 0, so macOS and Linux are untouched.
        val hardwareTopInsetDp =
            remember {
                if (JxBrowserConfig.renderingMode == com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED) {
                    // ConfigLoader, not getenv: this is the per-INSTALL tuning knob (the amount
                    // depends on the machine's chrome heights and scaling), so it belongs in
                    // local.properties as much as in the environment.
                    parseTopInsetDp(
                        ai.rever.boss.config.ConfigLoader
                            .getConfig("BOSS_BROWSER_TOP_INSET_DP"),
                    )
                } else {
                    0
                }
            }

        // Read in composition, not once: LocalDensity follows the window across displays, and the
        // pinch gate compares AWT logical units against pixel bounds using it.
        val viewDensity = LocalDensity.current.density

        // Render the browser view if available with mouse button handling
        viewState?.let { state ->
            BrowserView(
                state = state,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .offset(y = hardwareTopInsetDp.dp)
                        // Where this view is, for the pinch gate under HARDWARE_ACCELERATED —
                        // the only signal available there, since a foreign native surface means
                        // Compose never reports the pointer entering. Clipped bounds, so a view
                        // scrolled half out of the window does not claim the hidden half.
                        .onGloballyPositioned { coords ->
                            browserViewBoundsInWindow = coords.boundsInWindow()
                            // Captured with the bounds, from the same layout pass, so the two can
                            // never describe different displays after a window is dragged between
                            // monitors. See pointerInsideBounds for why the pairing is required.
                            browserViewDensity = viewDensity
                        }
                        // Hover tracking that gates the window-wide pinch gesture listener to
                        // this view under OFF_SCREEN (see the DisposableEffect above). Never
                        // fires under HARDWARE_ACCELERATED; see shouldAllowPinch.
                        .onPointerEvent(PointerEventType.Enter) { pointerOverBrowserView = true }
                        .onPointerEvent(PointerEventType.Exit) { pointerOverBrowserView = false }
                        .onPointerEvent(PointerEventType.Press) { event ->
                            // Get the native AWT mouse event to check button codes
                            val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent

                            // Handle mouse back button - navigate back
                            // Windows/macOS: awtButton=4, Linux: awtButton=6 or 8 (varies by mouse)
                            if (awtEvent?.button in listOf(4, 6, 8)) {
                                val now = System.currentTimeMillis()
                                if (isValid && (now - lastNavigationTime) > 100 && canGoBack()) {
                                    lastNavigationTime = now
                                    goBack()
                                }
                                event.changes.forEach { it.consume() }
                                return@onPointerEvent
                            }

                            // Handle mouse forward button - navigate forward
                            // Windows/macOS: awtButton=5, Linux: awtButton=7 or 9 (varies by mouse)
                            if (awtEvent?.button in listOf(5, 7, 9)) {
                                val now = System.currentTimeMillis()
                                if (isValid && (now - lastNavigationTime) > 100 && canGoForward()) {
                                    lastNavigationTime = now
                                    goForward()
                                }
                                event.changes.forEach { it.consume() }
                                return@onPointerEvent
                            }
                        },
            )
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        // Flush the visit in progress before anything is torn down. This is the only place a
        // page's dwell time can be closed out when a tab is shut while still on a page —
        // every other path ends a visit by starting the next one.
        visitTracker.closed()
        FullscreenBrowserWindow.exitFullscreen(browser)

        // Stop co-browse capture so a disposed tab can never keep streaming.
        coBrowseCapturing = false
        coBrowseControlGranted = false
        coBrowseSink = null
        coBrowseBridge.onEvent = null
        coBrowseScope.cancel()
        // Stops queued menu lookups from starting. A lookup already blocked inside
        // executeJavaScript cannot be interrupted by cancellation — the delivery site
        // checks `disposed` before handing anything back. shutdown() (not shutdownNow())
        // for the same reason: the thread is daemon, so a wedged lookup cannot hold up
        // exit, and interrupting it would buy nothing.
        contextMenuScope.cancel()
        contextMenuExecutor.shutdown()
        if (coBrowseInjectRegistered) {
            try {
                browser.remove(InjectJsCallback::class.java)
            } catch (_: Exception) {
            }
            coBrowseInjectRegistered = false
        }

        // Unsubscribe from all events
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()

        // Clear listeners
        navigationListeners.clear()
        titleListeners.clear()
        faviconListeners.clear()
        loadingListeners.clear()
        zoomListeners.clear()

        // Close browser view state
        currentViewState?.close()
        currentViewState = null
        currentViewStateWindowId = null

        // Clean up find bar resources before closing browser
        FluckEngine.disposeBrowserFindBar(browser)

        // Close browser
        if (!browser.isClosed) {
            browser.close()
        }

        logger.debug(LogCategory.BROWSER, "Browser handle disposed", mapOf("handleId" to id))
    }

    /**
     * Captured details of a POST upload made by a popup browser, used to replay
     * the same POST when the popup is adopted as a new tab.
     */
    private data class PopupCapture(
        val url: String,
        val body: ByteArray,
        val contentType: String,
    )

    companion object {
        /**
         * Popup browsers we are currently waiting to capture an upload body for.
         * Populated by the popup handler before [BeforeSendUploadDataCallback] fires;
         * the callback completes the deferred and removes the entry.
         */
        private val pendingPopupCaptures =
            ConcurrentHashMap<Browser, CompletableDeferred<PopupCapture?>>()

        private val uploadCallbackInstalled = AtomicBoolean(false)
        private val staticLogger = BossLogger.forComponent("BrowserHandleImpl")

        /**
         * Runs history retraction off the engine's event thread. Deliberately not tied to
         * any handle's lifetime — a retraction triggered by the navigation that closed a
         * tab still has to complete — and one per process rather than one per browser.
         */
        private val retractionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Best-effort cap on [loadUrlAndWait]; returns (no throw) if a load runs long. */
        private const val LOAD_TIMEOUT_MS = 30_000L

        /**
         * How long a context menu waits for the form-field detail behind secret auto-fill
         * before opening without it. Bounds a blocking JS round-trip against a busy page.
         */
        private const val FORM_FIELD_LOOKUP_TIMEOUT_MS = 500L

        /**
         * How far back a failure retracts visits recorded by a callback that raced ahead
         * of it. Long enough to cover the title/load callbacks for the navigation that
         * just failed, short enough that a genuine earlier visit to the same address is
         * never mistaken for one.
         */
        private const val RACE_RETRACTION_MS = 5_000L

        /**
         * Network errors that mean the address itself is wrong rather than temporarily
         * unreachable — a typo like `youtube.como` resolves to nothing, and no retry will
         * change that. Only these can evict history entries regardless of age, and only
         * while name resolution is otherwise working; see [recordNavigationOutcome].
         */
        private val ADDRESS_DOES_NOT_EXIST_ERRORS =
            setOf(
                NetError.NAME_NOT_RESOLVED,
                NetError.NAME_RESOLUTION_FAILED,
                NetError.ADDRESS_INVALID,
                NetError.INVALID_URL,
                NetError.UNKNOWN_URL_SCHEME,
                NetError.DISALLOWED_URL_SCHEME,
            )

        /**
         * Install an engine-wide [BeforeSendUploadDataCallback] that captures
         * POST bodies for popup browsers we're tracking. Idempotent — installs once.
         *
         * The callback proceeds unchanged for every request; it only diverts when
         * a request originates from a browser registered in [pendingPopupCaptures].
         * That popup is closed before its upload is sent, so the captured POST
         * is replayed exactly once from the adopted new tab.
         */
        private fun installUploadCallbackIfNeeded(engine: Engine) {
            if (!uploadCallbackInstalled.compareAndSet(false, true)) return
            try {
                engine.network().set(
                    BeforeSendUploadDataCallback::class.java,
                    BeforeSendUploadDataCallback { params ->
                        try {
                            val req = params.urlRequest()
                            val popupBrowser = req.browser().orElse(null)
                            if (popupBrowser != null) {
                                val deferred = pendingPopupCaptures.remove(popupBrowser)
                                if (deferred != null) {
                                    val bytes = params.uploadData().bytes() ?: ByteArray(0)
                                    val contentType =
                                        params
                                            .httpHeaders()
                                            .firstOrNull { it.name().equals("Content-Type", ignoreCase = true) }
                                            ?.value()
                                            ?: "application/x-www-form-urlencoded"
                                    deferred.complete(PopupCapture(req.url(), bytes, contentType))
                                }
                            }
                        } catch (e: Exception) {
                            staticLogger.warn(LogCategory.BROWSER, "Upload capture failed", error = e)
                        }
                        BeforeSendUploadDataCallback.Response.proceed()
                    },
                )
                staticLogger.debug(LogCategory.BROWSER, "BeforeSendUploadDataCallback installed")
            } catch (e: Exception) {
                uploadCallbackInstalled.set(false)
                staticLogger.warn(LogCategory.BROWSER, "Failed to install upload callback", error = e)
            }
        }
    }
}

/**
 * Whether a browser surface should survive its composable leaving composition.
 *
 * Only under HARDWARE_ACCELERATED. There, leaving composition means "this tab was hidden", and
 * closing the heavyweight GPU surface would make the tab paint blank when the user comes back
 * (A->B->A). Under OFF_SCREEN the surface is a cheap CPU bitmap and the original close-on-hide
 * lifecycle is kept, so macOS and Linux behave exactly as they did.
 *
 * Split out as a pure function so the platform decision is pinned by a test rather than by
 * reading an inline expression buried in a composable.
 */
internal fun shouldRetainSurface(mode: com.teamdev.jxbrowser.engine.RenderingMode): Boolean =
    mode == com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED

/**
 * Whether an AWT pointer falls inside a Compose-measured rect, given the display density.
 *
 * **The two inputs are in different coordinate SCALES and this function exists to reconcile
 * them.** `LayoutCoordinates.boundsInWindow()` reports DEVICE PIXELS;
 * `MouseInfo.getPointerInfo()` through `SwingUtilities.convertPointFromScreen` reports AWT
 * LOGICAL UNITS, which equal dp in Compose Desktop. They coincide only at density 1.0, so a
 * naive `bounds.contains(pointer)` is correct on an unscaled external monitor and wrong by the
 * density factor on the laptop panel — 2x on Retina, 1.5x at 150% Windows scaling.
 *
 * Concretely, at density 2.0 with the browser occupying window px `(0,100)-(2000,1300)`
 * (logical `(0,50)-(1000,650)`) and a terminal split beneath it: a pointer at logical
 * `(500,700)` is over the TERMINAL, but compared raw it satisfies both `700 < 1300` and
 * `500 < 2000` and the gate opens — zooming a browser the pointer is not over, which is the
 * one thing the gate is for. The mirror case refuses a pinch that is genuinely inside a
 * right-hand split.
 *
 * The same trap is documented for the overlay work in `HeavyweightPopup` ("onGloballyPositioned
 * reports PIXELS, while the window bounds and the offsets are AWT logical units"), which is
 * where the convention of naming the space in the parameter name comes from.
 *
 * Pure, so the density cases are a table test rather than something only a Retina machine
 * could catch.
 */
internal fun pointerInsideBounds(
    boundsPx: androidx.compose.ui.geometry.Rect,
    pointerLogical: androidx.compose.ui.geometry.Offset,
    density: Float,
): Boolean = boundsPx.contains(pointerLogical * density)

/**
 * Whether a macOS pinch gesture should zoom THIS browser.
 *
 * The gesture listener is registered on the window's root pane, because Apple's
 * GestureUtilities can only listen on a Swing component and there is no per-browser
 * one. So every browser in a window hears every pinch, and something has to decide
 * which one the user meant. That something used to be Compose hover — and hover is
 * exactly what HARDWARE_ACCELERATED takes away.
 *
 * Why: under HARDWARE the browser is not a component inside the Compose scene at all.
 * JxBrowser's `WindowedWidgetState` attaches Chromium's own native window to the AWT
 * window's native handle and positions it from Compose layout coordinates — so macOS
 * delivers mouse movement to that foreign window, Compose never sees
 * `PointerEventType.Enter`, and `pointerOverBrowserView` stays false for the lifetime
 * of the tab. Gating on it there means pinch never fires, with nothing but a debug
 * line to say so. That is the regression this function exists to prevent.
 *
 * The substitute is geometry: Compose still knows where the view is, and the pointer
 * location is readable from AWT, so "is the pointer inside this view" is answerable
 * without the pointer ever having to enter it. [pointerInsideBounds] is nullable
 * because that answer can be genuinely unavailable (pre-layout, no window, headless),
 * and an unknown is treated as NO — a pinch that does nothing is recoverable by
 * pinching again, whereas one that zooms an unpointed browser in another split is a
 * change the user did not ask for and may not notice.
 *
 * OFF_SCREEN deliberately keeps using hover rather than adopting the geometry check.
 * It is a real component there, hover is accurate and cheap, and this flip should not
 * be able to regress the platforms that already worked.
 *
 * Pure so the decision is pinned by tests; the impure pointer read stays at the call
 * site (see `pointerInsideBrowserView`).
 */
internal fun shouldAllowPinch(
    mode: com.teamdev.jxbrowser.engine.RenderingMode,
    isValid: Boolean,
    pointerOverComposeView: Boolean,
    pointerInsideBounds: Boolean?,
): Boolean {
    if (!isValid) return false
    return if (mode == com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED) {
        pointerInsideBounds == true
    } else {
        pointerOverComposeView
    }
}

/**
 * Parse BOSS_BROWSER_TOP_INSET_DP into a usable vertical correction for the browser surface.
 *
 * Clamped to 0..200 rather than taken at face value. This offset moves a heavyweight native
 * surface inside its slot with no visible error reporting, so a stray negative would shift the page
 * up under the toolbar and a stray large value would push it off the bottom — in both cases looking
 * like a rendering bug rather than a mistyped setting. 200dp is far beyond any real chrome height,
 * so the ceiling only ever catches nonsense.
 *
 * Unparseable or unset means 0, which is the correct default on the machine this was measured on
 * (see benchmarks/speedometer/win/WINDOWS.md — Lite's 24 over-corrects here).
 *
 * Restart-scoped: read once per browser view, not live-tunable.
 */
internal fun parseTopInsetDp(raw: String?): Int = raw?.trim()?.toIntOrNull()?.coerceIn(0, 200) ?: 0
