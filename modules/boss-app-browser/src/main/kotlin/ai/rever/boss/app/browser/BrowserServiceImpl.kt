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

        val refusal = navigationRefusalReason(url)
        if (refusal != null) {
            // Refused before it reaches windowStates/navigationEvents: no STARTED/COMPLETED pair,
            // no stored PageState. The refusal message names only the scheme (or its absence) or
            // "blank", never the attacker-controlled payload that may follow it.
            logger.warn("navigate: windowId={}, {}", request.windowId, refusal)
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage(refusal)
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

/**
 * Schemes [navigate] will act on - an allowlist rather than a blocklist, so a scheme this set
 * has never heard of (`javascript`, `data`, `view-source`, a future exotic scheme) is refused by
 * default instead of requiring its own enumeration. A blocklist here was a demonstrable bypass:
 * `jav\tascript:` didn't match a literal `{javascript, data}` set-test, but a real URL parser
 * strips embedded tab/newline/CR before reading the scheme and still executes it as `javascript:`.
 */
private val ALLOWED_URL_SCHEMES = setOf("http", "https", "file", "ftp")

/**
 * `null` when [url] is safe for [BrowserServiceImpl.navigate] to act on; otherwise the exact
 * message to both log and return to the caller. Never echoes anything from [url] itself beyond
 * an already-validated scheme name, so a caller can log this string unconditionally.
 */
private fun navigationRefusalReason(url: String): String? {
    if (url.isBlank()) return "URL must not be blank"
    val scheme = extractUrlScheme(url)
    return when {
        scheme in ALLOWED_URL_SCHEMES -> null
        scheme == null -> "Refusing to navigate to a URL with no recognized scheme"
        else -> "Refusing to navigate to a '$scheme:' URL"
    }
}

/** RFC 3986's scheme grammar: `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`. */
private val SCHEME_GRAMMAR = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

/**
 * Extracts a URL's scheme the way a browser's own parser would, so the same smuggling tricks a
 * real engine already normalizes away can't be used to sneak a scheme past this gate. Returns
 * `null` when there is no syntactically valid scheme - the blank/relative-URL case included -
 * which [navigate] treats identically to "not on the allowlist".
 *
 * Mirrors the relevant WHATWG URL parser steps rather than inventing a bespoke normalization:
 * ASCII tab/newline/CR are removed from anywhere in the string (not just the ends) before
 * leading C0 control characters (0x00-0x1F) and space are trimmed - the same two steps that let
 * a real browser interpret `jav\tascript:`/`jav\nascript:` and a leading NUL byte before the
 * scheme as `javascript:` rather than as an unrecognized scheme. The scheme name itself is then
 * validated against [SCHEME_GRAMMAR] so garbage before a stray colon can't be mistaken for a
 * real scheme.
 */
internal fun extractUrlScheme(url: String): String? {
    val withoutTabsAndNewlines = url.filterNot { it == '\t' || it == '\n' || it == '\r' }
    val trimmed = withoutTabsAndNewlines.trimStart { it.code <= 0x1F || it == ' ' }

    val colon = trimmed.indexOf(':')
    if (colon <= 0) return null

    return trimmed.substring(0, colon).takeIf { SCHEME_GRAMMAR.matches(it) }?.lowercase()
}

/**
 * Masks URL userinfo before it reaches a log line - `admin:Secret123@` in
 * `https://admin:Secret123@internal-host/` becomes a fixed placeholder, the rest of the URL
 * unchanged.
 *
 * Splits on the LAST `@` within the authority component (the span between `://` and the first
 * of `/`, `?`, or `#`), matching the WHATWG URL standard's own userinfo/host boundary. Splitting
 * on the first `@` instead - as a naive regex would - leaves everything after that `@` unmasked,
 * so a password containing its own `@` (`admin:p@ss@w0rd@internal-host` -> `p@ss@w0rd` is the
 * credential, `internal-host` is the host) would leak its tail into the log.
 *
 * This module cannot depend on composeApp's `LogSanitizer` (a separate GraalVM-native microkernel
 * service with no dependency on the desktop app module), so this is a small, local equivalent
 * scoped to exactly what [navigate]'s and [BrowserServiceImpl.getFavicon]'s log lines need.
 */
internal fun redactUrlUserInfo(url: String): String {
    val schemeSep = url.indexOf("://")
    val authorityStart = schemeSep + 3
    var authorityEnd = url.length
    if (schemeSep >= 0) {
        for (i in authorityStart until url.length) {
            if (url[i] == '/' || url[i] == '?' || url[i] == '#') {
                authorityEnd = i
                break
            }
        }
    }
    val at = if (schemeSep < 0) -1 else url.substring(authorityStart, authorityEnd).lastIndexOf('@')

    return if (at < 0) {
        url
    } else {
        url.substring(0, authorityStart) + "***@" + url.substring(authorityStart + at + 1)
    }
}
