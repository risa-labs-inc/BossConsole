package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
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

    /**
     * Navigation schemes this service accepts. `javascript:` executes script in
     * the page context without any approval surface, and `data:` can smuggle
     * payloads - neither may be navigated to (#911). Everything outside this
     * set is refused before any state is touched.
     */
    private val allowedSchemes = setOf("http", "https", "file", "ftp", "about")

    /** Redact URI userinfo before logging so `user:pass@host` never hits the log file (#640 shape). */
    internal fun redactUrlForLog(url: String): String {
        val schemeEnd = url.indexOf("://")
        val authorityEnd =
            if (schemeEnd < 0) -1 else url.indexOf('/', schemeEnd + 3)
        val authority =
            if (schemeEnd < 0) "" else url.substring(schemeEnd + 3, if (authorityEnd < 0) url.length else authorityEnd)
        val atSign = authority.lastIndexOf('@')
        return when {
            schemeEnd < 0 || atSign <= 0 -> url
            else -> url.substring(0, schemeEnd + 3) + "***@" + url.substring(schemeEnd + 3 + atSign + 1)
        }
    }

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

        // Scheme gate FIRST (#911): `javascript:` executes script in the page
        // context with no approval surface; `data:` smuggles payloads. A
        // refused URL must not reach ANY log line - only its scheme does -
        // so the gate runs before the INFO log that would otherwise echo the
        // payload verbatim (the exact leak the issue reports).
        navigationRefusal(url, request.windowId)?.let { refusal ->
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage(refusal)
                .build()
        }

        logger.info("navigate: windowId={}, url={}", request.windowId, redactUrlForLog(url))

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

    /**
     * The (#911) navigation guard: returns the refusal message for blank or
     * unsupported-scheme URLs (`javascript:` executes script in the page
     * context with no approval surface; `data:` smuggles payloads), or null
     * when the URL may navigate.
     */
    private fun navigationRefusal(
        url: String,
        windowId: String,
    ): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        return when {
            url.isBlank() -> {
                "URL must not be blank"
            }

            scheme !in allowedSchemes -> {
                logger.warn("navigate: refused scheme={}, windowId={}", scheme.ifBlank { "none" }, windowId)
                "Refused navigation: unsupported URL scheme '$scheme'"
            }

            else -> {
                null
            }
        }
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
        logger.debug("getFavicon: url={}", redactUrlForLog(request.url))
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
        val state = windowStates.values.firstOrNull()
        if (state != null) {
            windowStates[state.windowId] = state.copy(isLoading = true)
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
            // The reload is bookkeeping-only in this service (the composeApp
            // engine owns the real reload), so complete the cycle here instead
            // of leaving the window stuck reporting `loading` forever (#911).
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
            windowStates[state.windowId] = state.copy(isLoading = false)
        }
        return Empty.getDefaultInstance()
    }
}
