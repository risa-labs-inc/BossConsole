package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.logging.LogSanitizer
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of BrowserService.
 *
 * Tracks navigation state per window using an in-memory map and streams
 * events via a SharedFlow. JxBrowser runs in the composeApp process —
 * this service handles routing and state management for multi-window
 * browser coordination over IPC.
 */
class BrowserServiceImpl : BrowserServiceGrpcKt.BrowserServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(BrowserServiceImpl::class.java)

    /** Per-window page state snapshot. */
    private data class PageState(
        val windowId: String,
        val url: String,
        val title: String,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val isLoading: Boolean = false,
    )

    private val windowStates = ConcurrentHashMap<String, PageState>()
    private val navigationEvents = MutableSharedFlow<BrowserNavigationEvent>(extraBufferCapacity = 128)

    override suspend fun navigate(request: NavigateBrowserRequest): NavigateBrowserResponse {
        val url = request.url.trim()
        val refusal = navigateRefusal(url)
        if (refusal != null) {
            // Only the reason reaches the log, never the URL: a refused URL can carry the
            // very payload (javascript:…) or credential (https://user:password@host) that
            // this gate exists to keep out of the browser-service log (#911).
            logger.warn("navigate refused: windowId={}, reason={}", request.windowId, refusal)
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage(refusal)
                .build()
        }

        logger.info("navigate: windowId={}, url={}", request.windowId, LogSanitizer.redactUrlUserInfo(url))

        val prev = windowStates[request.windowId]
        val newState =
            PageState(
                windowId = request.windowId,
                url = url,
                title = url,
                canGoBack = prev != null,
            )
        windowStates[request.windowId] = newState

        val ts = System.currentTimeMillis()
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(request.windowId)
                .setUrl(url)
                .setTitle(url)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(ts)
                .build(),
        )
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(request.windowId)
                .setUrl(url)
                .setTitle(url)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED)
                .setTimestamp(ts + 1)
                .build(),
        )

        return NavigateBrowserResponse
            .newBuilder()
            .setSuccess(true)
            .setFinalUrl(url)
            .setTitle(url)
            .build()
    }

    override suspend fun executeJS(request: ExecuteJSRequest): ExecuteJSResponse {
        logger.debug("executeJS: windowId={}, scriptLen={}", request.windowId, request.script.length)
        // JS execution requires JxBrowser which runs in the composeApp process.
        return ExecuteJSResponse
            .newBuilder()
            .setSuccess(false)
            .setErrorMessage("JS execution requires JxBrowser (composeApp process)")
            .build()
    }

    override fun onNavigationEvent(request: Empty): Flow<BrowserNavigationEvent> =
        flow {
            navigationEvents.collect { event -> emit(event) }
        }

    override suspend fun getFavicon(request: GetFaviconRequest): GetFaviconResponse {
        logger.debug("getFavicon: url={}", LogSanitizer.redactUrlUserInfo(request.url))
        return GetFaviconResponse
            .newBuilder()
            .setFaviconBytes(ByteString.EMPTY)
            .setContentType("")
            .build()
    }

    override suspend fun getPageInfo(request: Empty): PageInfoResponse {
        if (windowStates.size > 1) {
            logger.warn(
                "getPageInfo called with {} windows tracked - returning first window only; use a window-specific RPC for multi-window support",
                windowStates.size,
            )
        }
        val state = windowStates.values.firstOrNull()
        return PageInfoResponse
            .newBuilder()
            .setUrl(state?.url ?: "")
            .setTitle(state?.title ?: "")
            .setCanGoBack(state?.canGoBack ?: false)
            .setCanGoForward(state?.canGoForward ?: false)
            .setIsLoading(state?.isLoading ?: false)
            .build()
    }

    override suspend fun goBack(request: Empty): Empty {
        logger.debug("goBack")
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(System.currentTimeMillis())
                .build(),
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun goForward(request: Empty): Empty {
        logger.debug("goForward")
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(System.currentTimeMillis())
                .build(),
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun reload(request: Empty): Empty {
        logger.debug("reload")
        val state = windowStates.values.firstOrNull() ?: return Empty.getDefaultInstance()
        // Nothing reports a load finishing back to this service, so reload keeps the same
        // synchronous STARTED -> COMPLETED pair navigate has. Leaving isLoading=true on the
        // slot instead wedged getPageInfo at "loading" forever (#911).
        windowStates[state.windowId] = state.copy(isLoading = false)
        val ts = System.currentTimeMillis()
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(state.windowId)
                .setUrl(state.url)
                .setTitle(state.title)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(ts)
                .build(),
        )
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(state.windowId)
                .setUrl(state.url)
                .setTitle(state.title)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED)
                .setTimestamp(ts + 1)
                .build(),
        )
        return Empty.getDefaultInstance()
    }

    /**
     * The reason [url] must not reach the engine, or null when it may.
     *
     * The scheme gate runs before any state is written, any event is emitted or the URL is
     * logged, so a refused scheme leaves the service exactly as it was — no COMPLETED
     * navigation event for a page that never loaded, and no script URL in the log (#911).
     */
    private fun navigateRefusal(url: String): String? =
        if (url.isBlank()) {
            "URL must not be blank"
        } else {
            val scheme = navigableScheme(url)
            if (scheme == null) {
                "URL has no parsable scheme; $SCHEME_RULE"
            } else if (scheme !in NAVIGABLE_SCHEMES) {
                "URL scheme '$scheme' is not navigable; $SCHEME_RULE"
            } else {
                null
            }
        }

    /**
     * The scheme of [url] as an engine reads it, or null when there is none.
     *
     * Tabs and newlines are stripped first, because stripping them is the first thing the
     * WHATWG URL parser does — `jav\tascript:alert(1)` reaches an engine as
     * `javascript:alert(1)` — so a gate reading the raw string could be walked with a single
     * control character. What remains must then be a legal scheme token: anything else is a
     * relative or malformed URL, which an engine resolves against the current page rather
     * than navigating.
     */
    private fun navigableScheme(url: String): String? {
        val normalized = url.replace("\t", "").replace("\n", "").replace("\r", "")
        val schemeEnd = normalized.indexOf(':')
        if (schemeEnd < 1) return null
        val scheme = normalized.substring(0, schemeEnd)
        return if (SCHEME_SYNTAX.matches(scheme)) scheme.lowercase() else null
    }

    private companion object {
        /**
         * The only schemes a Navigate may carry (#911). `javascript:` executes script in
         * the page's own context and `data:` smuggles a document the same way, so neither
         * may ride the no-approval Navigate path; an allowlist also refuses every scheme
         * nobody has classified yet.
         */
        val NAVIGABLE_SCHEMES = setOf("http", "https", "file", "ftp")

        /** RFC 3986 scheme: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ). */
        val SCHEME_SYNTAX = Regex("[a-zA-Z][a-zA-Z0-9+.-]*")

        /** The one clause every scheme refusal ends with, stating what Navigate does accept. */
        const val SCHEME_RULE = "Navigate accepts http, https, file and ftp URLs"
    }
}
