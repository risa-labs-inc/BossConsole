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
@Suppress("TooManyFunctions")
class BrowserServiceImpl : BrowserServiceGrpcKt.BrowserServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(BrowserServiceImpl::class.java)

    private val uriUserinfoPattern = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/\s@]+@""")

    internal fun redactUrlUserInfo(text: String): String = text.replace(uriUserinfoPattern, "$1[REDACTED]@")

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

    private fun isProhibitedScheme(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("vbscript:")
    }

    private fun validateUrl(
        windowId: String,
        url: String,
        safeUrlForLogging: String,
    ): String? =
        when {
            url.isBlank() -> {
                "URL must not be blank"
            }

            isProhibitedScheme(url) -> {
                logger.warn(
                    "Refusing navigation to prohibited scheme: windowId={}, url={}",
                    windowId,
                    safeUrlForLogging,
                )
                "Prohibited URL scheme: javascript: and data: URLs are not allowed"
            }

            else -> {
                null
            }
        }

    private fun emitNavEvent(
        windowId: String,
        url: String,
        type: NavigationEventType,
        timestamp: Long,
    ) {
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(windowId)
                .setUrl(url)
                .setTitle(url)
                .setEventType(type)
                .setTimestamp(timestamp)
                .build(),
        )
    }

    override suspend fun navigate(request: NavigateBrowserRequest): NavigateBrowserResponse {
        val url = request.url.trim()
        val safeUrlForLogging = redactUrlUserInfo(url)
        logger.info("navigate: windowId={}, url={}", request.windowId, safeUrlForLogging)

        val error = validateUrl(request.windowId, url, safeUrlForLogging)
        if (error != null) {
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage(error)
                .build()
        }

        val prev = windowStates[request.windowId]
        val newState =
            PageState(
                windowId = request.windowId,
                url = url,
                title = url,
                canGoBack = prev != null,
                isLoading = false,
            )
        windowStates[request.windowId] = newState

        val ts = System.currentTimeMillis()
        emitNavEvent(request.windowId, url, NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED, ts)
        emitNavEvent(request.windowId, url, NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED, ts + 1)

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
        logger.debug("getFavicon: url={}", redactUrlUserInfo(request.url))
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
            windowStates[state.windowId] = state.copy(isLoading = false)
            val ts = System.currentTimeMillis()
            emitNavEvent(state.windowId, state.url, NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED, ts)
            emitNavEvent(state.windowId, state.url, NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED, ts + 1)
        }
        return Empty.getDefaultInstance()
    }
}
