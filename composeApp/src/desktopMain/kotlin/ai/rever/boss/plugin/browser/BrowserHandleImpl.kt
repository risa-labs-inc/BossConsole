package ai.rever.boss.plugin.browser

import ai.rever.boss.tabfullscreen.FullscreenBrowserWindow
import java.util.concurrent.locks.ReentrantReadWriteLock
import ai.rever.boss.utils.MacOSGestureHandler
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.graphics.toComposeImageBitmap
import ai.rever.boss.cache.FaviconCache
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.CreatePopupCallback
import com.teamdev.jxbrowser.browser.callback.InjectJsCallback
import com.teamdev.jxbrowser.browser.callback.OpenPopupCallback
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.browser.event.BrowserClosed
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.js.JsObject
import com.teamdev.jxbrowser.browser.event.FaviconChanged
import com.teamdev.jxbrowser.browser.event.TitleChanged
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.event.Subscription
import com.teamdev.jxbrowser.navigation.LoadUrlParams
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.KeyModifiers
import com.teamdev.jxbrowser.ui.MouseButton
import com.teamdev.jxbrowser.ui.Point
import com.teamdev.jxbrowser.ui.ScrollType
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyReleased
import com.teamdev.jxbrowser.ui.event.KeyTyped
import com.teamdev.jxbrowser.ui.event.MouseDragged
import com.teamdev.jxbrowser.ui.event.MouseMoved
import com.teamdev.jxbrowser.ui.event.MousePressed
import com.teamdev.jxbrowser.ui.event.MouseReleased
import com.teamdev.jxbrowser.ui.event.MouseWheel
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import com.teamdev.jxbrowser.navigation.event.LoadStarted
import com.teamdev.jxbrowser.navigation.event.NavigationFinished
import com.teamdev.jxbrowser.navigation.event.NavigationStarted
import com.teamdev.jxbrowser.net.ByteData
import com.teamdev.jxbrowser.net.HttpHeader
import com.teamdev.jxbrowser.net.callback.BeforeSendUploadDataCallback
import com.teamdev.jxbrowser.ui.Rect
import com.teamdev.jxbrowser.zoom.ZoomLevel
import com.teamdev.jxbrowser.zoom.ZoomMode
import ai.rever.boss.plugin.window.LocalWindowId
import ai.rever.boss.utils.WindowFocusManager
import com.teamdev.jxbrowser.view.compose.BrowserView
import com.teamdev.jxbrowser.view.compose.BrowserViewState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Window
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val ownerWindowId: String
) : BrowserHandle {

    private val logger = BossLogger.forComponent("BrowserHandleImpl")

    override val id: String = UUID.randomUUID().toString()

    private val disposed = AtomicBoolean(false)
    private val subscriptions = mutableListOf<Subscription>()

    private val navigationListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val titleListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val faviconListeners = CopyOnWriteArrayList<(String?) -> Unit>()
    private val loadingListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val zoomListeners = CopyOnWriteArrayList<(Double) -> Unit>()

    // Track loading state
    private var _isLoading = false

    // Context menu callback
    private var contextMenuCallback: ContextMenuCallback? = null

    // Callback for opening links in new tabs (cmd+click, target="_blank", window.open)
    private var openInNewTabCallback: ((String) -> Unit)? = null

    // Callback variant that also carries POST body for form-submit popups.
    // When set, this wins over [openInNewTabCallback].
    private var openInNewTabWithDataCallback: ((PopupNavigation) -> Unit)? = null

    // BrowserViewState for Compose rendering - managed per Content() call
    private var currentViewState: BrowserViewState? = null

    // True while the pointer hovers this handle's rendered BrowserView. Gates the
    // window-wide macOS pinch listener so a pinch only zooms the browser under the
    // cursor, not one sitting in a background tab or another split.
    @Volatile private var pointerOverBrowserView = false

    // Runs a pinch-triggered zoom only when the pointer hovers this view and the
    // handle is alive; logs suppressions since the hover flag depends on Compose
    // Enter/Exit events reaching the view — a stuck flag would otherwise present
    // as pinch silently not working (or zooming a non-hovered view).
    private inline fun gatedPinchZoom(direction: String, zoom: () -> Unit) {
        if (pointerOverBrowserView && isValid) {
            zoom()
        } else {
            logger.debug(LogCategory.BROWSER, "Pinch zoom suppressed", mapOf(
                "direction" to direction,
                "hovered" to pointerOverBrowserView.toString(),
                "valid" to isValid.toString()
            ))
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

        // Load initial URL
        if (config.url.isNotBlank()) {
            val postData = config.initialPostData
            val contentType = config.initialPostContentType
            if (postData != null && contentType != null) {
                // Replay a form-submit popup as POST on first navigation.
                val params = LoadUrlParams.newBuilder(config.url)
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
        subscriptions += browser.navigation().on(NavigationStarted::class.java) { _ ->
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
        subscriptions += browser.navigation().on(NavigationFinished::class.java) { event ->
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

                // Skip injection for about:blank pages (used for dashboard display)
                // Only inject context menu trackers for actual web pages
                if (url.isNotEmpty() && url != "about:blank") {
                    // Inject context menu trackers (video click and link click tracking)
                    // These set window._rightClickedOnVideo and window._rightClickedLinkUrl
                    // which are read by setupContextMenuHandler when building the menu
                    injectContextMenuTrackers()
                }
            }
        }

        // Title changed
        subscriptions += browser.on(TitleChanged::class.java) { event ->
            val title = event.title()
            titleListeners.forEach { listener ->
                try {
                    listener(title)
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Title listener threw exception", error = e)
                }
            }
        }

        // Favicon changed - save to cache and notify listeners with cache key
        subscriptions += browser.on(FaviconChanged::class.java) { event ->
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
        subscriptions += browser.on(BrowserClosed::class.java) {
            logger.debug(LogCategory.BROWSER, "Browser closed", mapOf("handleId" to id))
            disposed.set(true)
            // Stop streaming: the underlying page is gone.
            coBrowseCapturing = false
            coBrowseSink = null
            coBrowseBridge.onEvent = null
        }
    }

    private fun setupBrowserHandlers() {
        // Setup download handler if enabled
        if (config.enableDownloads) {
            FluckEngine.setupBrowserDownloadHandler(browser)
        }

        // Setup keyboard interceptor for menu shortcuts
        FluckEngine.setupKeyboardInterceptor(browser, ownerWindowId)

        // Setup screen capture handler
        FluckEngine.setupCaptureSessionHandler(browser)

        // Setup context menu handler
        setupContextMenuHandler()
    }

    private fun setupContextMenuHandler() {
        browser.set(ShowContextMenuCallback::class.java, ShowContextMenuCallback { params, tell ->
            val callback = contextMenuCallback
            if (callback != null) {
                // Get page URL from params
                val pageUrl = try {
                    params.browser().url()
                } catch (e: Exception) {
                    logger.debug(LogCategory.BROWSER, "Could not read page URL for context menu - using empty", mapOf("error" to e.toString()))
                    ""
                }

                // Use JavaScript to get link URL, selected text, video status, and editable state
                // This is more reliable than the native PointInspection API
                var linkUrl: String? = null
                var selectedText: String? = null
                var hasVideo = false
                var isEditable = false
                var formFieldInfo: FormFieldInfo? = null

                try {
                    browser.mainFrame().ifPresent { frame ->
                        // Get link URL at click position
                        linkUrl = frame.executeJavaScript<String?>(BrowserJavaScripts.getRightClickedLinkUrl)

                        // Get selected text
                        val selection = frame.executeJavaScript<String?>(BrowserJavaScripts.getSelectedText)
                        selectedText = if (!selection.isNullOrBlank()) selection else null

                        // Check if right-clicked on a video element (not just if page has videos)
                        hasVideo = frame.executeJavaScript<Boolean>(BrowserJavaScripts.isClickedOnVideo) ?: false

                        // Check if focused element is editable
                        isEditable = frame.executeJavaScript<Boolean>("""
                            (function() {
                                var el = document.activeElement;
                                if (!el) return false;
                                var tag = el.tagName.toLowerCase();
                                if (tag === 'input' || tag === 'textarea') return true;
                                if (el.isContentEditable) return true;
                                return false;
                            })()
                        """.trimIndent()) ?: false

                        // If editable, get form field info for secret auto-fill
                        if (isEditable) {
                            formFieldInfo = getFormFieldInfoFromJS(frame)
                        }
                    }
                } catch (e: Exception) {
                    // JavaScript execution failed - proceed with defaults
                    logger.debug(LogCategory.BROWSER, "Context-menu JS inspection failed - proceeding with defaults", mapOf("error" to e.toString()))
                }

                val info = BrowserContextMenuInfo(
                    linkUrl = linkUrl,
                    selectedText = selectedText,
                    isEditable = isEditable,
                    hasVideo = hasVideo,
                    pageUrl = pageUrl,
                    pageTitle = browser.title(),
                    formFieldInfo = formFieldInfo
                )

                // Suppress JxBrowser's native context menu
                tell.close()

                // Invoke callback (runs on JxBrowser thread, caller should dispatch to main thread if needed)
                callback(info)
            }
            // If no custom callback, don't call tell.close() - default context menu will appear
        })
    }

    /**
     * Get form field info from JavaScript for secret auto-fill.
     */
    private fun getFormFieldInfoFromJS(frame: com.teamdev.jxbrowser.frame.Frame): FormFieldInfo? {
        return try {
            val jsonString = frame.executeJavaScript<String?>("""
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
            """.trimIndent())

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
            val fieldType = when {
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
                autocomplete = autocomplete
            )
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Failed to get form field info", mapOf("error" to e.message))
            null
        }
    }

    /**
     * Injects JavaScript trackers for context menu and navigation functionality.
     *
     * This injects event listeners for:
     * 1. Video click tracker - sets window._rightClickedOnVideo for PiP functionality
     * 2. Link click tracker - sets window._rightClickedLinkUrl for link context menu options
     * 3. Cmd+Click handler - intercepts Cmd+Click (Mac) / Ctrl+Click (Win/Linux) on links
     *    to open them in new tabs via window.open()
     *
     * These must be injected after navigation finishes so the context menu handler
     * can read the values when building the menu.
     */
    private fun injectContextMenuTrackers() {
        browser.mainFrame().ifPresent { frame ->
            try {
                // Inject video click tracker
                frame.executeJavaScript<Unit>(BrowserJavaScripts.injectVideoClickTracker)

                // Inject link click tracker
                frame.executeJavaScript<Unit>(BrowserJavaScripts.injectLinkClickTracker)

                // Inject Cmd+Click / Ctrl+Click handler for opening links in new tabs
                frame.executeJavaScript<Unit>(BrowserJavaScripts.injectCmdClickHandler)

                // Inject form field detection script for secret auto-fill
                FormFieldDetector.injectFormDetectionScript(createLockedBrowser())

                logger.debug(LogCategory.BROWSER, "Context menu and navigation trackers injected", mapOf("handleId" to id))
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to inject context menu trackers", error = e)
            }
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
            browser.set(InjectJsCallback::class.java, InjectJsCallback { params ->
                try {
                    if (coBrowseCapturing && params.frame().isMain) {
                        injectCoBrowseRecorder(params.frame())
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "InjectJsCallback failed", error = e)
                }
                InjectJsCallback.Response.proceed()
            })
        } catch (e: Exception) {
            coBrowseInjectRegistered = false
            logger.warn(LogCategory.BROWSER, "Failed to register InjectJsCallback", error = e)
        }
    }

    override fun startCoBrowseCapture(onEvent: (String) -> Unit, maskInputs: Boolean) {
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
        val o = try {
            kotlinx.serialization.json.Json.parseToJsonElement(inputJson).jsonObject
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Co-browse input unparsable", mapOf("handleId" to id), error = e)
            return
        }
        fun int(k: String, d: Int = 0) = o[k]?.jsonPrimitive?.intOrNull ?: d
        fun fl(k: String, d: Float = 0f) = o[k]?.jsonPrimitive?.floatOrNull ?: d
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
        fun bool(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
        val kind = str("kind")
        coBrowseScope.launch {
            try {
                val point = Point.of(int("x"), int("y"))
                when (kind) {
                    "down", "up" -> {
                        val button = when (int("button")) {
                            1 -> MouseButton.MIDDLE
                            2 -> MouseButton.SECONDARY
                            else -> MouseButton.PRIMARY
                        }
                        val clicks = int("clicks", 1)
                        if (kind == "down") {
                            browser.dispatch(MousePressed.newBuilder(point).button(button).clickCount(clicks).build())
                        } else {
                            browser.dispatch(MouseReleased.newBuilder(point).button(button).clickCount(clicks).build())
                        }
                    }
                    "move" -> browser.dispatch(MouseMoved.newBuilder(point).build())
                    "drag" -> browser.dispatch(MouseDragged.newBuilder(point).button(MouseButton.PRIMARY).build())
                    "wheel" -> browser.dispatch(
                        MouseWheel.newBuilder(point)
                            .deltaX(fl("dx"))
                            .deltaY(fl("dy"))
                            .scrollType(ScrollType.UNIT_SCROLL)
                            .build()
                    )
                    "keydown", "keyup" -> {
                        val keyCode = jsKeyToKeyCode(str("key"), str("code"))
                        val ch = str("ch").firstOrNull() ?: '\u0000'
                        val mods = KeyModifiers.newBuilder()
                            .shiftDown(bool("shift"))
                            .controlDown(bool("ctrl"))
                            .altDown(bool("alt"))
                            .metaDown(bool("meta"))
                            .build()
                        if (kind == "keydown") {
                            browser.dispatch(KeyPressed.newBuilder(keyCode).keyChar(ch).keyModifiers(mods).build())
                            // KeyTyped delivers the character to the focused field; only for
                            // printable input (modifier chords and control keys must not type).
                            if (ch != '\u0000' && !ch.isISOControl() && !bool("ctrl") && !bool("meta")) {
                                browser.dispatch(KeyTyped.newBuilder(keyCode).keyChar(ch).keyModifiers(mods).build())
                            }
                        } else {
                            browser.dispatch(KeyReleased.newBuilder(keyCode).keyModifiers(mods).build())
                        }
                    }
                    else -> logger.warn(LogCategory.BROWSER, "Co-browse input unknown kind", mapOf("handleId" to id, "kind" to kind))
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Co-browse input dispatch failed", mapOf("handleId" to id, "kind" to kind), error = e)
            }
        }
    }

    /** Map a JS KeyboardEvent key/code pair onto the engine's key codes. */
    private fun jsKeyToKeyCode(key: String, code: String): KeyCode = when {
        code.length == 4 && code.startsWith("Key") -> runCatching { KeyCode.valueOf("KEY_CODE_${code[3]}") }.getOrDefault(KeyCode.UNKNOWN)
        code.length == 6 && code.startsWith("Digit") -> runCatching { KeyCode.valueOf("KEY_CODE_${code[5]}") }.getOrDefault(KeyCode.UNKNOWN)
        else -> when (key) {
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

    override suspend fun applyCoBrowseControl(eventJson: String): String? {
        if (!isValid || !coBrowseControlGranted) {
            logger.warn(
                LogCategory.BROWSER, "Co-browse control refused by handle guard",
                mapOf("handleId" to id, "valid" to isValid.toString(), "granted" to coBrowseControlGranted.toString())
            )
            return null
        }
        return withContext(Dispatchers.Main) {
            try {
                val status = browser.mainFrame().map { frame ->
                    frame.executeJavaScript<String?>(CoBrowseScripts.applyControl(eventJson))
                }.orElse(null)
                if (status != "ok") {
                    // Non-ok statuses ("stale"/"denied"/"nomirror"/"err:…") are how
                    // control failures surface — keep them visible for live debugging.
                    logger.warn(
                        LogCategory.BROWSER, "Co-browse control not applied",
                        mapOf("handleId" to id, "status" to (status ?: "null"), "event" to eventJson.take(120))
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
        get() = !disposed.get() && !browser.isClosed &&
                FluckEngine.currentEngineGeneration == engineGeneration

    override suspend fun loadUrl(url: String) {
        if (!isValid) {
            logger.warn(LogCategory.BROWSER, "Cannot load URL - browser invalid", mapOf("handleId" to id))
            return
        }
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
            browser.navigation().goBack()
        }
    }

    override fun goForward() {
        if (isValid && browser.navigation().canGoForward()) {
            browser.navigation().goForward()
        }
    }

    override fun reload() {
        if (isValid) {
            browser.navigation().reload()
        }
    }

    override fun stop() {
        if (isValid) {
            browser.navigation().stop()
        }
    }

    override fun canGoBack(): Boolean {
        return isValid && browser.navigation().canGoBack()
    }

    override fun canGoForward(): Boolean {
        return isValid && browser.navigation().canGoForward()
    }

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

    override fun isLoading(): Boolean {
        return _isLoading
    }

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
        browser.set(CreatePopupCallback::class.java, CreatePopupCallback {
            CreatePopupCallback.Response.create()
        })

        // Phase 2: Handle popup display based on bounds
        // Based on the original BrowserFunctions.kt implementation
        browser.set(OpenPopupCallback::class.java, OpenPopupCallback { params ->
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
                    val u = try { popupBrowser.url() } catch (_: Exception) { "" }
                    if (u.isNotEmpty() && u != "about:blank") urlDeferred.complete(u)
                }

                if (targetUrl.isNotEmpty() && targetUrl != "about:blank") {
                    urlDeferred.complete(targetUrl)
                } else {
                    subscription = popupBrowser.navigation().on(LoadStarted::class.java) {
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
                            val nav = PopupNavigation(
                                url = finalUrl,
                                postData = capture?.body,
                                contentType = capture?.contentType
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
                                "hasPost" to (capture != null).toString()
                            )
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
                        val browserView = com.teamdev.jxbrowser.view.swing.BrowserView.newInstance(popupBrowser)
                        frame.contentPane.add(browserView)

                        // Update frame title when page title changes
                        subscriptions += popupBrowser.on(TitleChanged::class.java) { event ->
                            SwingUtilities.invokeLater {
                                frame.title = event.title()
                            }
                        }

                        // Close frame when browser closes
                        subscriptions += popupBrowser.on(BrowserClosed::class.java) {
                            SwingUtilities.invokeLater {
                                subscriptions.forEach { it.unsubscribe() }
                                frame.dispose()
                            }
                        }

                        // Close browser when frame closes
                        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
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
                        })

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
        })

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
        onExitFullscreen: () -> Unit
    ) {
        if (!isValid || tabId.isEmpty()) return

        FluckEngine.setupFullscreenHandler(
            browser = browser,
            tabId = tabId,
            onFullscreenEnter = {
                logger.info(LogCategory.BROWSER, "Tab entered fullscreen", mapOf("tabId" to tabId, "handleId" to id))
                onEnterFullscreen()
            },
            onFullscreenExit = {
                logger.info(LogCategory.BROWSER, "Tab exited fullscreen", mapOf("tabId" to tabId, "handleId" to id))
                onExitFullscreen()
            }
        )

        logger.debug(LogCategory.BROWSER, "Fullscreen handler configured", mapOf("tabId" to tabId, "handleId" to id))
    }

    override fun requestExitFullscreen() {
        FullscreenBrowserWindow.requestExit()
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

    override suspend fun fillCredentials(username: String, password: String, fillBoth: Boolean): Boolean {
        if (!isValid) return false
        return try {
            val mode = if (fillBoth) {
                FormFieldInjector.FillMode.BOTH
            } else {
                // Determine which field to fill based on focused field type
                val focusedType = browser.mainFrame().map { frame ->
                    frame.executeJavaScript<String?>("""
                        (function() {
                            var el = document.activeElement;
                            if (!el || el.tagName !== 'INPUT') return null;
                            return el.type || 'text';
                        })()
                    """.trimIndent())
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
                    val escapedText = clipboardText
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "")
                    frame.executeJavaScript<Unit>("""
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
                    """.trimIndent())
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
        var viewState by remember { mutableStateOf<BrowserViewState?>(null) }

        // Track last navigation time for debouncing mouse button navigation
        var lastNavigationTime by remember { mutableStateOf(0L) }

        // The window actually hosting this composition, resolved through the app's
        // window registry. In a multi-window setup the "first showing window"
        // fallback can resolve a different window than the one this view renders
        // in, which would bind the view state and the pinch gesture listener where
        // the browser isn't (gesture events are delivered per window). Keying the
        // effect on the id also rebinds both when a tab moves across windows.
        val hostWindowId = LocalWindowId.current

        DisposableEffect(browser, hostWindowId) {
            // Find a valid window to associate with the BrowserView
            val awtWindow = hostWindowId?.let { WindowFocusManager.getWindow(it) }
                ?: Window.getWindows().firstOrNull { window ->
                    try {
                        window.isDisplayable && window.isShowing
                    } catch (e: Exception) {
                        // Window can be mid-disposal - treat as not a candidate
                        logger.debug(LogCategory.BROWSER, "Window state probe failed - skipping window", mapOf("error" to e.toString()))
                        false
                    }
                }

            if (awtWindow != null) {
                try {
                    val newState = BrowserViewState(browser, MainScope(), awtWindow)
                    viewState = newState
                    currentViewState = newState
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
                        gestureToken = MacOSGestureHandler.addMagnificationListener(
                            rootPane,
                            onZoomIn = { gatedPinchZoom("in") { zoomIn() } },
                            onZoomOut = { gatedPinchZoom("out") { zoomOut() } }
                        )
                        if (gestureToken != null) {
                            gesturePane = rootPane
                            logger.debug(LogCategory.BROWSER, "Added macOS pinch-to-zoom gesture handler")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Failed to set up pinch-to-zoom gestures", error = e)
                }
            }

            onDispose {
                pointerOverBrowserView = false
                val pane = gesturePane
                val token = gestureToken
                if (pane != null && token != null) {
                    MacOSGestureHandler.removeMagnificationListener(pane, token)
                }
                viewState?.close()
                viewState = null
                currentViewState = null
            }
        }

        // Render the browser view if available with mouse button handling
        viewState?.let { state ->
            BrowserView(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    // Hover tracking that gates the window-wide pinch gesture
                    // listener to this view (see the DisposableEffect above)
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
                    }
            )
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return

        // Stop co-browse capture so a disposed tab can never keep streaming.
        coBrowseCapturing = false
        coBrowseControlGranted = false
        coBrowseSink = null
        coBrowseBridge.onEvent = null
        coBrowseScope.cancel()
        if (coBrowseInjectRegistered) {
            try { browser.remove(InjectJsCallback::class.java) } catch (_: Exception) {}
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
        val contentType: String
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

        /** Best-effort cap on [loadUrlAndWait]; returns (no throw) if a load runs long. */
        private const val LOAD_TIMEOUT_MS = 30_000L

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
                                    val contentType = params.httpHeaders()
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
                    }
                )
                staticLogger.debug(LogCategory.BROWSER, "BeforeSendUploadDataCallback installed")
            } catch (e: Exception) {
                uploadCallbackInstalled.set(false)
                staticLogger.warn(LogCategory.BROWSER, "Failed to install upload callback", error = e)
            }
        }
    }
}
