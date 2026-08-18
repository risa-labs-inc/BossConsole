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
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.teamdev.jxbrowser.frame.EditorCommand
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
import java.awt.Window
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * - Editable is reported for the main frame only, and `fillCredentials` is why it stays that
 *   way: it runs against `browser.mainFrame()` and `document.activeElement`, so offering it
 *   for a field inside an iframe could write a password into whatever main-frame input
 *   happens to be focused. Frame-accurate detection has to wait for a frame-accurate fill
 *   path. `cut`/`copySelection`/`paste`/`selectAll` no longer share that limit — they go
 *   through [BrowserHandleImpl.editorCommand], which targets `focusedFrame()` — but this gate
 *   still decides whether the menu offers them at all, so widening it is what would actually
 *   put them in reach inside an iframe.
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
    private val visitTracker = BrowserVisitTracker(windowId = { currentWindowId })

    /**
     * The window this tab is currently in, as opposed to the one it was created in.
     *
     * A tab moves between windows — `Content()` resolves `LocalWindowId.current` and the
     * surface effect below exists precisely because "the window the tab came from" and the
     * window it is composing in can differ. Stamping telemetry with [ownerWindowId] therefore
     * kept attributing a moved tab's dwell, depth and tab counts to the window it left, and
     * `BrowserVisitTrackerTest` asserts that per-window attribution is load-bearing. Read at
     * emit time for the same reason [currentPageAuthority] is: the value at construction is
     * not the value when the event happens.
     */
    @Volatile private var currentWindowId: String = ownerWindowId

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
            windowId = { currentWindowId },
        )

    private val disposed = AtomicBoolean(false)

    /**
     * Whether [Content] has ever been composed for this handle.
     *
     * Separates "this tab is appearing for the first time" from "this tab is being shown again"
     * so the focus effect in [Content] can act on the second and not the first. Deliberately not
     * derived from the retained-surface state: retention answers whether the *surface* survived,
     * which is a different question and is false on the very first show for the same mode.
     */
    private val shownBefore = AtomicBoolean(false)

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

    /**
     * Bumped to re-attach the browser view after a committed document never drew. Read in
     * [Content], so it has to be Compose state rather than a plain field.
     *
     * See [BrowserFrameStall] for the measurements behind this.
     */
    private var viewGeneration by mutableStateOf(0)

    /**
     * How many compositions currently show this handle's view.
     *
     * **Ref-counted, not a boolean, for the reason [BrowserVisitTracker.setVisible] spells out**:
     * the caller is a `DisposableEffect` per composition, and a tab moving between windows tears
     * down one composition while building another in an order the effect does not control. As a
     * plain boolean the compose-then-dispose order lands on false while the tab is on screen, and
     * here that would silently disable the watchdog for exactly the tab the user just dragged,
     * until they hid and re-showed it. Failing safe is not enough when the failure is invisible.
     *
     * The gate itself is needed because Chromium serves no frames to a view that is not on screen:
     * a commit in a background tab, or in a handle a plugin created and never rendered, reads "no
     * frame" perfectly correctly, and re-attaching it repairs nothing while logging a warning that
     * is false. Session restore with N background tabs would be N false warnings per launch.
     */
    private val composedSurfaces = AtomicInteger(0)

    /**
     * The AWT window this handle's view is bound to, for the on-screen half of [viewComposed].
     * Null until [Content] resolves one, and treated as "assume showing" while it is.
     */
    @Volatile private var frameStallHostWindow: Window? = null

    /**
     * Whether this view is genuinely on screen: composed **and** in a window that is showing and
     * not minimized.
     *
     * Composition alone is not the same thing. A minimized window keeps its composition alive, so
     * its foreground tab would pass a composed-only gate while Chromium is legitimately not
     * painting it - and the cost of that false positive is not merely a wasted WARN but a view
     * rebuild, which drops keyboard focus and IME state in that tab.
     */
    private val viewComposed: Boolean
        get() {
            if (composedSurfaces.get() <= 0) return false
            val window = frameStallHostWindow ?: return true
            // Fully qualified: this file already imports JxBrowser's Frame, and an unqualified
            // one here resolves to that rather than the AWT window type.
            return runCatching {
                val minimized =
                    (window as? java.awt.Frame)
                        ?.let { it.extendedState and java.awt.Frame.ICONIFIED != 0 }
                        ?: false
                window.isShowing && !minimized
            }.getOrDefault(true)
        }

    /** Cooldown, give-up counting and the log-once flag, extracted so they are unit-testable. */
    private val frameStallPolicy = FrameStallPolicy()

    /**
     * Waits on the frame-stall probe. Separate from [coBrowseScope] so a co-browse teardown cannot
     * cancel a pending check, and on Default so [withTimeoutOrNull] can actually fire - the
     * blocking round-trip itself runs on [frameProbeDispatcher], never here and never on the EDT.
     *
     * Carries a handler for the same reason [contextMenuScope] does: the body hops to Main during
     * what may be window teardown, and an escaping failure would otherwise reach the default
     * handler and print to stderr, bypassing BossLogger.
     */
    private val frameStallScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    logger.warn(LogCategory.BROWSER, "Frame-stall check failed", error = error)
                },
        )

    /**
     * Off-thread executor for the beacon round-trip, mirroring [contextMenuLookupDispatcher] and
     * for the same reason spelled out there: `executeJavaScript` blocks and nothing can interrupt
     * it, so against a wedged renderer this costs one parked daemon thread rather than a frozen
     * UI. That matters more here than for the context menu - this probe exists to interrogate a
     * page already suspected of misbehaving, and it runs on every http(s) commit.
     */
    private val frameProbeExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "boss-frame-probe-$id").apply { isDaemon = true }
        }
    private val frameProbeDispatcher = frameProbeExecutor.asCoroutineDispatcher()

    /**
     * The in-flight check, swapped atomically.
     *
     * A redirect chain fires NavigationFinished repeatedly and only the document that finally
     * sticks is worth judging. `getAndSet` rather than read-cancel-assign so that does not rest on
     * an assumption about JxBrowser delivering navigation events on one thread.
     */
    private val frameStallJob = AtomicReference<Job?>(null)

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
            // OTHER, not the LINK the tracker falls back to. Every tab starts here - new
            // tabs, restored sessions, bookmarks, deep links, a link opened in a new tab -
            // so leaving it unhinted would file the largest share of all page views under
            // "clicked through from the previous page", which is the one thing it is not.
            visitTracker.expect(BrowserNavigationType.OTHER)
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

    /**
     * Read the frame beacon, or null if the round-trip could not be made in time.
     *
     * The blocking call is confined to [frameProbeDispatcher] and only the *wait* is bounded, so a
     * renderer that never answers parks that one daemon thread instead of anything shared. Null
     * and "0" are kept apart on purpose: see [BrowserFrameStall.isStalled].
     */
    private suspend fun readFrameBeacon(): String? {
        if (!isValid) return null
        // Raced rather than wrapped, exactly as the context-menu lookup is: executeJavaScript has
        // no suspension point, so a cancelled withTimeoutOrNull could not interrupt it.
        val probe =
            frameStallScope.async(frameProbeDispatcher) {
                runCatching {
                    browser.mainFrame().orElse(null)?.executeJavaScript<String?>(BrowserFrameStall.BEACON_SCRIPT)
                }.getOrNull()
            }
        // The probe is a child of the scope, not of this coroutine, so neither the timeout below
        // nor a supersede cancels it. Left alone, one still queued behind a blocked probe on the
        // single-thread executor would run later against whatever document is current by then -
        // arming THAT document's beacon ahead of its own ARM_DELAY_MS, so its first real reading
        // is no longer the reading the constants describe.
        //
        // In a finally, not after the call. `withTimeoutOrNull` swallows only its own
        // TimeoutCancellationException: when the enclosing job is cancelled instead - the
        // supersede at the end of scheduleFrameStallCheck, or dispose - `probe.await()` rethrows
        // that CancellationException and unwinds straight past any cleanup placed after it. The
        // earlier `if (reading == null) probe.cancel()` therefore covered the timeout and missed
        // both cancellation paths, which are the ones the paragraph above is about.
        //
        // A queued coroutine honours the cancel immediately; one already inside executeJavaScript
        // is unaffected either way, which is the parked-thread cost frameProbeExecutor documents.
        return try {
            withTimeoutOrNull(BrowserFrameStall.PROBE_TIMEOUT_MS) { probe.await() }
        } finally {
            if (!probe.isCompleted) probe.cancel()
        }
    }

    /** Monotonic, not wall clock: an NTP step must not stretch or shorten the cooldown. */
    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Ask whether a re-attach is allowed, recording the attempt when it is.
     *
     * Answers with the *reason* rather than a boolean, because the two refusals are not
     * interchangeable: a cooldown is worth waiting out and a give-up is not. See
     * [FrameStallPolicy.claimOrDefer] for why deciding both in one place matters.
     *
     * Bounded like `EngineWedgeDetector`'s recycle: past the cap the handle degrades to the
     * pre-existing behaviour (the user switches tabs themselves) rather than flickering forever.
     *
     * A handle which has given up still probes on every commit. That reads like waste and is not:
     * [FrameStallPolicy.recordHealthyNavigation] is only reachable from a reading, so probing is
     * the only way a retired tab can ever come back. Giving up means "stop re-attaching", not
     * "stop looking".
     */
    private fun claimReattachSlot(): FrameStallPolicy.Claim {
        val claim = frameStallPolicy.claimOrDefer(monotonicNowMs())
        if (claim is FrameStallPolicy.Claim.Refused && claim.firstRefusal) {
            logger.warn(
                LogCategory.BROWSER,
                "Re-attaching stopped helping - leaving this tab alone",
                mapOf(
                    "ineffectiveInARow" to frameStallPolicy.ineffectiveInARow.toString(),
                    "attemptsTotal" to frameStallPolicy.attempts.toString(),
                ),
            )
        }
        return claim
    }

    /**
     * Record a page that painted without help, and say so if this tab had been given up on.
     *
     * Shared by the decision loop and the deferred retry so the revival line cannot go missing on
     * one of them - a log that says a tab was abandoned and never says it came back is the exact
     * asymmetry the line exists to prevent.
     */
    private fun notePaintedUnaided() {
        // Read before the reset, since the reset is what clears it.
        val wasRetired = frameStallPolicy.hasGivenUp
        frameStallPolicy.recordHealthyNavigation()
        if (wasRetired) {
            logger.info(
                LogCategory.BROWSER,
                "Tab painted unaided - frame-stall watchdog active again",
                mapOf("attemptsTotal" to frameStallPolicy.attempts.toString()),
            )
        }
    }

    /**
     * Claim a re-attach slot, waiting out the cooldown once rather than abandoning the page.
     *
     * A stall detected inside the cooldown used to end the job silently, so a follow-up query
     * within about 7.5s of a repaired one sat blank with nothing in the log to explain it. The
     * rate limit is still honoured - this waits for the remainder instead of shortening it - and
     * only one deferral is attempted, so a tab navigating in a tight loop cannot queue them up.
     *
     * Deferring costs the user the wait: worst case the page stays blank for the 2500ms decision
     * plus the remaining cooldown, so up to about 12.5s before it snaps in. That is the deliberate
     * price of not rebuilding the view more often than the rate limit allows.
     *
     * Only a cooldown is waited out. A give-up is refused immediately, which is what keeps a
     * retired tab from being deferred and then silently un-retired by the re-judge below.
     */
    private suspend fun claimReattachSlotWaiting(): Boolean =
        when (val claim = claimReattachSlot()) {
            is FrameStallPolicy.Claim.Now -> true
            is FrameStallPolicy.Claim.After -> claimAfterCooldown(claim.waitMs)
            is FrameStallPolicy.Claim.Refused -> false
        }

    private suspend fun claimAfterCooldown(remainingMs: Long): Boolean {
        // INFO, not DEBUG: the default level is INFO, so a DEBUG line here would leave a page
        // blank for up to another 10s with nothing in a shipped log to say why - the same silence
        // this change set out to remove.
        logger.info(
            LogCategory.BROWSER,
            "Frame-stall repair deferred until the cooldown expires",
            mapOf("remainingMs" to remainingMs.toString()),
        )
        delay(remainingMs)
        // Re-judged rather than claimed blind: the wait is long enough for the page to have
        // started painting on its own, or for the view to be hidden or the handle disposed.
        //
        // Tri-state, because isStalled(null) is false and an unanswered probe would otherwise be
        // credited as "painted while we waited" - clearing the ineffective run in exactly the
        // wedged-renderer case the cap exists for, and doing it on the population most likely to
        // stop answering, since this path is only reached for a tab already known stalled.
        if (!viewComposed) return false
        val stillWorthRepairing =
            when (BrowserFrameStall.repairOutcome(readFrameBeacon())) {
                false -> {
                    true
                }

                true -> {
                    notePaintedUnaided()
                    false
                }

                // Unknown: no evidence either way, so leave the ineffective run untouched.
                null -> {
                    false
                }
            }
        return stillWorthRepairing && claimReattachSlot() is FrameStallPolicy.Claim.Now
    }

    /**
     * Watch a freshly committed document and re-attach the view if it never draws.
     *
     * The repair is the same one a user performs by switching tabs: bumping [viewGeneration]
     * takes the browser view out of composition and puts it back, which re-attaches the native
     * view and restarts frame production. Verified end to end - a blanked AI Mode page goes from
     * 0 rAF callbacks to painting normally. See [BrowserFrameStall].
     */
    private fun scheduleFrameStallCheck(url: String?) {
        if (!BrowserFrameStall.shouldWatch(url, JxBrowserConfig.renderingMode)) return
        val started =
            frameStallScope.launch {
                // The first call arms the beacon and always answers "not painted", so it is not a
                // reading and is not treated as one. See BrowserFrameStall.ARM_DELAY_MS.
                delay(BrowserFrameStall.ARM_DELAY_MS)
                if (!viewComposed) return@launch
                // A null here means the arm never landed - the probe timed out, the main frame was
                // gone mid-commit. Carrying on would silently spend the first loop iteration
                // arming, leaving one real reading where the design promises two, and it would do
                // so against exactly the slow renderers most likely to be honestly mid-paint. A
                // commit that could not be probed at all is not evidence of a stall.
                if (readFrameBeacon() == null) return@launch

                // Two real readings. A page still blocked on a render-blocking resource can
                // honestly have drawn nothing at the first one.
                repeat(2) {
                    delay(BrowserFrameStall.READ_GAP_MS)
                    if (!viewComposed) return@launch
                    // Tri-state, not isStalled: that reads null as "not stalled", so an
                    // unanswered probe would be credited as a page painting unaided and would
                    // clear a legitimate ineffective run on no evidence at all.
                    when (BrowserFrameStall.repairOutcome(readFrameBeacon())) {
                        true -> {
                            // Painted unaided, so this tab is evidently fine - let that decay any
                            // earlier ineffective attempts rather than holding them against it.
                            notePaintedUnaided()
                            return@launch
                        }

                        // Unknown: not evidence of a stall, and not evidence of health either, so
                        // stop without touching the run.
                        null -> {
                            return@launch
                        }

                        // Still blank; keep going and take the second reading.
                        false -> {
                            Unit
                        }
                    }
                }

                if (!claimReattachSlotWaiting()) return@launch
                // Booked as ineffective before the repair, upgraded only by an observed recovery.
                // Everything that can end this job before the confirmation - a fresh commit
                // superseding it, the view leaving composition, a probe that never answers - would
                // otherwise leave the attempt uncounted, and a tab that keeps reading unpainted
                // would re-attach every cooldown forever without ever reaching the cap.
                frameStallPolicy.recordAttemptPending()
                logger.warn(
                    LogCategory.BROWSER,
                    "Committed page served no frame - re-attaching the browser view",
                    mapOf(
                        "url" to LogSanitizer.maskUriParams(url.orEmpty()),
                        "attempt" to frameStallPolicy.attempts.toString(),
                    ),
                )
                withContext(Dispatchers.Main) { viewGeneration += 1 }

                // Did the repair take? This both feeds the give-up counter and is the one datum
                // worth having if this ever needs escalating to TeamDev, since it is a recovery
                // for a fault we do not own.
                //
                // The beacon is per-document and already armed, so it keeps reporting the same
                // document across the re-attach - which is exactly what makes this readable as
                // "did re-attaching start frames for THIS page".
                delay(BrowserFrameStall.READ_GAP_MS)
                // Gated like the decision reads, and for the same reason: a tab hidden inside this
                // window reads "0" because Chromium does not paint hidden pages, and recording that
                // as an ineffective repair would retire the watchdog on a false reading.
                if (!viewComposed) return@launch
                // Three outcomes, not two. isStalled(null) is false, so reusing it here would read
                // a probe that never answered as a success - and credit the repair in exactly the
                // wedged-renderer case the cap exists to stop. See BrowserFrameStall.repairOutcome.
                val outcome = BrowserFrameStall.repairOutcome(readFrameBeacon())
                if (outcome == true) frameStallPolicy.recordRecovered()
                val detail =
                    mapOf(
                        "attempt" to frameStallPolicy.attempts.toString(),
                        "ineffectiveInARow" to frameStallPolicy.ineffectiveInARow.toString(),
                    )
                when (outcome) {
                    true -> logger.info(LogCategory.BROWSER, "Browser view re-attached and painting", detail)

                    false -> logger.info(LogCategory.BROWSER, "Browser view re-attached and still not painting", detail)

                    // Counted against the tab by recordAttemptPending, deliberately: an
                    // unanswerable probe is evidence of a renderer this cannot repair.
                    null -> logger.info(LogCategory.BROWSER, "Browser view re-attached, outcome unknown", detail)
                }
            }
        // Supersede rather than stack: only the document that finally sticks is worth judging.
        frameStallJob.getAndSet(started)?.cancel()
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
                    // Same-document navigations (pushState, fragment) keep the document, and the
                    // beacon is armed once per document and latches at painted. Probing one reads
                    // the "1" the ORIGINAL load left behind, which is not evidence about this
                    // navigation at all - and it reaches recordHealthyNavigation, clearing a
                    // legitimate ineffective run on a stale reading. See BrowserFrameStall for the
                    // mirror case this leaves open.
                    if (!event.isSameDocument) scheduleFrameStallCheck(url)
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
                    //
                    // BOTH gates apply to the interaction path too, not just to the page view.
                    // Chromium commits an error page as a real document, so the collector runs
                    // inside it and finds an authority — and clicking "Reload" on the error page
                    // for a mistyped host then reported a CLICK on a domain the user never
                    // reached. Failing the load clears the authority rather than merely skipping
                    // the page view.
                    val landed = !NavigationOutcomeTracker.didFail(url)
                    val host = suggestableHost(url)
                    val authority = host?.takeIf { landed }
                    currentPageAuthority = authority
                    if (authority != null) {
                        visitTracker.pageViewed(authority)
                    } else {
                        // The tracker still has to be told, even though there is nothing to
                        // report. Skipping it left the previous visit open, so its dwell and
                        // active time kept accruing while the user sat on the error page and
                        // were then billed to that previous domain, left the depth run
                        // unbroken, and left the failed load's navigation hint to relabel the
                        // next link click. The raw host goes with it only so TAB_CLOSED can
                        // still say where the tab was.
                        visitTracker.leftTrackablePage(host)
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

        // Answer the page's own file dialogs with the OS ones. This must run before the
        // browser view is composed: JxBrowser's view installs a Swing JFileChooser for each
        // of these and skips any callback already set. See NativeFileDialogs.
        NativeFileDialogs.installOn(browser)

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
        // Belt and braces with the guard in BrowserAnalytics: that one stops any event
        // reaching the bus, this one stops the collector running in the page at all, which
        // is the part a deployment can actually observe from inside a site.
        if (!BrowserAnalytics.telemetryEnabled) return
        try {
            val window = frame.executeJavaScript<JsObject>("window")
            window?.putProperty(BrowserInteractionScript.BRIDGE_PROPERTY, interactionBridge)
            frame.executeJavaScript<Any?>(BrowserInteractionScript.source)
        } catch (e: Exception) {
            // The exception CLASS, not its message. JxBrowser is unlikely to put a URL in
            // one, but this file's whole premise is that page-level detail never reaches a
            // log line, and a class name loses nothing diagnostically.
            logger.debug(
                LogCategory.BROWSER,
                "Interaction collector injection failed",
                mapOf("handleId" to id, "error" to (e::class.simpleName ?: "Exception")),
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

    /**
     * Generation first, and `isClosed` last and guarded, because the two are not equally
     * trustworthy.
     *
     * `Browser.isClosed` only reports what this side has been *told*. Closing an engine whose
     * IPC has already died leaves its browsers unmarked - the notification has no channel to
     * arrive on - so a browser belonging to a closed engine keeps answering `false` while every
     * call through it throws ObjectClosedException. The generation is decided locally by
     * [FluckEngine] and cannot lie in that direction, so it is the load-bearing clause.
     *
     * Guarded, because this is read from `if (isValid)` at the top of nearly every method here:
     * a throw out of the getter would escape those methods into plugin code, which is the exact
     * failure it exists to prevent.
     */
    override val isValid: Boolean
        get() =
            !disposed.get() &&
                FluckEngine.currentEngineGeneration == engineGeneration &&
                runCatching { !browser.isClosed }.getOrDefault(false)

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
        // Same user action as loadUrl, so the same hint. Missing it here filed every
        // wait-for-load navigation under LINK.
        visitTracker.expect(BrowserNavigationType.TYPED)
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
                            frame.iconImages = BossWindowIcon.images

                            // Set position and size from bounds
                            frame.setLocation(initialBounds.origin().x(), initialBounds.origin().y())
                            frame.setSize(initialBounds.size().width(), initialBounds.size().height())

                            // A popup browser has no handle of its own, so it never went
                            // through setupBrowserHandlers. Claim its file dialogs before the
                            // Swing view below installs the JFileChooser ones - an OAuth or
                            // payment popup is exactly where an upload field turns up.
                            NativeFileDialogs.installOn(popupBrowser)

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
        editorCommand(EditorCommand.copy(), "Copy")
    }

    override fun paste() {
        editorCommand(EditorCommand.paste(), "Paste")
    }

    override fun cut() {
        editorCommand(EditorCommand.cut(), "Cut")
    }

    override fun selectAll() {
        editorCommand(EditorCommand.selectAll(), "Select All")
    }

    /**
     * Runs [command] on the frame that holds the caret, and reports whether Chromium took it.
     *
     * These four used to be `frame.executeJavaScript("document.execCommand(...)")`, which was
     * wrong three separate ways and produced the "copy works sometimes" report:
     *
     *  - **`execCommand('copy'|'cut')` needs transient user activation.** A right-click grants
     *    it, but it lapses after roughly five seconds, so picking the item straight away copied
     *    and picking it after reading the menu silently did nothing. Nothing about the two
     *    attempts looked different to the user, which is what made it read as flakiness rather
     *    than as a rule.
     *  - **It only ever reached `mainFrame()`,** so a selection inside an iframe copied nothing.
     *  - **The answer was discarded** by the `<Unit>` type argument: `execCommand` returns a
     *    boolean, and a refusal was indistinguishable from a success.
     *
     * `Frame.execute` drives Chromium's own editor instead. It has no activation requirement,
     * and it reads and writes the real system clipboard rather than splicing a string into
     * `document.activeElement` from JS — which is what makes a copy here paste in another tab,
     * in a terminal, or in another application. It also fires the `beforeinput`/`paste` events
     * that framework-managed inputs listen for; the old paste bypassed them, so a React or Vue
     * field could show text its own state never learned about.
     *
     * [Browser.focusedFrame] first and `mainFrame()` only as a fallback: the caret is what an
     * editor command acts on, and it routinely sits in a subframe (an embedded editor, an OAuth
     * form). Note the menu that offers these is still main-frame-gated upstream — see
     * `toContextMenuInfo` — so today the fallback is the common path and the preference is what
     * lets the gate be widened later without revisiting this.
     *
     * Never throws: this runs from context-menu handlers on a JxBrowser callback thread, where
     * an escaping exception has no owner. A refusal is logged rather than returned, because
     * `BrowserHandle` declares these as `Unit` and widening that would force a plugin-api
     * release for a signal only this log needs.
     */
    private fun editorCommand(
        command: EditorCommand,
        what: String,
    ): Boolean {
        if (!isValid) return false
        val accepted =
            runCatching {
                executeEditorCommand(
                    focusedFrame = browser.focusedFrame().orElse(null),
                    mainFrame = browser.mainFrame().orElse(null),
                    command = command,
                )
            }.onFailure { logger.warn(LogCategory.BROWSER, "$what failed", error = it) }
                .getOrDefault(false)
        if (!accepted) {
            // Chromium's own answer, not an exception: nothing selected, nothing editable at
            // the caret, or an empty clipboard. Worth a line — a silently refused clipboard
            // command is exactly the failure this method was rewritten to stop hiding.
            logger.debug(LogCategory.BROWSER, "$what refused by Chromium", mapOf("handleId" to id))
        }
        return accepted
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
        // Retaining is gated on the rendering mode, NOT applied unconditionally as Lite does.
        // HARDWARE is now the default on every platform, so in practice the gate is open
        // everywhere; it still earns its keep because OFF_SCREEN remains reachable per install
        // (BOSS_RENDERING_MODE, the Chromium-flags setting), and an install that picks it must
        // keep the exact close-on-hide lifecycle it had. Safe because the surface is still closed
        // for real in dispose(), which runs when the tab is actually closed rather than merely
        // hidden.
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
        // tabs is not being read.
        //
        // The tracker ref-counts these rather than taking them as a boolean, because a tab
        // moving between windows builds one composition and tears down the other in an order
        // this effect does not control. Keying on Unit does not help: it only stabilises
        // across recomposition *within* a composition, and a cross-window move is two.
        //
        // Window-level focus is deliberately not consulted here: a visible tab in a
        // background window still counts as active. WindowFocusEvent is reported separately,
        // so a consumer that cares can intersect the two.
        DisposableEffect(Unit) {
            visitTracker.setVisible(true)
            // Same signal, second consumer, and ref-counted for the same ordering reason: the
            // frame-stall probe must not judge a view that is not on screen, because Chromium
            // serves no frames to one. See composedSurfaces.
            composedSurfaces.incrementAndGet()
            onDispose {
                visitTracker.setVisible(false)
                composedSurfaces.decrementAndGet()
            }
        }

        // Give the web content keyboard focus when a tab is shown AGAIN, and never the first
        // time.
        //
        // In HARDWARE_ACCELERATED mode the Compose view is JxBrowser's SharedSurfaceWidget over
        // a native child view, and unlike the OFF_SCREEN widget — whose OffScreenWidgetState
        // wires onFocusChanged to BrowserWidget.focus()/unfocus() and answers TakeFocusCallback —
        // it has no Java-side focus wiring at all. It relies entirely on the native view being
        // first responder. Switching tabs hides that view and shows another, and nothing
        // promotes the one that reappears, so the returned-to tab can hold no keyboard focus:
        // Cmd+V goes nowhere until the page is clicked. Whether it happens depends on what the
        // window fell back to when the outgoing view was hidden, which is what made it read as
        // "sometimes".
        //
        // The first-show exception is the point of [shownBefore], not an optimisation: a tab
        // being created is supposed to leave the caret in BOSS's own URL bar, and focusing the
        // page here would take it away on every new tab.
        //
        // There is deliberately no unfocus() on the way out. A tab moving between windows builds
        // one composition and tears down the other in an order this effect does not control (see
        // the ref-counting note above), so an unfocus from the outgoing composition could land
        // after the incoming one has focused and undo it. Hiding is already communicated by the
        // widget detaching; the missing half was only ever the re-show.
        DisposableEffect(Unit) {
            if (needsExplicitFocusOnReshow(JxBrowserConfig.renderingMode) && shownBefore.getAndSet(true)) {
                // invokeLater, not a direct call: child effects run before parent ones, so the
                // native view has been shown by now, but the focus request still reads better one
                // turn of the event loop later than in the middle of applying this frame.
                SwingUtilities.invokeLater {
                    if (isValid) {
                        runCatching { browser.focus() }
                            .onFailure {
                                logger.debug(
                                    LogCategory.BROWSER,
                                    "Could not focus web content on tab re-show",
                                    mapOf("handleId" to id, "error" to it.toString()),
                                )
                            }
                    }
                }
            }
            onDispose {}
        }

        // Which window telemetry is attributed to, kept current across a tab move. Its own
        // effect because the visibility effect above must stay keyed on Unit - keying that one
        // on the window would fire a spurious TAB_ACTIVATED every time a tab moved. (It used to
        // say "the focus effect above"; there is now a second effect above that really is about
        // focus, and this is not the one it means.)
        DisposableEffect(hostWindowId) {
            hostWindowId?.let { currentWindowId = it }
            onDispose {}
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

            // Published for the frame-stall gate, which needs to know whether the window this view
            // lives in is actually showing - composition alone stays alive while it is minimized.
            frameStallHostWindow = awtWindow

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

        // Render the browser view if available with mouse button handling.
        //
        // Keyed on viewGeneration so the stall watchdog can force the view out of composition and
        // back, which is what re-attaches the native view when a committed page never drew. The
        // BrowserViewState is deliberately NOT rebuilt with it: the manual repair this imitates (a
        // tab switch) reuses the retained surface too, so re-attaching is what matters and
        // rebuilding the surface would cost far more than it fixes.
        viewState?.let { state ->
            key(viewGeneration) {
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
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        // Shut the interaction bridge FIRST. Its only gate is this authority, and the
        // collector flushes on `pagehide` — which is precisely when this runs. Closing the
        // tracker first left a window between the two statements in which a batch arriving on
        // the JS thread still read a non-null authority, so a tab close emitted PAGE_LEFT,
        // TAB_CLOSED, and then clicks on a tab that was already gone: the exact race this
        // pair exists to close. Nulling first cannot lose a visit, since closed() is guarded
        // by its own `finished` flag and does not consult this.
        currentPageAuthority = null
        // Then flush the visit in progress. This is the only place a page's dwell time can be
        // closed out when a tab is shut while still on a page — every other path ends a visit
        // by starting the next one.
        visitTracker.closed()
        FullscreenBrowserWindow.exitFullscreen(browser)

        // Stop co-browse capture so a disposed tab can never keep streaming.
        coBrowseCapturing = false
        coBrowseControlGranted = false
        coBrowseSink = null
        coBrowseBridge.onEvent = null
        coBrowseScope.cancel()
        // A pending frame-stall probe outlives the tab otherwise, and its next act is a blocking
        // executeJavaScript against a browser that is being torn down. shutdown() not
        // shutdownNow(), for the same reason as the context-menu executor: a round-trip already
        // inside executeJavaScript cannot be interrupted, and the thread is daemon.
        frameStallJob.getAndSet(null)?.cancel()
        frameStallScope.cancel()
        frameProbeExecutor.shutdown()
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
 * Runs [command] on the frame that should receive it, and reports whether Chromium took it.
 *
 * The whole content of this function is the frame choice, and it is the part that was wrong:
 * the clipboard operations used to reach `mainFrame()` unconditionally, so a caret inside an
 * iframe copied and pasted nothing. [focusedFrame] is where the caret is; [mainFrame] is the
 * fallback for the case Chromium reports no focused frame at all.
 *
 * Pure and separate from [BrowserHandleImpl] so that choice is pinned by a test instead of
 * needing a live engine to observe. Exception containment stays at the call site, which owns
 * the logger.
 */
internal fun executeEditorCommand(
    focusedFrame: Frame?,
    mainFrame: Frame?,
    command: EditorCommand,
): Boolean {
    val frame = focusedFrame ?: mainFrame ?: return false
    return frame.execute(command)
}

/**
 * Whether the host has to hand keyboard focus back to the web content when a tab is shown again.
 *
 * Only under HARDWARE_ACCELERATED, and for a reason unrelated to [shouldRetainSurface] even
 * though both currently name the same mode. This one is about JxBrowser's Compose widgets:
 * `OffScreenWidgetState` wires `onFocusChanged` to `BrowserWidget.focus()`/`unfocus()` and
 * answers `TakeFocusCallback`, so under OFF_SCREEN focus is already handled and a second,
 * host-side `focus()` would fight it. `SharedSurfaceWidget` — the HARDWARE_ACCELERATED path —
 * has none of that and depends on the native view being first responder, which nothing restores
 * after a tab switch.
 *
 * Kept as its own predicate rather than reusing [shouldRetainSurface] so the two reasons can
 * diverge: a future JxBrowser that wires focus into the shared-surface widget would flip this
 * one and leave surface retention exactly as it is.
 */
internal fun needsExplicitFocusOnReshow(mode: com.teamdev.jxbrowser.engine.RenderingMode): Boolean =
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
