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

        if (url.isBlank()) {
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("URL must not be blank")
                .build()
        }

        val blockedScheme = dangerousUrlScheme(url)
        if (blockedScheme != null) {
            // Refused before it reaches windowStates/navigationEvents: no STARTED/COMPLETED pair,
            // no stored PageState, and the attacker-controlled payload after the scheme is never
            // logged - only the scheme name is.
            logger.warn("navigate: windowId={}, refused '{}:' URL", request.windowId, blockedScheme)
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("Refusing to navigate to a '$blockedScheme:' URL")
                .build()
        }

        logger.info("navigate: windowId={}, url={}", request.windowId, redactUrlUserInfo(url))

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
        logger.debug("getFavicon: url={}", request.url)
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
            // This stub has no real engine reporting completion, so nothing ever flipped
            // isLoading back - getPageInfo() reported "loading" forever after the first reload.
            // Mirrors navigate()'s own synchronous STARTED+COMPLETED pair immediately below.
            windowStates[state.windowId] = state.copy(isLoading = false)
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
        }
        return Empty.getDefaultInstance()
    }
}

/** Schemes that must never be navigated to: script/HTML execution disguised as a URL. */
private val DANGEROUS_URL_SCHEMES = setOf("javascript", "data")

/**
 * The URL's scheme, if it is one [navigate] must refuse - `null` for anything else (including a
 * URL with no scheme at all, which the blank/relative-URL handling deals with separately).
 */
internal fun dangerousUrlScheme(url: String): String? {
    val schemeEnd = url.indexOf(':')
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).trim().lowercase()
    return scheme.takeIf { it in DANGEROUS_URL_SCHEMES }
}

/**
 * Masks `user:pass@` userinfo in a URL before it reaches a log line - `admin:Secret123@` in
 * `https://admin:Secret123@internal-host/` becomes a fixed placeholder, the rest of the URL
 * unchanged.
 *
 * This module cannot depend on composeApp's `LogSanitizer` (a separate GraalVM-native microkernel
 * service with no dependency on the desktop app module), so this is a small, local equivalent
 * scoped to exactly what [navigate]'s log line needs.
 */
internal fun redactUrlUserInfo(url: String): String = url.replace(Regex("(://)[^/@\\s]+@"), "$1***@")
