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

        logger.info("navigate: windowId={}, url={}", request.windowId, LogSanitizer.describeUri(url))

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
        logger.debug("getFavicon: url={}", LogSanitizer.describeUri(request.url))
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
        // synchronous STARTED -> COMPLETED pair navigate has. The isLoading=false write
        // is an explicit invariant: nothing in this file sets it true, and the old
        // reload set it true with no completion ever flipping it back, which wedged
        // getPageInfo at "loading" forever (#911).
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
                // take(32): the scheme charset is validated, but its length is not,
                // and the stated contract of this log line is "never the URL" -
                // an attacker could otherwise put an arbitrarily long token there.
                "URL scheme '${scheme.take(SCHEME_LOG_MAX_LEN)}' is not navigable; $SCHEME_RULE"
            } else {
                authorityRefusal(engineForm(url), scheme.length + 1)
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
        val normalized = engineForm(url)
        val schemeEnd = normalized.indexOf(':')
        if (schemeEnd < 1) return null
        val scheme = normalized.substring(0, schemeEnd)
        return if (SCHEME_SYNTAX.matches(scheme)) scheme.lowercase() else null
    }

    private companion object {
        /**
         * The only schemes a Navigate may carry (#911). http/https, matching the repo's
         * existing URL policy for anything that opens in a browser tab (see
         * composeApp's UrlOpenValidation, whose KDoc names the same exceptions):
         * `javascript:` executes script in the page's own context, `data:` smuggles a
         * document the same way, `file:` reads local files into a window (and on
         * Windows `file://host/share` is an SMB fetch), and `ftp:` is dead - Chromium
         * removed it in 88, so the engine would refuse it anyway. An allowlist also
         * refuses every scheme nobody has classified yet.
         */
        val NAVIGABLE_SCHEMES = setOf("http", "https")

        /** RFC 3986 scheme: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ). */
        val SCHEME_SYNTAX = Regex("[a-zA-Z][a-zA-Z0-9+.-]*")

        /** The one clause every scheme refusal ends with, stating what Navigate does accept. */
        const val SCHEME_RULE = "Navigate accepts http and https URLs"

        /** Refusals log the scheme, not the URL; cap the echoed length regardless. */
        const val SCHEME_LOG_MAX_LEN = 32
    }
}

/**
 * [url] as an engine reads it: the WHATWG URL parser strips ASCII tab and newline anywhere in a
 * URL before doing anything else, so both gates below judge this form, not the raw string.
 */
private fun engineForm(url: String): String = url.replace("\t", "").replace("\n", "").replace("\r", "")

/** Printable characters an authority may not hold; control characters are refused separately. */
private val FORBIDDEN_IN_AUTHORITY = charArrayOf(' ', '\u00A0', '\\', '"', '<', '>')

/** The clause every authority refusal ends with. Like the scheme refusals, it never echoes the URL. */
private const val AUTHORITY_RULE = "Navigate accepts http(s)://host[:port] with no credentials in the authority"

/**
 * Why the authority of an http(s) [url] (in [engineForm]) is refused, or null when it is well
 * formed. [schemeLength] counts the scheme and its colon.
 *
 * The same rule composeApp's `UrlOpenValidation` applies to URLs the OS hands BOSS, so a
 * `Navigate` over IPC can no longer carry an authority the deep-link gate would refuse (#1591):
 * `//` must follow the scheme; the authority - up to the first `/`, `?` or `#` - must be
 * non-empty, free of spaces, backslashes, quotes, angle brackets and control characters, and
 * free of `@`; and it must hold a non-empty host with, optionally, a valid port. `@` is refused
 * rather than stripped: credentials in the authority are how a link disguises its destination
 * (`https://apple.com@evil.example`), and nothing Navigate does needs them.
 *
 * Read from the string, not `java.net.URI`, for the reason `UrlOpenValidation` gives: `URI`
 * rejects hosts browsers open fine, such as an underscore in a Docker service name.
 */
private fun authorityRefusal(
    url: String,
    schemeLength: Int,
): String? {
    if (!url.startsWith("//", schemeLength)) return "URL has no authority; $AUTHORITY_RULE"
    val start = schemeLength + 2
    val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
    val authority = url.substring(start, end)
    return when {
        authority.isEmpty() -> {
            "URL has no authority; $AUTHORITY_RULE"
        }

        authority.any { it in FORBIDDEN_IN_AUTHORITY || it.isISOControl() } -> {
            "URL authority contains a character a host cannot; $AUTHORITY_RULE"
        }

        '@' in authority -> {
            "URL carries credentials in its authority; $AUTHORITY_RULE"
        }

        hostOf(authority).isNullOrEmpty() -> {
            "URL authority has no valid host and port; $AUTHORITY_RULE"
        }

        else -> {
            null
        }
    }
}

/** The host of [authority], or null when its host or port is malformed. Bracketed IPv6 aware. */
private fun hostOf(authority: String): String? {
    // Split after the closing bracket for an IPv6 literal, else at the first colon: splitting on
    // the last colon would cut a bare IPv6 literal in half.
    val hostEnd =
        if (authority.startsWith('[')) {
            authority.indexOf(']').let { if (it < 0) -1 else it + 1 }
        } else {
            authority.indexOf(':').let { if (it < 0) authority.length else it }
        }
    val portSuffix = if (hostEnd < 0) "" else authority.substring(hostEnd)
    return when {
        hostEnd < 0 -> null
        portSuffix.isNotEmpty() && !isValidPortSuffix(portSuffix) -> null
        else -> authority.substring(0, hostEnd).removeSurrounding("[", "]")
    }
}

/**
 * `:` for the default port, or `:` and a port from 0 through 65535. ASCII digits only:
 * `Char.isDigit` accepts every Unicode decimal digit, which `UrlOpenValidation` found the hard way.
 */
private fun isValidPortSuffix(suffix: String): Boolean {
    if (!suffix.startsWith(':')) return false
    val port = suffix.drop(1).trimStart('0')
    return port.isEmpty() || (port.all { it in '0'..'9' } && port.toIntOrNull()?.let { it <= 65535 } == true)
}
