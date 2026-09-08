package ai.rever.boss.plugin.browser

import ai.rever.boss.cache.FaviconCache
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.overlayCornerIsHeavyweight
import ai.rever.boss.components.window_panel.components.main_window_panels.LocalInMainWindowPanel
import ai.rever.boss.config.AutoPipSettingsManager
import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.config.SwipeNavSettingsManager
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.plugin.api.BrowserNavigationType
import ai.rever.boss.plugin.api.LocalIsPanelActive
import ai.rever.boss.plugin.window.LocalWindowId
import ai.rever.boss.tabfullscreen.FullscreenBrowserWindow
import ai.rever.boss.tabfullscreen.TabFullscreenStateManager
import ai.rever.boss.utils.MacOSGestureHandler
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.window.BossWindowIcon
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.teamdev.jxbrowser.ObjectClosedException
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.MediaStreamType
import com.teamdev.jxbrowser.browser.callback.CreatePopupCallback
import com.teamdev.jxbrowser.browser.callback.OpenPopupCallback
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.browser.event.BrowserClosed
import com.teamdev.jxbrowser.browser.event.FaviconChanged
import com.teamdev.jxbrowser.browser.event.MediaStreamCaptureStarted
import com.teamdev.jxbrowser.browser.event.MediaStreamCaptureStopped
import com.teamdev.jxbrowser.browser.event.RenderProcessTerminated
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
import com.teamdev.jxbrowser.net.ResourceType
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.GraphicsEnvironment
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
 * - Editable is reported for the main frame only. The reason used to be the credential fill,
 *   which ran against `browser.mainFrame()` and `document.activeElement`, so offering it for a
 *   field inside an iframe could write a password into whatever main-frame input happened to be
 *   focused. That method is gone and filling now belongs to the caller, which targets an element
 *   it already identified - so this gate is no longer holding a fill path back.
 *   `cut`/`copySelection`/`paste`/`selectAll` go through [BrowserHandleImpl.editorCommand], which
 *   targets `focusedFrame()` and would work inside an iframe already; this gate is what stops the
 *   menu offering them there, and widening it is now a self-contained change.
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

    /**
     * The last committed main-frame URL, for [PageEventBridge.urlProvider].
     *
     * A plain field read, on purpose. That provider runs from `emit`, on the JxBrowser thread and
     * inside the page's own event dispatch - the one thread the bridge's KDoc insists must never
     * block - and whether `Browser.url()` is served from cached Java state or is a synchronous IPC
     * round-trip is a JxBrowser implementation detail this code should not be betting on. Reading a
     * field settles it: no round-trip, per submit or ever.
     *
     * Fed from the same NavigationFinished handler that notifies the navigation listeners, so it is
     * the committed URL rather than anything pending. `browser.url()` remains the fallback for the
     * window before the first navigation commits.
     */
    @Volatile private var lastCommittedMainFrameUrl: String = ""

    /**
     * Whether this handle's page is [url], without asking Chromium.
     *
     * Built on [lastCommittedMainFrameUrl] rather than on a second field of its own: main added
     * that one for the page-event bridge while this was in review, for the same reason - reading
     * a field instead of betting on whether `Browser.url()` is a synchronous round trip - and two
     * volatiles tracking one value is how they drift.
     *
     * Exists so a handle can be matched against a tab's URL without an IPC round trip.
     * `DesktopBrowserAccessor.findBrowserForTab` resolves a dynamic plugin's browser tab by
     * scanning every active handle for the one whose URL equals the tab's, and it used to read
     * each candidate's `getCurrentUrl()` - a blocking call into Chromium per handle, on a path a
     * panel can poll. With a dozen tabs open that was a dozen round trips per lookup, and a
     * handle whose transport had gone made each one a failure that had to fail first.
     *
     * The comparison is sound because the field is fed from the same `NavigationFinished`
     * main-frame branch that notifies [navigationListeners], which is where the plugin's own tab
     * state gets its URL from: both sides now come from one event, rather than a tracked value on
     * one side and a live read on the other. Same-document navigations reach that branch too, so
     * an SPA route change is reflected.
     *
     * Falls back to the creation URL while the field is still blank, so a browser that has not
     * navigated yet is matchable. Deliberately not an exposed getter - a caller holding the
     * string would be tempted to read live when it looked stale, which is the round trip this
     * removes - and deliberately NOT the `browser.url()` fallback [lastCommittedMainFrameUrl]'s
     * other reader uses, since that is the round trip.
     */
    internal fun isAtUrl(url: String): Boolean = lastCommittedMainFrameUrl.ifBlank { config.url } == url

    /** Receives in-page interaction batches, attributed to the page that is actually loaded. */
    private val interactionBridge =
        BrowserInteractionBridge(
            authorityProvider = { currentPageAuthority },
            windowId = { currentWindowId },
        )

    // How close together two two-finger swipes may navigate. A handle-level object rather than the
    // `lastNavigationTime` the aux mouse buttons use, because that one is a `remember` slot inside
    // Content() and this arrives from a JxBrowser thread with no composition in sight. It survives
    // the navigation it just caused, which the page-side script cannot - see [SwipeNavGate].
    private val swipeNavGate = SwipeNavGate()

    /** Receives committed two-finger swipes from the page. See [BrowserSwipeNavScript]. */
    private val swipeNavBridge = BrowserSwipeNavBridge(onNavigate = ::onSwipeNavigate)

    private val disposed = AtomicBoolean(false)

    /**
     * Latched when a synchronous RPC through this browser fails because its transport is gone.
     *
     * [isValid]'s generation clause covers a browser outliving *its engine*. It cannot see the
     * other way a browser's IPC dies: this browser's own connection closing while the engine
     * generation still matches - a renderer going away, a page calling `window.close()`, or a
     * plugin disposing the browser behind its own handle. In that state `isClosed` still answers
     * `false` (nothing arrived to mark it) and the generation still matches, so [isValid]
     * answered `true` for a browser every call through which throws.
     *
     * That made the handle uncollectable: [BrowserServiceImpl.reconcileOrphanedBrowsers] prunes
     * on `!isValid`, so a handle that can never be valid again was also never pruned, and stayed
     * in `getActiveHandles()` for the rest of the session. Any scan over those handles then paid
     * a failing round trip per zombie and, because these accessors threw, aborted at the first
     * one - so the scan could never reach a live browser again either.
     *
     * Latched rather than probed: the condition is terminal (a closed connection is never
     * reopened - a new browser gets a new handle), and probing would mean spending the failing
     * round trip this exists to avoid.
     */
    private val connectionDead = AtomicBoolean(false)

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

    /**
     * The tab this browser is rendered in, learned when the plugin registers its fullscreen
     * handler. Null until then, which is why Back-to-tab still raises the window either way.
     */
    @Volatile private var ownerTabId: String? = null

    /** Fallback destinations for popups whose navigation never names one. */
    private val popupTargets = PopupTargetQueue()

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

    /**
     * Every blocking renderer round trip this handle makes, off the EDT and answered on a deadline.
     *
     * See [BoundedBrowserCall] for why both of those are necessary and why they need two different
     * threads. Browser-process calls are deliberately NOT routed through here: `loadUrl`,
     * `browser.url()` and `dispatch` are answered by a process page JS cannot block, so they are not
     * this failure and gain nothing from queueing behind a wedged renderer.
     *
     * **Why [frameProbeExecutor], [contextMenuExecutor] and [pageInjectExecutor] are still separate.**
     * This class is a strictly better version of all three - retiring thread, encapsulated deadline,
     * one place the two-thread rule lives - and folding them in would leave one pattern instead of
     * four. It is deliberately not done here, and the reason is not inertia: merging the queues merges
     * the blast radii. Today a wedged page-helper injection still leaves the context menu answering
     * and the stall probe reporting, because each waits on its own thread; behind one queue they
     * would all time out together, and the frame-stall probe in particular exists to interrogate a
     * page already suspected of misbehaving. Folding them in is a real option, but it is a decision
     * about how much independence to trade for one pattern, and it belongs in its own change.
     */
    private val handleCall = BoundedBrowserCall("boss-browser-call-$id")

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
    private val coBrowseInjectRegistered = AtomicBoolean(false)

    // Page→host bridge injected onto window.__bossCoBrowse; its onEvent is repointed per capture.
    private val coBrowseBridge = CoBrowseBridge()

    // Scope for injection/teardown. Its launches make blocking renderer round trips, so it runs on
    // [handleCall]'s thread rather than Main: on Main, a viewer sharing a tab whose page stops
    // answering froze the whole app. Single-threaded, so those stay ordered against each other.
    //
    // dispatchCoBrowseInput is the one member that overrides this back to Main - see its comment.
    private val coBrowseScope = CoroutineScope(SupervisorJob() + handleCall.dispatcher)

    // --- Page event channel (setPageEventScript) ---
    // The plugin-supplied document-start script, or null when uninstalled. Read by the injector
    // on the JxBrowser thread, so @Volatile rather than a plain field.
    @Volatile private var pageEventScript: String? = null

    // Page→plugin bridge. Handed to the plugin's script as a parameter (PageEventScripts.injection),
    // never left on window; its onEvent is repointed per setPageEventScript call.
    private val pageEventBridge = PageEventBridge()

    // True once the document-start injector is registered. Left registered after an uninstall for
    // the same reason co-browse does: re-registering is a race, and the injector is inert while
    // pageEventScript is null.
    //
    // AtomicBoolean, not @Volatile: @Volatile makes each read fresh but not the read-then-write
    // pair, so two concurrent setPageEventScript calls could both register and every document would
    // then be injected twice.
    private val pageEventInjectRegistered = AtomicBoolean(false)

    // Scope for the one immediate injection into the already-loaded document. On [handleCall]'s
    // thread and not Main for the reason [coBrowseScope] gives: that injection hands the bridge over
    // with `putProperty`, a blocking renderer round trip, and it is the call that was caught holding
    // the EDT with the macOS menu bar parked behind it.
    private val pageEventScope = CoroutineScope(SupervisorJob() + handleCall.dispatcher)

    /*
     * Why there is NO "inject once per document" counter here, though there was one for a while.
     *
     * It was a documentGeneration bumped in NavigationFinished and claimed at injection. It did not
     * work, and the trace is worth keeping because the idea is an obvious one to have again:
     * NavigationFinished is where _isLoading = false is set, so it fires AFTER the new document's
     * script context exists and after the document-start injection into it. The counter therefore
     * advanced BETWEEN the two injections that can reach one document - the document-start one, and
     * the immediate one when a caller (re)installs a script - so the second was always allowed. It
     * dressed up "no guarantee" as a guarantee, and three repos documented it.
     *
     * What is true instead: a script may be evaluated MORE than once in a document, so it has to
     * tolerate that. Tolerating is cheap for a submit-driven consumer (two identical events, into a
     * conflated channel); enforcing was not, because the only guard shared across evaluations is a
     * window property, which is the detectability the parameter shape exists to remove.
     */

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
     * What this page is capturing, and therefore whether it is in a call.
     *
     * Declared here, above [init], on purpose: `setupEventListeners()` subscribes to the capture
     * events from the constructor. The subscription lambdas read this field when an event
     * arrives rather than when they are created, so a later declaration happens to work - but
     * only by accident, and a refactor that touched it inside the lambda would NPE.
     */
    private val captureTracker = CaptureTracker()

    /**
     * Set while a pop-out this class opened is on screen. A pop-out the user opened by hand is
     * not ours to close, so only the ones we entered are restored on the way back - the same rule
     * Chrome applies through its 5-second activation window.
     */
    private val autoPoppedOut = AtomicBoolean(false)

    /** The floating window holding this browser's real surface while its tab is backgrounded. */
    @Volatile private var popOutFrame: JFrame? = null

    /**
     * Answers the plugin's hibernation guard, which cannot see a host window.
     *
     * Read off the frame rather than [autoPoppedOut]: that flag is raised before the window
     * exists and cleared in paths that do not own the frame, while this is true exactly when a
     * window is on screen holding the surface - which is the fact hibernation needs.
     */
    override val isPoppedOut: Boolean
        get() = popOutFrame != null

    /** The Swing view inside [popOutFrame]; detached before the frame is disposed. */
    @Volatile private var popOutView: com.teamdev.jxbrowser.view.swing.BrowserView? = null

    /**
     * The AWT window this handle's view is bound to, for the on-screen half of [viewComposed].
     * Null until [Content] resolves one, and treated as "assume showing" while it is.
     */
    @Volatile private var frameStallHostWindow: Window? = null

    /**
     * OS pid of the Chromium renderer serving the last committed main-frame document, or null.
     *
     * Pushed at the events where it can change rather than pulled when someone asks, because the
     * pull costs a blocking round trip and the push costs nothing. Of the chain
     * `mainFrame -> renderProcess -> pid`, only `mainFrame()` is IPC; the other two are field
     * reads. Capturing during [injectPageHelpers], which already holds the frame, therefore adds
     * no round trip at all, and the reader gets a plain volatile load it can make from anywhere
     * including a Compose layout pass.
     *
     * **Only the Int is kept, never the `Frame` or the `RenderProcess`.** Holding either across a
     * navigation is a trap: `FrameImpl.renderProcess()` and `RenderProcess.pid()` carry no
     * `checkNotClosed`, so a stale frame does not throw - it silently answers with the *previous*
     * renderer's pid, which is a wrong number that looks entirely right.
     *
     * Cleared on renderer death, browser close and dispose. That is what closes the pid-reuse
     * hole: a dead renderer's pid can be recycled by another Chromium helper of ours, and a stale
     * entry would then charge that process's memory to this tab, again plausibly.
     */
    private val rendererPid = RendererPid()

    /**
     * The renderer pid as of the last main-frame commit, or null when unknown.
     *
     * Never blocks and never touches IPC - see [rendererPid]. Unknown is the honest answer before
     * the first commit and after the renderer goes away, and callers must render it as absent
     * rather than as a zero.
     */
    internal fun lastKnownRendererPid(): Int? = rendererPid.value

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

    // Off-thread executor for the per-commit renderer-pid capture and page-helper injection.
    //
    // Same hazard the two above are built around, and the one place that was still running it
    // unguarded. Both of those round trips were made straight from the NavigationFinished
    // callback, which JxBrowser delivers on its RPC thread — the same thread that has to pump
    // the reply. `executeJavaScript` there re-enters RpcThreadCallExecutor and parks on a queue
    // only the thread it is blocking could drain, so the call can never be answered. Every later
    // blocking call on that browser then waits behind it: the EDT freezes inside its own
    // executeJavaScript, and the AppKit main thread freezes behind the EDT, taking the menu bar
    // with it. A single unanswerable round trip is enough to hang the whole app.
    //
    // One thread, and daemon, for the reason contextMenuExecutor spells out: nothing can
    // interrupt a call already inside executeJavaScript, so a wedged renderer costs one parked
    // thread and later injections queue behind it.
    private val pageInjectExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "boss-page-inject-$id").apply { isDaemon = true }
        }
    private val pageInjectDispatcher = pageInjectExecutor.asCoroutineDispatcher()

    private val pageInjectScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    logger.warn(LogCategory.BROWSER, "Page-helper injection failed", error = error)
                },
        )

    /**
     * The in-flight commit follow-up, swapped atomically.
     *
     * A redirect chain fires NavigationFinished repeatedly and only the document that finally
     * sticks is worth injecting into, exactly as for [frameStallJob].
     */
    private val pageInjectJob = AtomicReference<Job?>(null)

    // Lock for thread-safe browser operations
    private val browserLock = ReentrantReadWriteLock()

    // Helper to create LockedBrowser for FormFieldDetector
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
            watchSwipeSetting()
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

                // A CROSS-DOCUMENT main-frame navigation ends whatever call the old document
                // was in, so the capture counts start again. Without this the tab stays "in a
                // call" for as long as the engine's stop events say so, and ONE missed
                // MediaStreamCaptureStopped - a renderer crash, a page swapped out mid-call -
                // leaves it permanently eligible, so every later tab switch pops the tab out
                // over a call that ended long ago.
                //
                // Same-document navigations are excluded, and that exclusion is load-bearing:
                // NavigationFinished fires for pushState/replaceState too, and Google Meet
                // rewrites its URL DURING a call - so clearing on those zeroed the counts while
                // the camera was live, no new capture event ever came, and auto-PiP silently
                // never fired again for the rest of the call.
                if (event.isInMainFrame && !event.isSameDocument) {
                    captureTracker.clear()
                }

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
                    // See lastCommittedMainFrameUrl: this is what the page-event bridge reports as
                    // the posting document, so it must be set before any script in the new document
                    // can post - which this handler is, since it fires on navigation completion.
                    lastCommittedMainFrameUrl = url
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

                    // One `mainFrame()` serving both the pid capture and the injection, so the
                    // capture costs no round trip of its own.
                    //
                    // The capture sits OUTSIDE the URL gate below, and before the injection
                    // rather than after it, because both of those are ways to keep a stale pid.
                    // Injection is skipped for about:blank, so a tab navigating from a heavy
                    // site to the dashboard would otherwise keep pointing at the old document's
                    // renderer; and injection can throw partway, which would leave the previous
                    // value in place. Either produces the "wrong number that looks right" the
                    // whole design is built to avoid. Refreshing on every commit makes the only
                    // failure mode "unknown".
                    //
                    // `mainFrame()` is the one call still made here: it is what names the
                    // document this commit is about, so reading it later would race the next
                    // navigation. Everything after it is a blocking round trip and moves to
                    // [pageInjectDispatcher] — see the note there for what running them on this
                    // thread does.
                    val frame = browser.mainFrame().orElse(null)

                    // Cleared synchronously, so the previous document's renderer is never the
                    // answer for this one even while the real capture is still in flight. That
                    // is the refresh-on-every-commit rule above, and "unknown" is the failure
                    // mode RendererPid is built to prefer.
                    rendererPid.onCommit(null)

                    // Skip injection for about:blank pages (used for dashboard display)
                    // Only inject into actual web pages
                    val injectTarget = frame?.takeIf { url.isNotEmpty() && url != "about:blank" }
                    val followUp =
                        pageInjectScope.launch(pageInjectDispatcher) {
                            val pid = frame?.let { runCatching { it.renderProcess().pid() }.getOrNull() }
                            // A superseded commit must not write: by now the pid names a
                            // document that is no longer current, which is precisely the
                            // plausible-looking wrong number RendererPid exists to refuse. The
                            // supersede below cannot interrupt a call already inside JxBrowser,
                            // so the check has to happen here, after it returns.
                            ensureActive()
                            rendererPid.onCommit(pid)
                            if (injectTarget != null) injectPageHelpers(injectTarget)
                        }
                    pageInjectJob.getAndSet(followUp)?.cancel()
                }
            }

        // Device capture, which is how this tab says "I am in a call". Chrome gates its auto
        // Picture-in-Picture on exactly this (MediaStreamCaptureIndicator::IsCapturingUserMedia),
        // and it is device capture only - a screen share goes through captureSessions() instead,
        // which is why Chrome's rule does not fire for getDisplayMedia either.
        subscriptions +=
            browser.on(MediaStreamCaptureStarted::class.java) { event ->
                val media = capturedMediaOf(event.mediaStreamType())
                media?.let(captureTracker::started)
                logger.debug(
                    LogCategory.BROWSER,
                    "Capture started",
                    mapOf(
                        "handleId" to id,
                        "media" to media.toString(),
                        "capturing" to captureTracker.isCapturing().toString(),
                    ),
                )
            }
        subscriptions +=
            browser.on(MediaStreamCaptureStopped::class.java) { event ->
                val media = capturedMediaOf(event.mediaStreamType())
                media?.let(captureTracker::stopped)
                logger.debug(
                    LogCategory.BROWSER,
                    "Capture stopped",
                    mapOf(
                        "handleId" to id,
                        "media" to media.toString(),
                        "capturing" to captureTracker.isCapturing().toString(),
                    ),
                )
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

        // Renderer gone. Forgetting the pid here keeps the strip honest: Chromium recycles pids,
        // so a retained one can later belong to a different helper of ours and that process's
        // memory would be reported as this tab's.
        //
        // This covers *unexpected* death - a crash or a kill - not the ordinary process swap of
        // a cross-site navigation, which fires no such event. The swap is covered by the commit
        // that follows it, which is why the capture had to be moved out of the URL-gated
        // injection path: between the two, every way the renderer can change is accounted for.
        subscriptions +=
            browser.on(RenderProcessTerminated::class.java) { event ->
                logger.debug(
                    LogCategory.BROWSER,
                    "Renderer terminated",
                    mapOf("handleId" to id, "exitCode" to event.exitCode(), "status" to event.status().name),
                )
                rendererPid.onGone()
            }

        // Browser closed
        subscriptions +=
            browser.on(BrowserClosed::class.java) {
                logger.debug(LogCategory.BROWSER, "Browser closed", mapOf("handleId" to id))
                disposed.set(true)
                rendererPid.onGone()
                // Stop streaming: the underlying page is gone.
                coBrowseCapturing = false
                coBrowseSink = null
                coBrowseBridge.onEvent = null
                // Same for the page event channel. dispose() clears this too, but a browser can
                // close without one (a crashed renderer, an engine recycle), and a sink still
                // pointing at a plugin is the half that matters.
                pageEventScript = null
                pageEventBridge.onEvent = null
                pageEventBridge.urlProvider = { "" }
                // And drop the injectors HERE, not only in dispose(). This handler sets
                // disposed = true, and dispose() returns on its first line when that is already
                // set - so for a browser that closed on its own (crashed renderer, engine recycle)
                // dispose() never reaches its unregister call, and the entry pins this handle for
                // the rest of the session. That is the leak the unregister was added to fix,
                // arriving through the one path that skips it.
                BrowserInjectDispatcher.unregister(browser)
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

        // Find-in-page, with THIS handle's lock so a search takes the same read lock as every
        // other access to the browser. Registered before the interceptor, which is what serves the
        // find chord.
        BrowserFindController.register(browser, browserLock)

        // Setup keyboard interceptor for menu shortcuts. Passing `this` mid-construction is safe:
        // the interceptor only captures it in a callback lambda, which JxBrowser cannot invoke
        // until a key reaches a browser that is by then fully built.
        FluckEngine.setupKeyboardInterceptor(browser, ownerWindowId, zoomTarget = this)

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
     * describes the clicked one.
     *
     * A caller that fills from `activeElement` inherits the same assumption, and the two at least
     * agree with each other. One that resolves the element itself - from `params.location()` via
     * `elementFromPoint`, or by deferring resolution until a fill is actually chosen - does not
     * need this to be right, and is the direction to go if it ever matters.
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
    private fun injectPageHelpers(frame: Frame) {
        // Outside the shared try below, and first, because everything in there is one failure
        // domain: a throw from the Cmd+Click injection would silently cost this page its back
        // gesture. It brings its own catch, so the reverse cannot happen either.
        injectSwipeNav(frame)
        run {
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

    /**
     * Publish the swipe bridge on this page, tell it which directions exist, and start the
     * detector.
     *
     * Re-run per navigation for two reasons, not one: each document gets a fresh JS context and
     * needs the bridge republished, and a same-document navigation keeps the context but changes
     * what `canGoBack`/`canGoForward` answer. The script no-ops its own second start; the state
     * assignment is what actually has to run every time.
     *
     * Deliberately NOT gated on [BrowserAnalytics.telemetryEnabled], unlike the interaction
     * collector next door. This is an input feature, and a deployment that turns telemetry off has
     * not asked to lose the back gesture.
     */
    private fun injectSwipeNav(frame: Frame) {
        if (!BrowserSwipeNavScript.isEnabled()) return
        try {
            val window = frame.executeJavaScript<JsObject>("window")
            window?.putProperty(BrowserSwipeNavScript.BRIDGE_PROPERTY, swipeNavBridge)
            // Before the script, so the first wheel event of a swipe that starts the instant the
            // page paints already has an answer to gate on.
            frame.executeJavaScript<Any?>(
                BrowserSwipeNavScript.stateUpdate(
                    enabled = true,
                    canGoBack = browser.navigation().canGoBack(),
                    canGoForward = browser.navigation().canGoForward(),
                ),
            )
            frame.executeJavaScript<Any?>(BrowserSwipeNavScript.source)
        } catch (e: Exception) {
            // The exception CLASS, not its message, for the reason the collector above gives.
            logger.debug(
                LogCategory.BROWSER,
                "Swipe navigation injection failed",
                mapOf("handleId" to id, "error" to (e::class.simpleName ?: "Exception")),
            )
        }
    }

    /**
     * Keep an already-open page's copy of the setting current.
     *
     * Without this the page keeps whatever was pushed at its last navigation, so switching the
     * gesture off leaves the detector running on every tab until each one navigates - including the
     * tab the user just changed the setting to test. That would make this setting half
     * restart-scoped, which is exactly what its own KDoc says it is not.
     *
     * Only the flag is pushed, not navigability: those two change on different clocks, and pushing
     * a stale back/forward pair here would be worse than pushing nothing.
     */
    private fun watchSwipeSetting() {
        pageEventScope.launch {
            SwipeNavSettingsManager.settings.collect {
                if (!isValid) return@collect
                val enabled = BrowserSwipeNavScript.isEnabled()
                val statement =
                    "window.${BrowserSwipeNavScript.STATE_PROPERTY} = " +
                        "Object.assign(window.${BrowserSwipeNavScript.STATE_PROPERTY} || {}, { enabled: $enabled });"
                pageInjectScope.launch(pageInjectDispatcher) {
                    runCatching { browser.mainFrame().ifPresent { it.executeJavaScript<Any?>(statement) } }
                }
            }
        }
    }

    /**
     * A committed two-finger swipe, arriving from the page's JS thread.
     *
     * Posted onto [pageInjectScope] rather than acted on here: [BrowserSwipeNavBridge] promises the
     * renderer it will not block, and `goBack()` is a round trip into the browser.
     */
    private fun onSwipeNavigate(direction: SwipeNavDirection) {
        if (!isValid || !swipeNavGate.accept(direction)) return
        pageInjectScope.launch(pageInjectDispatcher) {
            when (direction) {
                SwipeNavDirection.BACK -> goBack()
                SwipeNavDirection.FORWARD -> goForward()
            }
        }
    }

    // ============================================================
    // PAGE EVENT CHANNEL
    // ============================================================

    /**
     * Install a plugin-supplied document-start script and forward what it emits.
     *
     * The host's whole job here is transport. It hands [pageEventBridge] to the caller's script as
     * a parameter named [PAGE_EVENT_BRIDGE] - see [PageEventScripts.injection], which passes it in
     * and takes the hand-over slot back off `window` in the same evaluation - and gives each
     * emitted string to the caller's sink. It does not parse the payload or know what an event
     * means. The caller wrote the script,
     * so the caller owns every rule about what is worth reporting - the same division the
     * `fillCredentials` removal settled for writing.
     *
     * Two things make this more than `executeJavaScript` in a loop: the script runs before the
     * page's own scripts, and an event is delivered while the document that produced it still
     * exists. A submit is followed by a navigation that destroys the JS context, so a value latched
     * in the page for a later poll to collect is racing its own teardown.
     */
    override val supportsPageEventScript: Boolean get() = true

    override fun clearPageEventScript() {
        // In this order deliberately: clearing the SCRIPT first makes the path monotonic. Clearing
        // the sink first left a window in which pageEventScript was still non-null, so a
        // document-start injection landing there would evaluate the plugin's script into a fresh
        // page against an inert bridge - a listener that can never report.
        //
        // Nothing is retracted from a document that is already live: whatever was evaluated there
        // goes away with the document. The dispatcher registration is deliberately left in place -
        // the injector reads pageEventScript and is inert while it is null.
        pageEventScript = null
        pageEventBridge.onEvent = null
    }

    override fun setPageEventScript(
        script: String,
        onEvent: (url: String, payload: String) -> Unit,
    ) {
        // Guard BEFORE the assignments: a dead handle must not end up holding a plugin's closure.
        if (!isValid) return
        // The URL the plugin is handed comes from here, never from the page's payload. Read at emit
        // time, inside the page's own event dispatch, so it names the document that actually posted
        // rather than whatever the tab navigated to next.
        pageEventBridge.urlProvider = {
            lastCommittedMainFrameUrl.ifBlank { runCatching { browser.url() }.getOrDefault("") }
        }
        // Sink before script: the reverse order would let a document-start injection evaluate the
        // new script and post into the previous callback.
        pageEventBridge.onEvent = onEvent
        pageEventScript = script
        ensurePageEventInjector()
        // The injector only fires for FUTURE contexts, so the page already loaded would be skipped
        // until its next navigation. Same reason startCoBrowseCapture injects immediately.
        pageEventScope.launch {
            try {
                browser.mainFrame().ifPresent { frame -> injectPageEventScript(frame) }
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Page event immediate injection failed",
                    mapOf("handleId" to id),
                    error = e,
                )
            }
        }
    }

    // Two callers, two threads, and never Main on either: [handleCall]'s thread when
    // setPageEventScript injects into the already-loaded document, and JxBrowser's own inject-callback
    // thread from the document-start injector, which has to block there before calling proceed().
    // Both are correct; what matters is that neither is the EDT, because this blocks on the renderer
    // twice over (an `executeJavaScript` and a `putProperty`).
    //
    // Reads pageEventScript once into a local: it is @Volatile, and an uninstall racing this would
    // otherwise hand over the bridge and evaluate null.
    //
    // Two guards, each a distinct "nothing to do here": no script installed, and no window to hand
    // the bridge through. They log differently, so collapsing them would hide which one fired.
    @Suppress("ReturnCount")
    private fun injectPageEventScript(frame: Frame) {
        val script = pageEventScript ?: return
        // The bridge has to cross into JS through a window property - executeJavaScript takes source
        // and no arguments - but it does not have to STAY there, and a documented global is the
        // wrong shape for a channel whose payload is a password. PageEventScripts.injection hands it
        // to the script as a parameter and takes the slot back off window; see its KDoc for what the
        // random name does and does not buy.
        val slot = PageEventScripts.newSlot { UUID.randomUUID().toString().replace("-", "") }
        val window = frame.executeJavaScript<JsObject>("window")
        if (window == null) {
            logger.debug(LogCategory.BROWSER, "Page event injection skipped - no window", mapOf("handleId" to id))
            return
        }
        try {
            window.putProperty(slot, pageEventBridge)
            frame.executeJavaScript<Any?>(PageEventScripts.injection(slot, script))
        } catch (e: Exception) {
            // Never logged with the script body: a page-event script is written by a plugin and may
            // legitimately name fields the user typed into.
            logger.warn(LogCategory.BROWSER, "Page event script injection failed", mapOf("handleId" to id), error = e)
            // The put succeeded and the evaluation did not, which leaves the bridge reachable on
            // window for the rest of this document - exactly the state the parameter shape exists to
            // prevent. A frame refusing an evaluation mid-navigation is normal (installFindKeyProbe
            // says so), so this is a reachable path rather than a defensive flourish.
            runCatching { frame.executeJavaScript<Any?>("try { delete window.$slot; } catch (e) { }") }
        }
    }

    /**
     * Claim a document-start slot once, through [BrowserInjectDispatcher] rather than
     * `browser.set(InjectJsCallback…)`. JxBrowser has exactly one such slot per browser and a
     * second `set` silently replaces the first, so going direct here would switch off the
     * co-browse recorder (or be switched off by it, depending on which registered last).
     */
    private fun ensurePageEventInjector() {
        if (!pageEventInjectRegistered.compareAndSet(false, true)) return
        val claimed =
            BrowserInjectDispatcher.register(browser) { frame ->
                if (pageEventScript != null && frame.isMain) {
                    injectPageEventScript(frame)
                }
            }
        // Registration can fail, and the dispatcher dropping its own entry is only half the
        // recovery: with the flag latched, this would never ask again, so the script would be inert
        // for this tab's whole life with no signal - and worse, another feature registering
        // successfully afterwards would claim the slot with only ITS injector in it.
        if (!claimed) pageEventInjectRegistered.set(false)
    }

    // ============================================================
    // CO-BROWSE / TAB SHARING (DOM state-sync)
    // ============================================================

    /**
     * Inject the rrweb recorder + page→host bridge into [frame] (main frame only).
     * rrweb captures same-origin iframes natively, so we never start a second
     * recorder in subframes.
     *
     * Two callers, two threads, and never Main on either - the same pair
     * [injectPageEventScript] has: [handleCall]'s thread when [startCoBrowseCapture]
     * injects into the already-loaded document, and JxBrowser's own inject-callback
     * thread from [ensureCoBrowseInjectCallback]'s document-start injector, which has
     * to block there before calling proceed(). What matters is that neither is the
     * EDT, because this blocks on the renderer four times over, and on Main a page
     * that stopped answering froze the app.
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
     * [stopCoBrowseCapture] to avoid re-register races.
     *
     * Goes through [BrowserInjectDispatcher] rather than `browser.set(InjectJsCallback…)`.
     * JxBrowser allows exactly ONE such callback per browser and a second `set` silently
     * replaces the first, so with two features wanting document-start injection - this
     * recorder and [setPageEventScript] - whichever registered second used to switch the
     * other off. Sharing a tab would have silently stopped credential capture in it, or
     * the reverse depending on order, with no error anywhere.
     */
    private fun ensureCoBrowseInjectCallback() {
        // AtomicBoolean for the same reason the page-event flag is: a @Volatile read-then-write pair
        // lets two concurrent startCoBrowseCapture calls both register, which double-injects the
        // recorder into every document.
        if (!coBrowseInjectRegistered.compareAndSet(false, true)) return
        val claimed =
            BrowserInjectDispatcher.register(browser) { frame ->
                if (coBrowseCapturing && frame.isMain) {
                    injectCoBrowseRecorder(frame)
                }
            }
        if (!claimed) coBrowseInjectRegistered.set(false)
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
        // Main, overriding [coBrowseScope]'s dispatcher, which is the one member here that keeps its
        // pre-existing thread. `browser.dispatch` is answered by the browser process, not the
        // renderer, so page JS cannot stall it and it was never part of this freeze. Two reasons not
        // to move it anyway: it would be an unannounced behaviour change to remote input under
        // HARDWARE_ACCELERATED, and while the shared thread is wedged a viewer's pointer keeps
        // enqueueing one task per event at frame rate onto an unbounded queue.
        coBrowseScope.launch(Dispatchers.Main) {
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
        // Bounded on [handleCall] for the reason [executeJavaScript] gives: a viewer actuating a tab
        // whose page has stopped answering must not freeze the host.
        //
        // No backlog guard here, unlike CoBrowseRtcPeerImpl.sendDom, and the difference is that this
        // one is AWAITED. The viewer gets one status per event and cannot outrun its own round trips,
        // so the queue depth is bounded by the number of viewers rather than by a frame rate - and
        // `call`'s finally cancels whatever it gave up on, so a stale event resumes with cancellation
        // instead of being actuated late into a page that recovered. What is left is retention: the
        // task and its eventJson sit on the queue while the renderer is wedged. sendDom has neither
        // property, which is why it drops instead.
        //
        // The catch stays INSIDE the block rather than wrapping the call. Kotlin's
        // CancellationException is a java.util.concurrent one, which extends IllegalStateException,
        // so a `catch (e: Exception)` around the await would swallow a caller's cancellation and
        // answer "err" to it - reporting a co-browse failure for what was an orderly teardown.
        return handleCall.call {
            try {
                val status =
                    browser
                        .mainFrame()
                        .map { frame ->
                            frame.executeJavaScript<String?>(CoBrowseScripts.applyControl(eventJson))
                        }.orElse(null)
                if (status != "ok") {
                    // Non-ok statuses ("stale"/"denied"/"nomirror"/"err:…") are ordinary outcomes and
                    // stay visible for live debugging - but the payload does NOT go with them.
                    // CoBrowseScripts.applyControl assigns `p.value` for kind 'input', so eventJson
                    // carries the literal text the viewer typed into a field, which can be a
                    // password. The kind is what makes the line useful; the value never was.
                    logger.warn(
                        LogCategory.BROWSER,
                        "Co-browse control not applied",
                        mapOf(
                            "handleId" to id,
                            // Truncated because an "err:…" status is built from a page-side exception
                            // message, so its length and content are the page's to choose.
                            "status" to (status?.take(STATUS_LOG_LIMIT) ?: "null"),
                            "kind" to coBrowseEventKind(eventJson),
                        ),
                    )
                }
                status
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Co-browse control apply failed",
                    mapOf("handleId" to id),
                    error = e,
                )
                "err"
            }
        }
    }

    /**
     * The event's `kind` alone, for logging.
     *
     * A co-browse event's payload can hold whatever a viewer typed, so it is never logged; AGENTS.md
     * requires [ai.rever.boss.utils.logging.LogSanitizer] for anything that might carry a secret,
     * and the cheapest way to satisfy that here is to not carry one.
     */
    private fun coBrowseEventKind(eventJson: String): String =
        runCatching {
            kotlinx.serialization.json.Json
                .parseToJsonElement(eventJson)
                .jsonObject["kind"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull() ?: "unknown"

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
                !connectionDead.get() &&
                FluckEngine.currentEngineGeneration == engineGeneration &&
                runCatching { !browser.isClosed }.getOrDefault(false)

    /**
     * Runs a synchronous JxBrowser accessor, returning [fallback] instead of throwing.
     *
     * Every sync accessor here is an IPC round trip that can fail at any moment, and they are
     * called from plugin code and from scans over [BrowserServiceImpl.getActiveHandles] - an
     * `if (isValid)` guard cannot make them safe, because the connection can die between the
     * check and the call. A throw out of one of these is what turned a single dead browser into
     * a permanently broken lookup, so they report failure by value like the rest of this class.
     *
     * A transport failure also latches [connectionDead], which is what lets the handle be pruned.
     * The log stays at debug: the whole point is that this path can repeat on a hot caller, and
     * it was a WARN-with-stack-trace per failure that flooded the console buffer.
     */
    private fun <T> syncCall(
        op: String,
        fallback: T,
        block: () -> T,
    ): T {
        if (!isValid) return fallback
        return try {
            block()
        } catch (e: Exception) {
            if (isTransportFailure(e)) {
                connectionDead.set(true)
                // isValid has just flipped without a disposal, and nothing unregisters here -
                // the registration is only dropped later by reconcileOrphanedBrowsers or at
                // window teardown. ActiveBrowserRegistry recomputes only on register/unregister,
                // so without this its window set stays stale in the dangerous direction: the
                // browser menu items stay ENABLED, keep swallowing Cmd+[ window-wide, and
                // activeIn then answers null. Republishing re-reads isValid and drops the window.
                ActiveBrowserRegistry.republish()
            }
            logger.debug(
                LogCategory.BROWSER,
                "Browser sync call failed",
                mapOf("handleId" to id, "op" to op, "error" to (e.message ?: e.javaClass.simpleName)),
            )
            fallback
        }
    }

    /**
     * Whether [e] means "this browser's IPC is gone" rather than "this one call failed".
     *
     * Only two things are treated as terminal: `ObjectClosedException`, and an
     * [IllegalStateException] carrying "The connection has been closed." - both of which say
     * the transport itself is gone, and a closed connection is never reopened.
     *
     * Deliberately NOT "Failed to receive the response.", even though that is the message on
     * the exception this was written for. It describes a round trip that did not come back,
     * which is also what a live-but-wedged renderer produces - see the frame-stall probe above,
     * which exists because `executeJavaScript` can block indefinitely against a page that is
     * still alive. Latching on it would let one slow round trip permanently invalidate a
     * healthy browser: every navigation and zoom call silently refusing on a tab that renders
     * fine, which is a worse bug than the one this fixes.
     *
     * Nothing is lost by excluding it, because the chain is what is matched, not the top
     * message: the observed failures arrived as "Failed to receive the response." with
     * "The connection has been closed." as their `cause`, so the terminal reason is still
     * found. Anything unrecognised counts as a transient call failure - a new message shape
     * costs a retry rather than a wrongly discarded live browser.
     */
    private fun isTransportFailure(e: Throwable): Boolean =
        generateSequence(e) { prev -> prev.cause?.takeIf { it !== prev } }
            // Bounded: a cause cycle longer than self-reference would otherwise not terminate.
            .take(MAX_CAUSE_DEPTH)
            .any { cause ->
                cause is ObjectClosedException ||
                    (cause.message ?: "").contains("connection has been closed", ignoreCase = true)
            }

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

    /**
     * Evaluate a plugin's script in the main frame, or null if the renderer did not answer in time.
     *
     * Every part of that bound lives in [BoundedBrowserCall], including which thread the wait runs
     * on - see its KDoc for why leaving that to the caller silently lost the deadline.
     *
     * **The behaviour change a plugin can see.** This used to wait forever (and take the app with
     * it); it now answers null after [BoundedBrowserCall.DEFAULT_TIMEOUT_MS]. That null is
     * indistinguishable from a script that legitimately evaluated to null, so a plugin reading it as
     * "no such element" will occasionally see that on a page slow enough to miss the deadline. The
     * timeout is always logged with the tab's thread name, which is the only way to tell the two
     * apart from outside.
     */
    override suspend fun executeJavaScript(script: String): Any? {
        if (!isValid) return null
        return handleCall.call(
            onError = { e ->
                logger.warn(
                    LogCategory.BROWSER,
                    "JS execution error",
                    mapOf("handleId" to id, "error" to (e.message ?: "unknown")),
                )
            },
        ) {
            browser.mainFrame().map { it.executeJavaScript<Any?>(script) }.orElse(null)
        }
    }

    override fun getCurrentUrl(): String = syncCall("url", "") { browser.url() }

    override fun getTitle(): String = syncCall("title", "") { browser.title() }

    override fun addNavigationListener(listener: (String) -> Unit) {
        navigationListeners.add(listener)
    }

    override fun removeNavigationListener(listener: (String) -> Unit) {
        navigationListeners.remove(listener)
    }

    override fun addTitleListener(listener: (String) -> Unit) {
        titleListeners.add(listener)
        // Replay the title Chromium has already reported.
        //
        // TitleChanged is an EVENT, so a listener that arrives after it fired hears nothing, and
        // a tab whose page loaded before its listener was attached keeps whatever placeholder it
        // was created with - "Loading..." - for the rest of its life. That is not a rare window:
        // a cached page fires TitleChanged almost immediately, and session restore attaches
        // listeners for many tabs while those tabs are already loading.
        //
        // Blank is skipped for the same reason updateTitle skips it: about:blank reports an empty
        // title, and replaying that would blank a tab rather than leave it alone.
        val title = lastKnownTitle
        if (title.isNotBlank()) {
            runCatching { listener(title) }
                .onFailure { logger.warn(LogCategory.BROWSER, "Title listener threw on replay", error = it) }
        }
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
        if (!canGoBack()) return
        syncCall("goBack", Unit) {
            visitTracker.expect(BrowserNavigationType.BACK_FORWARD)
            browser.navigation().goBack()
        }
    }

    override fun goForward() {
        if (!canGoForward()) return
        syncCall("goForward", Unit) {
            visitTracker.expect(BrowserNavigationType.BACK_FORWARD)
            browser.navigation().goForward()
        }
    }

    override fun reload() {
        syncCall("reload", Unit) {
            visitTracker.expect(BrowserNavigationType.RELOAD)
            browser.navigation().reload()
        }
    }

    override fun stop() = syncCall("stop", Unit) { browser.navigation().stop() }

    override fun canGoBack(): Boolean = syncCall("canGoBack", false) { browser.navigation().canGoBack() }

    override fun canGoForward(): Boolean = syncCall("canGoForward", false) { browser.navigation().canGoForward() }

    // ============================================================
    // ZOOM CONTROLS
    // ============================================================

    override fun getZoomLevel(): Double = syncCall("zoomLevel", 1.0) { browser.zoom().level().value() }

    override fun setZoomLevel(level: Double) {
        syncCall("setZoomLevel", Unit) { browser.zoom().level(ZoomLevel.of(level)) }
        notifyZoomListeners()
    }

    override fun zoomIn() {
        syncCall("zoomIn", Unit) { browser.zoom().`in`() }
        notifyZoomListeners()
    }

    override fun zoomOut() {
        syncCall("zoomOut", Unit) { browser.zoom().out() }
        notifyZoomListeners()
    }

    override fun resetZoom() {
        syncCall("resetZoom", Unit) { browser.zoom().reset() }
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
        // Same replay, same reason as addTitleListener: a listener attached after loading
        // finished would otherwise sit at its initial assumption forever. Unlike the title there
        // is no "blank" to skip - false is a real, useful answer.
        runCatching { listener(_isLoading) }
            .onFailure { logger.warn(LogCategory.BROWSER, "Loading listener threw on replay", error = it) }
    }

    override fun removeLoadingListener(listener: (Boolean) -> Unit) {
        loadingListeners.remove(listener)
    }

    // ============================================================
    // SECURITY
    // ============================================================

    override fun isSecure(): Boolean {
        // Through getCurrentUrl, not browser.url(): this was the same unguarded round trip,
        // reached from toolbar UI, and "" does not start with https so a dead handle reads
        // as not-secure rather than throwing into the composable.
        val url = getCurrentUrl()
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
     * 1. CreatePopupCallback allows JxBrowser to create a temporary popup browser, and records
     *    the target URL it is handed - the only place the destination is stated outright
     * 2. OpenPopupCallback intercepts before the popup is shown:
     *    - Empty bounds (Rect.empty()) indicates target="_blank" or cmd+click → route to new tab
     *    - Non-empty bounds indicates OAuth window or actual popup → allow to proceed
     */
    private fun setupPopupHandler() {
        // Phase 1: Allow popup browser creation, and keep the target URL it arrives with.
        // OpenPopupCallback's params carry no URL, so without this the destination has to be
        // recovered from the popup browser after the fact, which races the page's own scripts.
        browser.set(
            CreatePopupCallback::class.java,
            CreatePopupCallback { params ->
                popupTargets.record(
                    try {
                        params.targetUrl()
                    } catch (_: Exception) {
                        ""
                    },
                )
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

                // Claimed for EVERY popup, including the OAuth-window branch below. The queue
                // pairs creates with opens in order, so skipping the claim on one branch would
                // hand the next link's tab a URL meant for this window.
                val createTargetUrl = popupTargets.claim()
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
                    val urlSubscriptions = mutableListOf<Subscription>()
                    val scope = CoroutineScope(Dispatchers.Default + Job())

                    fun resolveFromBrowser() {
                        if (urlDeferred.isCompleted) return
                        val u =
                            try {
                                popupBrowser.url()
                            } catch (_: Exception) {
                                null
                            }
                        usablePopupUrl(u)?.let { urlDeferred.complete(it) }
                    }

                    // A popup can die between the show request and here - navigating to a
                    // download destroys it - and every call below then throws "closed object".
                    // Unguarded, that escapes the callback with the capture entry installed and
                    // no coroutine left to remove it: a permanent strong reference to a dead
                    // Browser in a process-wide map, plus a deferred nobody ever completes.
                    try {
                        val alreadyNavigated = usablePopupUrl(targetUrl)
                        if (alreadyNavigated != null) {
                            urlDeferred.complete(alreadyNavigated)
                        } else {
                            // NavigationStarted carries the URL on the event, so this no longer
                            // has to re-read popupBrowser.url() and hope it had committed.
                            urlSubscriptions +=
                                popupBrowser.navigation().on(NavigationStarted::class.java) { event ->
                                    try {
                                        if (!event.isInMainFrame || event.isSameDocument) return@on
                                        // A popup's initial empty document fires one of these too.
                                        val started = usablePopupUrl(event.url()) ?: return@on
                                        urlDeferred.complete(started)
                                    } catch (e: Exception) {
                                        logger.debug(
                                            LogCategory.BROWSER,
                                            "Popup navigation event unreadable",
                                            mapOf("error" to e.toString()),
                                        )
                                    }
                                }
                            // LoadStarted as well, kept from the original. It fires at commit, so
                            // it still answers for a popup whose navigation START was dispatched
                            // before the subscription above existed - JxBrowser does not replay
                            // events to a late subscriber, and create-then-show is an IPC hop
                            // away from here.
                            urlSubscriptions +=
                                popupBrowser.navigation().on(LoadStarted::class.java) { resolveFromBrowser() }
                            // And once more directly, closing the same gap for a navigation that
                            // had already committed by the time we got here.
                            resolveFromBrowser()
                        }
                    } catch (e: Exception) {
                        urlSubscriptions.forEach { runCatching { it.unsubscribe() } }
                        pendingPopupCaptures.remove(popupBrowser)
                        scope.cancel()
                        logger.debug(
                            LogCategory.BROWSER,
                            "Popup went away before it could be adopted",
                            mapOf("error" to e.toString()),
                        )
                        return@OpenPopupCallback OpenPopupCallback.Response.proceed()
                    }

                    scope.launch {
                        try {
                            val url = withTimeoutOrNull(POPUP_URL_TIMEOUT_MS) { urlDeferred.await() }
                            // The upload grace only buys something when there IS a navigation to
                            // carry a body. A popup that named no URL is a content window - a
                            // Document Picture-in-Picture one, or an opener writing into it - and
                            // making it sit through this before it can be shown is dead time in
                            // front of the user.
                            val capture =
                                if (url == null) {
                                    null
                                } else {
                                    withTimeoutOrNull(POPUP_UPLOAD_GRACE_MS) { captureDeferred.await() }
                                }

                            val nav = popupDestination(url, createTargetUrl, capture)

                            // Stop listening either way, but do NOT close the popup yet: whether
                            // it is disposable depends on the decision below, and closing first is
                            // what made Document Picture-in-Picture resolve and render nothing.
                            if (cleanedUp.compareAndSet(false, true)) {
                                urlSubscriptions.forEach { runCatching { it.unsubscribe() } }
                                pendingPopupCaptures.remove(popupBrowser)
                            }

                            if (nav == null) {
                                // A URL-less popup is dropped, unconditionally - restored old
                                // behaviour. `usablePopupUrl` is http(s)-only deliberately, so
                                // this also covers `file:`, `data:`, `blob:` and custom schemes,
                                // which must never become chrome-less always-on-top windows.
                                //
                                // There USED to be a branch here that showed one as a floating
                                // window, for the Document PiP flow this file no longer uses -
                                // the pop-out is the tab's real surface now. Left in place, that
                                // branch produced a second floating window whenever Google Meet,
                                // genuinely visible inside the surface pop-out, opened its own
                                // Document PiP on starting a screen share.
                                logger.debug(
                                    LogCategory.BROWSER,
                                    "Dropping a URL-less popup",
                                    mapOf("handleId" to id),
                                )
                                if (!popupBrowser.isClosed) popupBrowser.close()
                                return@launch
                            }

                            // Adopted into a tab, so the popup browser itself is redundant.
                            if (!popupBrowser.isClosed) {
                                popupBrowser.close()
                            }
                            if (capture != null && nav.postData == null) {
                                // The capture described a different request from the one we are
                                // navigating to. Keeping this visible: it is how the destination
                                // itself used to get replaced by an analytics beacon endpoint.
                                logger.warn(
                                    LogCategory.BROWSER,
                                    "Popup upload does not match the navigation, dropping the body",
                                    mapOf(
                                        "navigation" to LogSanitizer.maskUriParams(nav.url),
                                        "upload" to LogSanitizer.maskUriParams(capture.url),
                                    ),
                                )
                            }

                            // A download navigation is not a page. Chromium tears the popup down
                            // without ever committing, and opening a tab on it just re-triggers
                            // the download - the legacy handler gated on this and the deferred
                            // never resolving used to hide its absence here.
                            if (FluckEngine.isActiveDownload(nav.url)) {
                                logger.debug(
                                    LogCategory.BROWSER,
                                    "Skipping new tab for download URL",
                                    mapOf("url" to LogSanitizer.maskUriParams(nav.url)),
                                )
                                return@launch
                            }
                            // Lets FluckEngine close this tab again if a download starts right
                            // after it opens - a redirect to a file looks like a page until it
                            // does not.
                            FluckEngine.notifyTabOpened()

                            val withDataCb = openInNewTabWithDataCallback
                            if (withDataCb != null) {
                                withContext(Dispatchers.Main) { withDataCb(nav) }
                            } else {
                                withContext(Dispatchers.Main) { openInNewTabCallback?.invoke(nav.url) }
                            }

                            logger.debug(
                                LogCategory.BROWSER,
                                "Popup dispatched",
                                mapOf(
                                    "url" to LogSanitizer.maskUriParams(nav.url),
                                    "hasPost" to (nav.postData != null).toString(),
                                ),
                            )
                        } catch (e: Exception) {
                            if (cleanedUp.compareAndSet(false, true)) {
                                urlSubscriptions.forEach { it.unsubscribe() }
                                pendingPopupCaptures.remove(popupBrowser)
                            }
                            // OUTSIDE the CAS, deliberately. The happy path now sets cleanedUp
                            // before it decides what to do with the popup, so a throw after that
                            // point finds the CAS already lost and would skip the close - leaking
                            // a Browser and its renderer process. The CAS guards unsubscribe and
                            // map removal, which must happen once; closing is idempotent.
                            if (!popupBrowser.isClosed) {
                                popupBrowser.close()
                            }
                            logger.warn(LogCategory.BROWSER, "Popup handler error", error = e)
                        } finally {
                            scope.cancel()
                        }
                    }
                } else {
                    // Has dimensions = OAuth/payment popup (window.open with features)
                    showPopupInWindow(popupBrowser = popupBrowser, bounds = initialBounds)
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

    /**
     * Positions and sizes a popup window.
     *
     * A popup that asked for geometry gets it. One that did not - a Document Picture-in-Picture
     * window - goes bottom-right of the work area, inset, which is where a browser puts its own.
     */
    private fun placePopOut(
        frame: JFrame,
        bounds: Rect?,
        requestedSize: java.awt.Dimension?,
    ) {
        if (bounds != null) {
            frame.setLocation(bounds.origin().x(), bounds.origin().y())
            frame.setSize(bounds.size().width(), bounds.size().height())
            return
        }
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val width = requestedSize?.width ?: FLOATING_POPUP_WIDTH
        // The strip is added on top of the requested size rather than taken out of the video: the
        // page asked for a viewport, not a window.
        val height =
            (requestedSize?.height ?: FLOATING_POPUP_HEIGHT) + POP_OUT_BAR_HEIGHT + POP_OUT_GRIP_HEIGHT
        frame.setSize(width, height)
        frame.setLocation(
            screen.x + screen.width - width - FLOATING_POPUP_INSET,
            screen.y + screen.height - height - FLOATING_POPUP_INSET,
        )
    }

    /**
     * Builds the slim bar an undecorated pop-out is dragged by, and closed from.
     *
     * A native drag strip rather than a listener on the frame: the content below is a native
     * browser surface that consumes its own mouse events, so nothing attached to the frame or a
     * glass pane ever sees a press. This is also what a browser's own Picture-in-Picture looks
     * like - a thin header over the video, not a full title bar.
     */
    private fun buildPopOutDragBar(frame: JFrame): javax.swing.JComponent {
        val bar = javax.swing.JPanel(java.awt.BorderLayout())
        bar.background = java.awt.Color(0x1F, 0x1F, 0x1F)
        bar.preferredSize = java.awt.Dimension(0, POP_OUT_BAR_HEIGHT)
        bar.border = javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 6)

        // The ORIGIN, not the page title. A browser's own pop-out names the site, and on a
        // floating window with no address bar that is the only thing saying what it belongs to.
        val title = javax.swing.JLabel(popOutOriginLabel())
        title.foreground = java.awt.Color(0xE8, 0xEA, 0xED)
        title.font = title.font.deriveFont(java.awt.Font.PLAIN, POP_OUT_BAR_FONT_SIZE)
        bar.add(title, java.awt.BorderLayout.CENTER)

        // Right-aligned, evenly spaced, tight against the edge. A Swing button reserves margin
        // and border insets by default, which is what spread these across the whole bar instead
        // of grouping them in the corner: every one has to be sized explicitly.
        val actions = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0))
        actions.isOpaque = false

        // Point size per glyph, not one for both: an arrow and a multiplication sign carry very
        // different ink at the same size, and matching the numbers left the arrow visibly smaller
        // than the close. These are chosen to look equal, not to be equal.
        fun barButton(
            glyph: String,
            tip: String,
            pointSize: Float,
            onClick: () -> Unit,
        ): javax.swing.JButton {
            val button = javax.swing.JButton(glyph)
            button.foreground = java.awt.Color(0xE8, 0xEA, 0xED)
            button.isBorderPainted = false
            button.isFocusPainted = false
            button.isContentAreaFilled = false
            button.isOpaque = false
            button.margin = java.awt.Insets(0, 0, 0, 0)
            button.border = javax.swing.BorderFactory.createEmptyBorder()
            button.preferredSize = java.awt.Dimension(POP_OUT_ICON_SIZE, POP_OUT_ICON_SIZE)
            button.font = button.font.deriveFont(java.awt.Font.PLAIN, pointSize)
            button.toolTipText = tip
            button.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            button.horizontalAlignment = javax.swing.SwingConstants.CENTER
            button.addActionListener { onClick() }
            return button
        }

        actions.add(
            barButton("\u2921", "Back to tab", POP_OUT_ARROW_FONT_SIZE) {
                // Selecting the tab recomposes its surface, which closes this window through
                // onSurfaceShown - but that only arrives if the selection lands, so the close
                // below is direct rather than hoped for. Both are idempotent.
                returnToTab()
                closeSurfacePopOut()
            },
        )

        val close =
            barButton("\u00d7", "Close", POP_OUT_CLOSE_FONT_SIZE) {
                // Closes the window only. The tab stays backgrounded; its next selection
                // recomposes the surface as any backgrounded tab's would.
                closeSurfacePopOut()
            }
        actions.add(close)
        bar.add(actions, java.awt.BorderLayout.EAST)

        val origin = java.awt.Point()
        val drag =
            object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    // Converted to the FRAME's space: this listener is installed on the bar and
                    // on the title label, and the label sits ~12px in, so storing the raw event
                    // point made a drag started on the title snap the window by that offset
                    // before it began tracking.
                    origin.setLocation(SwingUtilities.convertPoint(e.component, e.point, frame))
                }

                override fun mouseDragged(e: java.awt.event.MouseEvent) {
                    val at = e.locationOnScreen
                    frame.setLocation(at.x - origin.x, at.y - origin.y)
                }
            }
        bar.addMouseListener(drag)
        bar.addMouseMotionListener(drag)
        title.addMouseListener(drag)
        title.addMouseMotionListener(drag)
        bar.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)

        return bar
    }

    /**
     * The strip along the bottom of an undecorated pop-out, which is how it is resized.
     *
     * Three zones rather than one. The whole strip used to carry the bottom-RIGHT resize cursor
     * and bottom-right semantics, so hovering the bottom-LEFT corner promised a resize that grew
     * the window away from the pointer. Each zone now shows the cursor for what it actually does:
     * the corners resize in their own direction, the span between them is a plain bottom edge.
     */
    private fun buildPopOutResizeGrip(frame: JFrame): javax.swing.JComponent {
        val grip = javax.swing.JPanel(java.awt.BorderLayout())
        grip.background = java.awt.Color(0x1F, 0x1F, 0x1F)
        grip.preferredSize = java.awt.Dimension(0, POP_OUT_GRIP_HEIGHT)
        grip.cursor = gripCursorFor(GripZone.BOTTOM)

        val start = java.awt.Point()
        val startBounds = java.awt.Rectangle()
        var zone = GripZone.BOTTOM
        val resize =
            object : java.awt.event.MouseAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    grip.cursor = gripCursorFor(gripZoneAt(e.x, grip.width))
                }

                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    zone = gripZoneAt(e.x, grip.width)
                    start.setLocation(e.locationOnScreen)
                    startBounds.bounds = frame.bounds
                }

                override fun mouseDragged(e: java.awt.event.MouseEvent) {
                    val at = e.locationOnScreen
                    frame.bounds =
                        resizedPopOutBounds(startBounds, zone, at.x - start.x, at.y - start.y)
                    frame.validate()
                }
            }
        grip.addMouseListener(resize)
        grip.addMouseMotionListener(resize)
        return grip
    }

    /** The host of the page this pop-out belongs to, which is what a browser shows here. */
    private fun popOutOriginLabel(): String =
        runCatching {
            java.net
                .URI(browser.url())
                .host
                .orEmpty()
                .removePrefix("www.")
        }.getOrDefault("")

    /**
     * Brings the window holding this tab back to the front.
     *
     * Raises the window and selects the tab, through [PopOutReturnRequests] - the window that
     * owns the tab resolves its panel and selects it, because selecting is `SplitViewState`'s and
     * this class has no route to it.
     *
     * No plugin API change was needed for that, which is worth knowing before someone adds one:
     * the tab id arrives already, in [setFullscreenHandler]. If [ownerTabId] is still null - a
     * plugin that never registered a fullscreen handler - this degrades to raising the window,
     * which is what it did before and is still useful.
     */
    private fun returnToTab() {
        runCatching { WindowFocusManager.focusWindow(currentWindowId) }
        ownerTabId?.let { tabId ->
            if (!PopOutReturnRequests.request(currentWindowId, tabId)) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Back-to-tab request was dropped",
                    mapOf("handleId" to id, "tabId" to tabId),
                )
            }
        }
        runCatching { browser.focus() }
    }

    /**
     * Shows a popup browser in its own decorated Swing window.
     *
     * One caller: a popup that asked for geometry, which is an OAuth or payment window. It gets
     * a normal titled window, and [bounds] is non-null because a popup with no geometry never
     * reaches here - those are adopted as tabs, or dropped.
     *
     * This used to take a null [bounds] as well and answer it with a chrome-less always-on-top
     * window, for the Document Picture-in-Picture flow that the surface pop-out replaced. That
     * shape is gone rather than merely unreachable: an undecorated, address-bar-less window
     * showing page-chosen content is worth making impossible to reach by accident, and the
     * pop-out builds its own frame in [openSurfacePopOut].
     */
    private fun showPopupInWindow(
        popupBrowser: Browser,
        bounds: Rect,
    ) {
        SwingUtilities.invokeLater {
            try {
                val frame = JFrame()
                val subscriptions = mutableListOf<Subscription>()

                frame.title = "Popup"
                frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                frame.iconImages = BossWindowIcon.images

                placePopOut(frame, bounds, null)

                // A popup browser has no handle of its own, so it never went through
                // setupBrowserHandlers. Claim its file dialogs before the Swing view below
                // installs the JFileChooser ones.
                NativeFileDialogs.installOn(popupBrowser)

                val browserView =
                    com.teamdev.jxbrowser.view.swing.BrowserView
                        .newInstance(popupBrowser)
                frame.contentPane.add(browserView)

                // Replaces JxBrowser's built-in Swing context menu, which crashes the EDT here: it
                // positions itself from getLocationOnScreen() inside an invokeLater, and a popup
                // can close itself the moment its flow completes, so a right-click landing on that
                // boundary asks a disposed component where it is (BossConsole-Releases#17).
                installPopupWindowChrome(popupBrowser, browserView)

                subscriptions +=
                    popupBrowser.on(TitleChanged::class.java) { event ->
                        SwingUtilities.invokeLater { frame.title = event.title() }
                    }

                subscriptions +=
                    popupBrowser.on(BrowserClosed::class.java) {
                        SwingUtilities.invokeLater {
                            subscriptions.forEach { runCatching { it.unsubscribe() } }
                            frame.dispose()
                        }
                    }

                frame.addWindowListener(
                    object : java.awt.event.WindowAdapter() {
                        override fun windowClosing(e: java.awt.event.WindowEvent?) {
                            subscriptions.forEach { runCatching { it.unsubscribe() } }
                            if (!popupBrowser.isClosed) {
                                popupBrowser.close()
                            }
                        }
                    },
                )

                frame.isVisible = true
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Error creating popup window", error = e)
                if (!popupBrowser.isClosed) {
                    popupBrowser.close()
                }
            }
        }
    }

    /**
     * Called when this tab's surface leaves composition - the host's signal that a tab was
     * backgrounded.
     *
     * The page *can* see this for itself: `document.visibilityState` does flip to `hidden` on a
     * BOSS tab switch (measured), because Chromium derives visibility from the native widget
     * detaching even though nothing here calls a visibility API. So the trigger is not what the
     * page lacks.
     *
     * What it lacks is a **gesture**. Both Picture-in-Picture APIs refuse without transient user
     * activation, and a tab switch carries none - so a page-side `visibilitychange` listener
     * could notice the switch and still not be allowed to act on it. Chrome closes that gap
     * inside Blink, minting an activation before invoking the site's media-session handler; an
     * embedder has to mint one itself, which is why this lives in Kotlin.
     */
    private fun onSurfaceHidden() {
        if (!isValid) return
        val url = runCatching { browser.url() }.getOrDefault("")
        val capturing = captureTracker.isCapturing()
        val eligible =
            shouldAutoPictureInPicture(
                url = url,
                isCapturing = capturing,
                alreadyPoppedOut = autoPoppedOut.get(),
                enabled = AutoPipSettingsManager.isEnabled(),
            )
        logger.debug(
            LogCategory.BROWSER,
            "Tab hidden, auto Picture-in-Picture gate",
            mapOf(
                "handleId" to id,
                "eligible" to eligible.toString(),
                "capturing" to capturing.toString(),
                "poppedOut" to autoPoppedOut.get().toString(),
                "url" to LogSanitizer.maskUriParams(url),
            ),
        )
        if (!eligible) return
        autoPictureInPictureScope.launch {
            // A tab dragged between windows disposes one surface and composes another, in
            // unspecified order, so a move momentarily looks exactly like a background. Waiting
            // lets the new surface arrive and cancel this. The delay doubles as the fullscreen
            // window's COMPOSE_DETACH_DELAY: JxBrowser allows one rendering surface per browser,
            // and the Compose one needs a beat to let go before a Swing view can claim it.
            delay(AUTO_PIP_SETTLE_MS)
            if (composedSurfaces.get() > 0 || !isValid) return@launch
            if (!captureTracker.isCapturing()) return@launch
            // A real HTML-fullscreen session owns the one surface this would need. Compared
            // against THIS tab: a global check suppressed the pop-out whenever any other tab was
            // fullscreen, and ownerTabId is known by now.
            val fullscreenTab = TabFullscreenStateManager.fullscreenTabId.value
            if (fullscreenTab != null && fullscreenTab == ownerTabId) return@launch
            if (!autoPoppedOut.compareAndSet(false, true)) return@launch
            SwingUtilities.invokeLater { openSurfacePopOut() }
        }
    }

    /**
     * Puts the tab's REAL rendering surface in a small always-on-top window.
     *
     * This replaced a Document Picture-in-Picture window we filled with cloned tiles, and the
     * reason is architectural, measured over a day of failures: a hidden tab is a dead end.
     * Its DOM never mounts a new element (no rendering pipeline), and Meet's SFU does not even
     * FORWARD video for tiles the client is not rendering - every remote receiver sat muted
     * while a participant was visibly on the call. No script can show pixels the server will
     * not send. With the actual surface visible in this window, the page renders normally,
     * the SFU subscribes normally, and joins, drops, shares and names are simply the page.
     *
     * Chrome solves the same problem the same way at a different layer: its PiP window is a
     * real visible web contents that Meet renders into.
     *
     * The reparent follows [ai.rever.boss.tabfullscreen.FullscreenBrowserWindow]: the tab's
     * Compose view is already out of composition (that is what "backgrounded" means here), the
     * settle delay above played the role of its COMPOSE_DETACH_DELAY, and on the way back the
     * tab's view state is recreated - a Compose view whose surface a Swing view has held does
     * not reconnect by itself.
     */

    /**
     * Closes the pop-out when the browser behind it dies without a dispose().
     *
     * `BrowserClosed` sets `disposed = true`, and `dispose()` returns on its first line when that
     * is already set - so a crashed renderer or an engine recycle never reaches the cleanup.
     * The tab is backgrounded by definition while popped out, so nothing tears its composition
     * down either: without this the window stays on screen, undecorated and always-on-top, over
     * a dead surface. `showPopupInWindow` subscribes to the same event for the same reason.
     */
    private fun closePopOutWhenBrowserDies() {
        runCatching {
            browser.on(BrowserClosed::class.java) {
                SwingUtilities.invokeLater { closeSurfacePopOut() }
            }
        }
    }

    private fun openSurfacePopOut() {
        if (popOutFrame != null || !isValid || browser.isClosed()) {
            if (popOutFrame == null) autoPoppedOut.set(false)
            return
        }
        // Re-checked HERE, on the EDT, and not only before the flag was raised. The settle
        // coroutine tests composedSurfaces and then raises autoPoppedOut, and a tab re-composed
        // between those two steps finds the flag still false - so onSurfaceShown returns early,
        // this opens over a tab the user is looking at, and the one signal that would have
        // closed it has already fired. Taking the surface from a visible tab leaves it blank
        // behind an always-on-top window that has to be dismissed by hand.
        if (composedSurfaces.get() > 0) {
            autoPoppedOut.set(false)
            return
        }
        try {
            val frame = JFrame()
            frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            frame.iconImages = BossWindowIcon.images
            frame.isAlwaysOnTop = true
            frame.isUndecorated = true
            frame.addWindowListener(
                object : java.awt.event.WindowAdapter() {
                    override fun windowClosing(e: java.awt.event.WindowEvent?) {
                        closeSurfacePopOut()
                    }
                },
            )
            frame.contentPane.layout = java.awt.BorderLayout()
            frame.contentPane.add(buildPopOutDragBar(frame), java.awt.BorderLayout.NORTH)
            val view =
                com.teamdev.jxbrowser.view.swing.BrowserView
                    .newInstance(browser)
            frame.contentPane.add(view, java.awt.BorderLayout.CENTER)
            frame.contentPane.add(buildPopOutResizeGrip(frame), java.awt.BorderLayout.SOUTH)
            placePopOut(frame, null, java.awt.Dimension(SURFACE_POP_OUT_WIDTH, SURFACE_POP_OUT_HEIGHT))
            frame.isVisible = true
            popOutFrame = frame
            popOutView = view
            closePopOutWhenBrowserDies()
            logger.info(
                LogCategory.BROWSER,
                "Surface pop-out opened",
                mapOf("handleId" to id, "tabId" to (ownerTabId ?: "")),
            )
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Could not open the surface pop-out", error = e)
            autoPoppedOut.set(false)
        }
    }

    /**
     * Runs [closeSurfacePopOut] on the EDT, waiting at most [POP_OUT_EDT_TIMEOUT_MS] for it.
     *
     * See [dispose] for why the wait is bounded rather than an `invokeAndWait`.
     */
    private fun closePopOutOnEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            closeSurfacePopOut()
            return
        }
        val task = java.util.concurrent.FutureTask<Unit> { closeSurfacePopOut() }
        SwingUtilities.invokeLater(task)
        try {
            task.get(POP_OUT_EDT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn(LogCategory.BROWSER, "Interrupted while closing the surface pop-out", error = e)
        } catch (e: java.util.concurrent.TimeoutException) {
            // Left queued deliberately: the caller stops waiting, the window still closes once
            // the EDT is responsive again.
            logger.warn(
                LogCategory.BROWSER,
                "Timed out closing the surface pop-out on the EDT; the close remains queued",
                error = e,
            )
        } catch (e: java.util.concurrent.ExecutionException) {
            logger.warn(LogCategory.BROWSER, "Closing the surface pop-out failed", error = e)
        }
    }

    /**
     * Closes the pop-out and hands the surface back, in [FullscreenBrowserWindow]'s order:
     * hide and detach the Swing view, dispose the frame, and only after a beat ask the tab to
     * recreate its view state - the Compose view that existed before the Swing one took the
     * surface is permanently disconnected, and without the recreation signal the user comes
     * back to a frozen tab.
     */
    private fun closeSurfacePopOut() {
        val frame = popOutFrame ?: return
        popOutFrame = null
        val view = popOutView
        popOutView = null
        autoPoppedOut.set(false)
        try {
            view?.let {
                it.isVisible = false
                it.repaint()
                frame.contentPane.remove(it)
            }
            frame.contentPane.revalidate()
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error detaching the surface pop-out view", error = e)
        } finally {
            // In a finally, because popOutFrame was nulled above: if the detach throws - and
            // ObjectClosedException from a concurrently closing browser is live here - a dispose
            // inside the try would be skipped and nothing could ever close this window again.
            // Its own close button routes through this function, which now returns at the null
            // check, so it would sit undecorated and always-on-top for the rest of the session.
            runCatching {
                frame.isAlwaysOnTop = false
                frame.dispose()
            }
        }
        // The release delay is FullscreenBrowserWindow's SWING_RELEASE_DELAY: the disposed
        // Swing view needs a beat to let go of the rendering surface. Then the repair is the
        // file's own: bump [viewGeneration], which forces the BrowserView node out of
        // composition and back - the same re-attach the frame-stall watchdog uses. The view
        // STATE is deliberately not rebuilt: closing the composed state was tried and produced
        // a permanently black tab (the composed node held a closed state, and one-view-per-
        // browser kept the replacement from attaching); a browser.resize() jiggle was tried and
        // repaired nothing. Re-attaching alone gives the surface its composed bounds back.
        javax.swing
            .Timer(SURFACE_RELEASE_DELAY_MS) {
                viewGeneration += 1
                logger.info(
                    LogCategory.BROWSER,
                    "Surface handed back, view re-attach forced",
                    mapOf("handleId" to id, "viewGeneration" to viewGeneration.toString()),
                )
            }.apply {
                isRepeats = false
                start()
            }
        logger.info(
            LogCategory.BROWSER,
            "Surface pop-out closed",
            mapOf("handleId" to id, "tabId" to (ownerTabId ?: "")),
        )
    }

    /** Called when this tab's surface is composed again - the user came back. */
    private fun onSurfaceShown() {
        if (!autoPoppedOut.get()) return
        SwingUtilities.invokeLater { closeSurfacePopOut() }
    }

    /**
     * The one round trip on this handle whose thread the *caller* used to pick.
     *
     * A non-suspend override on the public [BrowserHandle] interface that blocked on the renderer
     * inline, so a plugin calling it from a `Dispatchers.Main` coroutine or a Compose click handler
     * made the freeze this class was otherwise fixed for - and `BrowserMainThreadRoundTripTest`
     * cannot see it, because there is no Main marker at the call site: the EDT-ness came from a
     * caller a module away.
     *
     * [BoundedBrowserCall.post] rather than [BoundedBrowserCall.call] because there is nothing to
     * await - this returns Unit, and a non-suspend caller has no context to suspend in anyway. The
     * cost is that a caller now learns nothing about failure beyond the log, which it did not learn
     * before either.
     */
    override fun requestPictureInPicture() {
        if (!isValid) return
        handleCall.post {
            browser.mainFrame().ifPresent { frame ->
                try {
                    frame.executeJavaScript<Unit>(BrowserJavaScripts.enablePictureInPicture)
                    logger.debug(LogCategory.BROWSER, "Requested Picture-in-Picture mode")
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Failed to request Picture-in-Picture", error = e)
                }
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
        // Kept for Back-to-tab. This is the only place the plugin tells the host which tab owns
        // this browser, and the host cannot work it out for itself - the tab is a dynamic
        // plugin's component type, which host code cannot name.
        ownerTabId = tabId

        FluckEngine.setupFullscreenHandler(
            browser = browser,
            tabId = tabId,
            ownerWindowId = ownerWindowId,
            onFullscreenEnter = {
                // The pop-out is holding this browser's one rendering surface, and fullscreen is
                // about to reparent it. Closing first hands it back; leaving both would be the
                // black-surface conflict this file is built around. Pressing a site's own
                // fullscreen control while a call is popped out is an ordinary thing to do.
                if (popOutFrame != null) {
                    closePopOutOnEdt()
                }
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
    // CLIPBOARD OPERATIONS
    // ============================================================

    override fun copySelection() {
        editorCommand(EditorCommand.copy())
    }

    override fun paste() {
        editorCommand(EditorCommand.paste())
    }

    override fun cut() {
        editorCommand(EditorCommand.cut())
    }

    override fun selectAll() {
        editorCommand(EditorCommand.selectAll())
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
     * **On the frame choice, and a comment in this package that says the opposite.**
     * [Browser.focusedFrame] first, `mainFrame()` only as a fallback: the caret is what an editor
     * command acts on, and it routinely sits in a subframe. `PopupWindowContextMenu` reaches the
     * other conclusion for its own menu ("browser.focusedFrame() would answer for the wrong frame
     * inside an iframe") and it is right there: a right-click has a frame Chromium already
     * resolved for that exact click, `params.frame()`, which beats any inference. These are not
     * in conflict so much as differently supplied — that callback has the accurate frame in hand
     * and this method does not.
     *
     * What that costs, stated plainly: for a caller reaching `copySelection()` on a non-editable
     * selection while an iframe holds keyboard focus, `focusedFrame()` is the iframe and the
     * command acts on its empty selection. `mainFrame()` would have been right there. That case
     * is not reachable through the browser plugin's menu today — its non-editable branch copies
     * the reported selection through AWT and never calls this, and the editable branch is gated
     * on `isEditable`, which `toContextMenuInfo` computes for the main frame only, so a
     * right-click that reaches here has focused a main-frame editable element. It is reachable by
     * any other plugin holding a [BrowserHandle].
     *
     * The durable answer is to prefer the frame the context-menu callback already resolved
     * (`BrowserHandleImpl` line ~1232 keeps `params.frame()`), held weakly and only while its
     * menu is live, with `focusedFrame()` then `mainFrame()` behind it. Not done here: it changes
     * the shape of the handle for a case nothing currently hits.
     *
     * Never throws: this runs from context-menu handlers on a JxBrowser callback thread, where
     * an escaping exception has no owner. A refusal is logged rather than returned, because
     * `BrowserHandle` declares these as `Unit` and widening that would force a plugin-api
     * release for a signal only this log needs.
     */
    @Suppress("TooGenericExceptionCaught") // Matches PopupWindowContextMenu: Error must propagate.
    private fun editorCommand(command: EditorCommand): Boolean {
        if (!isValid) return false
        // The command's own identity, not a hand-passed label: a second parameter would let
        // editorCommand(EditorCommand.paste(), "Copy") compile and mislabel every log line it
        // produced.
        val what = command.name().name
        val accepted =
            try {
                executeEditorCommand(
                    focusedFrame = browser.focusedFrame().orElse(null),
                    mainFrame = browser.mainFrame().orElse(null),
                    command = command,
                )
            } catch (e: Exception) {
                // Exception, not Throwable: `runCatching` here would swallow Error, which
                // PopupWindowContextMenu in this same package deliberately lets propagate. Two
                // stances on one failure class in one package is how the next reader gets it wrong.
                logger.warn(LogCategory.BROWSER, "$what failed", mapOf("handleId" to id), error = e)
                false
            }
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
            // Third consumer of the same signal, and the only one the user can see: this is where
            // "the tab was switched away from" is noticed. The page sees the switch too - its
            // visibilityState really does go hidden - but it cannot act on it without a gesture,
            // which is what onSurfaceHidden mints.
            onSurfaceShown()
            onDispose {
                visitTracker.setVisible(false)
                composedSurfaces.decrementAndGet()
                onSurfaceHidden()
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
            // Re-entering composition is not by itself "the user switched to this tab". This file
            // already establishes two other ways in: a cross-window move builds one composition
            // and tears down the other (why the visibility effect ref-counts), and the surface
            // effect exists because the window a tab composes in can differ from the one it came
            // from. A workspace restore, a pane split or a tab moving between panes would all end
            // in focus() otherwise, including in a window nobody is looking at - and with two
            // browser tabs side by side, a rebuild would have both handles calling focus() with
            // the last runnable winning. Gating on the host window being focused keeps the case
            // this fixes (a tab switch in the window you are using) and drops the rest.
            // FluckEngine's key-input gate reads the same signal the same way.
            val focusOnShow =
                shouldFocusOnShow(
                    mode = JxBrowserConfig.renderingMode,
                    alreadyShown = shownBefore.getAndSet(true),
                    hostWindowFocused = hostWindowId?.let(WindowFocusManager::isWindowFocused) == true,
                )
            // Cancelled if this composition leaves before the runnable gets to run. `isValid`
            // answers "not disposed, engine generation still matches" - it does NOT answer "still
            // on screen", and the gap is reachable: on a fast A->B->A, B queues a focus, leaves
            // composition, and the runnable then focuses B, whose native view is still alive
            // precisely because HARDWARE mode retains the surface. Same first-responder confusion
            // this effect exists to fix, pointed the other way. A click on the URL bar or another
            // pane in the interval is the same race with a different loser.
            var stillComposed = true
            if (focusOnShow) {
                // invokeLater, not a direct call: child effects run before parent ones, so the
                // native view has been shown by now, but the focus request still reads better one
                // turn of the event loop later than in the middle of applying this frame.
                SwingUtilities.invokeLater {
                    if (stillComposed && isValid) {
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
            onDispose { stillComposed = false }
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

        // Read here rather than at their first use further down: the registration effect below and
        // the find-bar effect both need them, and a CompositionLocal read has no ordering
        // constraint. The comment explaining what they mean lives with the find-bar effect.
        val isPanelActive = LocalIsPanelActive.current
        val inMainPanel = LocalInMainWindowPanel.current

        // Which browser the View menu's Zoom In / Zoom Out / Actual Size / Reload act on in this
        // window. Registered from here rather than from the tab component because the tab
        // component is a DYNAMIC plugin's class (fluck-browser's FluckBrowserTabComponent): the
        // host cannot name its type, which is exactly why the `is FluckTabComponent` test in
        // BossAppMenuActionEffects was always false and those four menu items did nothing.
        //
        // Its own effect rather than a key on the currentWindowId effect above: that one is
        // deliberately about telemetry attribution and must not fire when only panel activation
        // changed, whereas this one must - clicking into the other half of a split changes nothing
        // else here.
        //
        // Keyed on all three inputs, so a tab moved to another window re-registers under the new
        // one; a tab hidden and re-shown leaves and re-enters composition, and the fresh
        // registration's higher sequence is what makes "most recently shown wins" true without a
        // separate hook; and a split whose active panel changed re-registers both surfaces with
        // current flags.
        DisposableEffect(hostWindowId, isPanelActive, inMainPanel) {
            val registrationWindowId = hostWindowId
            val token =
                if (registrationWindowId != null) {
                    ActiveBrowserRegistry.register(
                        handle = this@BrowserHandleImpl,
                        windowId = registrationWindowId,
                        inMainPanel = inMainPanel,
                        panelActive = isPanelActive,
                    )
                } else {
                    null
                }
            onDispose { ActiveBrowserRegistry.unregister(id, token) }
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

        val findState = remember(browser) { BrowserFindController.stateFor(browser) }

        // Where the find bar goes: this pane's rectangle, in dp relative to the window's content
        // pane, already inset by the bar's corner margin.
        //
        // Snapshot state of its own rather than the @Volatile browserViewBoundsInWindow the pinch
        // gate uses. That field is read from AWT callbacks and deliberately not observable, so a
        // bar placed from it would never move when the split divider was dragged. Belonging to
        // this composition is also correct rather than incidental: a tab moved to another window
        // gets a fresh one instead of inheriting a rectangle measured in the window it left.
        var findRegionInWindow by remember { mutableStateOf<IntRect?>(null) }

        // Cmd+F that AWT consumed before the browser could see it, dispatched by the browser.find
        // keymap action. Collected HERE, in the composition that knows it is the visible surface of
        // the active panel in this window, so no registry has to answer "which browser".
        //
        // The event is a broadcast, so more than one surface can qualify: LocalIsPanelActive
        // defaults to `true`, which makes a browser in a sidebar slot look as active as the one in
        // the main content area. claimShortcut alone would then pick by whichever coroutine resumed
        // first - a coin flip between the page being read and the sidebar, which reads as exactly
        // the flakiness this change removes. So a surface outside a main window panel yields first
        // and claims only if nothing better did, which makes the winner deterministic. The
        // non-preferred branch still gets served, a beat later, so a sidebar-only browser is not
        // cut off.
        //
        // isPanelActive and inMainPanel are read further up, where the registry registration also
        // needs them.
        LaunchedEffect(browser, hostWindowId, isPanelActive, inMainPanel) {
            if (hostWindowId == null || !isPanelActive) return@LaunchedEffect
            MenuActionsHandler.browserFindEvents.collect { eventWindowId ->
                if (eventWindowId != hostWindowId) return@collect
                if (!inMainPanel) delay(SHORTCUT_DEFERRAL_MS)
                if (BrowserFindController.claimShortcut(eventWindowId)) {
                    BrowserFindController.onFindKeyFromShortcut(browser)
                }
            }
        }

        // Render the browser view if available with mouse button handling.
        //
        // Keyed on viewGeneration so the stall watchdog can force the view out of composition and
        // back, which is what re-attaches the native view when a committed page never drew. The
        // BrowserViewState is deliberately NOT rebuilt with it: the manual repair this imitates (a
        // tab switch) reuses the retained surface too, so re-attaching is what matters and
        // rebuilding the surface would cost far more than it fixes.
        //
        // Wrapped in a Box so the find bar has a scope to anchor in. Under OFF_SCREEN it draws in
        // place, aligned inside THIS pane; under HARDWARE_ACCELERATED - the default on every
        // platform - OverlayCorner escapes it into its own always-on-top window, because a
        // lightweight overlay renders behind the native GPU surface.
        Box(modifier = Modifier.fillMaxSize()) {
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
                                    val viewBounds = coords.boundsInWindow()
                                    browserViewBoundsInWindow = viewBounds
                                    // Captured with the bounds, from the same layout pass, so the two can
                                    // never describe different displays after a window is dragged between
                                    // monitors. See pointerInsideBounds for why the pairing is required.
                                    browserViewDensity = viewDensity
                                    // Same rectangle, converted for the find bar. Dp, because that is
                                    // what a heavyweight overlay is placed in - AWT's logical units map
                                    // 1:1 to dp, so an overlay positioned from device pixels lands off
                                    // by the scale factor on any HiDPI display.
                                    findRegionInWindow = findBarRegion(viewBounds, viewDensity)
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

            // Composed only while the bar is up. A heavyweight corner overlay is content-sized but
            // still swallows the clicks under itself, so one composed unconditionally would leave a
            // permanently dead rectangle over the page.
            //
            // A null region means this pane has not been measured yet (or was just torn down). Not
            // drawing is deliberate: HeavyweightCorner resolves an unmeasured parent to the screen
            // origin, so the alternative is an always-on-top bar in the corner of the primary
            // display rather than over the page it belongs to.
            val findRegion = findRegionInWindow
            if (findState.visible && findRegion != null) {
                // Which path OverlayCorner will take, asked before deciding where the corner margin
                // goes. Its lightweight branch aligns inside this BoxScope and ignores
                // regionInWindow for the same reason it ignores `inset`, so the margin baked into
                // the region is simply lost there and the bar sits flush in the pane's corner.
                // Reachable via BOSS_RENDERING_MODE=OFF_SCREEN, a supported escape hatch. Same trap
                // FocusModeQuickActions documents, and the reason overlayCornerIsHeavyweight exists.
                val heavyweight = overlayCornerIsHeavyweight()
                OverlayCorner(
                    alignment = Alignment.TopEnd,
                    initialSize = FIND_BAR_CEILING,
                    focusable = true,
                    regionInWindow = findRegion,
                ) {
                    // Margin as region inset on the heavyweight path so it is not a dead band that
                    // swallows clicks over the page, and as ordinary padding on the lightweight one,
                    // where nothing is swallowed and the region is not read at all.
                    Box(modifier = if (heavyweight) Modifier else Modifier.padding(FIND_BAR_MARGIN)) {
                        BrowserFindBar(browser = browser, state = findState)
                    }
                }
            }
        }
    }

    override fun dispose() {
        // Synchronously, and before the guard below: invokeLater would let browser.close() run
        // first, and closing the browser under a still-attached Swing view is exactly the
        // ordering that leaves an undecorated always-on-top window on screen with nothing able
        // to dispose it.
        //
        // BOUNDED, not invokeAndWait, and copied from FullscreenBrowserWindow's own EDT hop for
        // the same reason: disposeBrowser is a suspend function, so this can arrive off the EDT,
        // and an unbounded block there deadlocks the moment the EDT is itself waiting on
        // anything this thread holds. A timeout turns that into a late cleanup instead of a
        // frozen app, and the task stays queued so the window is still disposed once the EDT
        // frees up. On the EDT already - composition teardown - it runs inline.
        closePopOutOnEdt()
        if (!disposed.compareAndSet(false, true)) return
        rendererPid.onGone()
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
        // Same for the page event channel: a disposed tab must not deliver another event, and the
        // sink belongs to a plugin that may itself be going away.
        pageEventScript = null
        pageEventBridge.onEvent = null
        // The provider closes over `browser`, so it goes too rather than outliving the handle.
        pageEventBridge.urlProvider = { "" }
        pageEventScope.cancel()
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
        // A pending commit follow-up outlives the tab otherwise, and its next act is a blocking
        // round trip against a browser being torn down. shutdown() not shutdownNow(), for the
        // reason the two above give: the thread is daemon and a call already inside JxBrowser
        // cannot be interrupted, so interrupting would buy nothing.
        pageInjectJob.getAndSet(null)?.cancel()
        pageInjectScope.cancel()
        pageInjectExecutor.shutdown()
        // Last of the four. Note what this ordering does NOT buy: coBrowseScope and pageEventScope
        // were cancelled above, and cancelling a scope also cancels children that were dispatched but
        // have not started - startCoroutineCancellable means DispatchedTask.run sees an inactive job
        // and resumes with the cancellation instead of running the body. So the teardown queued by
        // stopCoBrowseCapture (recordStop, setControlGuard(false)) does not run here, and did not
        // before this change either, when both scopes were cancelled the same way on Main.
        //
        // Left alone rather than re-posted outside the cancelled scope: the browser is closing a few
        // lines below, so stopping a recorder in a page that is about to go away buys nothing.
        // See [BoundedBrowserCall.shutdown] for why not shutdownNow().
        handleCall.shutdown()
        // Drop this browser's injectors, WITHOUT unclaiming the shared callback slot - that slot
        // belongs to BrowserInjectDispatcher on behalf of every registered injector, and removing
        // it here would tear down another feature's hook as a side effect of this teardown.
        //
        // An earlier version of this comment claimed nothing leaked because the dispatcher keys its
        // registry weakly. That was wrong: a WeakHashMap value strongly references whatever it
        // captures, and these injectors are lambdas closing over `this` - which holds the key. So
        // the entry pinned a whole BrowserHandleImpl per closed tab. unregister() is the fix.
        BrowserInjectDispatcher.unregister(browser)
        // The flags are latched SET rather than cleared: the entry has just been dropped, so a
        // re-registration here would put an injector back on a browser that is closing. isValid is
        // already false too, which is what stops setPageEventScript reaching this in the first place.
        coBrowseInjectRegistered.set(true)
        pageEventInjectRegistered.set(true)

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

        // Release find-in-page state and its timers before closing the browser: a debounce that
        // fires afterwards would search a closed object.
        BrowserFindController.dispose(browser)

        // Unconditional, unlike the composition's token-guarded removal: the handle is gone, so
        // there is no successor registration this could delete. Covers a handle disposed out from
        // under a surface that is still composed - an engine generation bump does exactly that.
        ActiveBrowserRegistry.unregister(id)

        // Close browser
        if (!browser.isClosed) {
            browser.close()
        }

        logger.debug(LogCategory.BROWSER, "Browser handle disposed", mapOf("handleId" to id))
    }

    companion object {
        /** Cause-chain depth [isTransportFailure] inspects before giving up. */
        private const val MAX_CAUSE_DEPTH = 16

        /** How much of a page-authored co-browse status string reaches the log. */
        private const val STATUS_LOG_LIMIT = 80

        /**
         * Popup browsers we are currently waiting to capture an upload body for.
         * Populated by the popup handler before [BeforeSendUploadDataCallback] fires;
         * the callback completes the deferred and removes the entry.
         */
        private val pendingPopupCaptures =
            ConcurrentHashMap<Browser, CompletableDeferred<PopupCapture?>>()

        /**
         * The engine the upload callback is installed on. Not a boolean: FluckEngine discards
         * and rebuilds its Engine to recover a wedged renderer, and a process-wide flag meant
         * the replacement never got the callback - so POST capture stayed silently dead for the
         * rest of the session and every popup paid the full grace period for nothing.
         */
        private val uploadCallbackEngine = AtomicReference<Engine?>(null)
        private val staticLogger = BossLogger.forComponent("BrowserHandleImpl")

        /**
         * Runs history retraction off the engine's event thread. Deliberately not tied to
         * any handle's lifetime — a retraction triggered by the navigation that closed a
         * tab still has to complete — and one per process rather than one per browser.
         */
        private val retractionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Runs the pop-out handoff. Not tied to any handle's scope: the work is started from a
         * composition that is in the middle of going away, which is exactly the scope that would
         * cancel it. One per process rather than one per browser, like [retractionScope].
         */
        private val autoPictureInPictureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Maps the engine's capture type onto the one [CaptureTracker] counts. */
        private fun capturedMediaOf(type: MediaStreamType): CapturedMedia? =
            when (type) {
                MediaStreamType.AUDIO -> CapturedMedia.AUDIO
                MediaStreamType.VIDEO -> CapturedMedia.VIDEO
                else -> null
            }

        /** Best-effort cap on [loadUrlAndWait]; returns (no throw) if a load runs long. */
        private const val LOAD_TIMEOUT_MS = 30_000L

        /**
         * How long a backgrounded tab waits before popping out. A tab dragged to another window
         * disposes one surface and composes the next in unspecified order, so a move is
         * momentarily indistinguishable from a background; this is long enough for the new
         * surface to arrive and short enough that a real tab switch feels immediate.
         */
        private const val AUTO_PIP_SETTLE_MS = 100L

        /** The surface pop-out's initial size; the resize grip takes it from there. */
        private const val SURFACE_POP_OUT_WIDTH = 480
        private const val SURFACE_POP_OUT_HEIGHT = 360

        /**
         * How long the disposed Swing view gets to release the rendering surface before the
         * tab is told to recreate its view state - FullscreenBrowserWindow's SWING_RELEASE_DELAY.
         */
        private const val SURFACE_RELEASE_DELAY_MS = 200

        /** How long dispose() waits for the EDT to close the pop-out before giving up on it. */
        private const val POP_OUT_EDT_TIMEOUT_MS = 2_000L

        /** Size of a floating popup window that asked for no geometry of its own. */
        private const val FLOATING_POPUP_WIDTH = 480

        private const val FLOATING_POPUP_HEIGHT = 320

        private const val FLOATING_POPUP_INSET = 40

        /** Height of the drag strip on an undecorated pop-out. */
        private const val POP_OUT_BAR_HEIGHT = 30

        private const val POP_OUT_BAR_FONT_SIZE = 12f

        private const val POP_OUT_CLOSE_FONT_SIZE = 15f

        /** Larger than the close glyph on purpose: the arrow carries far less ink at equal size. */
        private const val POP_OUT_ARROW_FONT_SIZE = 19f

        /** Edge of a square icon button in the pop-out's strip. */
        private const val POP_OUT_ICON_SIZE = 24

        /** Height of the resize grip along the bottom of an undecorated pop-out. */
        private const val POP_OUT_GRIP_HEIGHT = 10

        /**
         * How long an adopted popup waits for its main-frame navigation to name a destination
         * before falling back to the URL recorded at popup-creation time.
         *
         * This was briefly cut to 600ms, for a Document Picture-in-Picture window that never
         * navigates and so waited out the whole deadline before it could be shown. That flow is
         * gone - URL-less popups are dropped now, so nothing is waiting to be displayed - and
         * the short deadline only risked a cold render process missing `NavigationStarted`. Back
         * at three seconds, which is where it should stay unless something is again blocked on
         * this expiring.
         */
        private const val POPUP_URL_TIMEOUT_MS = 3_000L

        /**
         * Grace period for the popup's main-frame upload to arrive once its URL is known, so a
         * `target="_blank"` form POST keeps its body across the handoff. Only a MAIN_FRAME
         * request can claim it, so this window is no longer a chance for a page's analytics
         * beacon to be mistaken for the navigation.
         */
        private const val POPUP_UPLOAD_GRACE_MS = 500L

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
         * a MAIN_FRAME request originates from a browser registered in [pendingPopupCaptures] -
         * a subresource upload from the popup's own page (an XHR, or a `sendBeacon` ping) is not
         * that popup's navigation and must not be mistaken for it. The popup is closed before
         * its upload is sent, so the captured POST is replayed exactly once from the adopted new
         * tab. The popup coroutine removes the entry on every exit path, so a navigation that
         * carries no body at all leaves nothing behind.
         */
        private fun installUploadCallbackIfNeeded(engine: Engine) {
            if (uploadCallbackEngine.getAndSet(engine) === engine) return
            try {
                engine.network().set(
                    BeforeSendUploadDataCallback::class.java,
                    BeforeSendUploadDataCallback { params ->
                        try {
                            val req = params.urlRequest()
                            val popupBrowser = req.browser().orElse(null)
                            // MAIN_FRAME only. This callback is engine-wide and fires for every
                            // request carrying a body, so an XHR, a CSP report, or a sendBeacon
                            // ping (ResourceType.PING) from the popup's own page used to be able
                            // to claim the capture - and the first one to fire won, which is how
                            // a tab ended up on an analytics endpoint. Gating the claim also
                            // stops a beacon consuming the slot the real navigation needs. The
                            // entry is not orphaned when the navigation is a plain GET: the popup
                            // coroutine removes it on every exit path.
                            if (popupBrowser != null && req.resourceType() == ResourceType.MAIN_FRAME) {
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
                uploadCallbackEngine.compareAndSet(engine, null)
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
 * Whether a browser surface entering composition should take keyboard focus.
 *
 * All three clauses are load-bearing and each is a bug on its own:
 *
 *  - [mode] - see [needsExplicitFocusOnReshow]. Under OFF_SCREEN the widget owns focus and a
 *    host-side call fights it.
 *  - [alreadyShown] `false` means this tab is appearing for the first time, and a new browser tab
 *    is supposed to leave the caret in the URL bar. Focusing here would take it away on every new
 *    tab, which is a worse bug than the one being fixed.
 *  - [hostWindowFocused] - composition re-entry happens for reasons that are not a tab switch (a
 *    cross-window tab move, a workspace restore, a split rebuild), and without this a background
 *    window steals focus, or two side-by-side browser tabs race to claim it on one rebuild.
 *
 * Pure so the rule is pinned by a test: the decision lives inside a composable, where the effect
 * that consumes it cannot be reached from a unit test.
 */
internal fun shouldFocusOnShow(
    mode: com.teamdev.jxbrowser.engine.RenderingMode,
    alreadyShown: Boolean,
    hostWindowFocused: Boolean,
): Boolean = needsExplicitFocusOnReshow(mode) && alreadyShown && hostWindowFocused

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
