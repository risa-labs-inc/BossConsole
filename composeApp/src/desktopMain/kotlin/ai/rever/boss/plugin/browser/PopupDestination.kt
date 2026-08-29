package ai.rever.boss.plugin.browser

/**
 * A URL is usable as a tab destination only when it names a web page we can adopt into a tab.
 *
 * Restricted to http(s) deliberately, and this is load-bearing rather than tidiness. Before the
 * destination could come from the URL stated at popup-creation time, a page calling
 * `window.open("file:///Users/me/.ssh/id_rsa")` produced nothing usable - Chromium refuses the
 * top-level navigation, so the popup browser stayed on the empty document and the popup was
 * dropped. Taking the creation-time target as a fallback would hand that string straight to a
 * new tab, so the scheme has to be checked here. `blob:` and `data:` are excluded for a
 * duller reason: a blob URL is scoped to the document that minted it, so adopting one into a
 * fresh browser yields a tab that can never load.
 *
 * `about:` is not a destination in any form. The exact-string comparison this replaces also let
 * `about:blank#blocked` through, which is a real value Chromium produces for a blocked popup.
 *
 * This matches the http(s)-only guard on the injected cmd+click handler and the one the
 * fluck-browser plugin applies to middle-click, so all three paths agree on what may become a tab.
 */
internal fun usablePopupUrl(url: String?): String? {
    val trimmed = url?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return trimmed.takeIf { scheme == "http" || scheme == "https" }
}

/**
 * Decide where an adopted popup's new tab should go, and whether a POST body rides along.
 *
 * The navigation is the authority, with the URL Chromium reported at popup-creation time as the
 * fallback for a popup that never got as far as starting one.
 *
 * A capture may only ever contribute the *body*. It used to be able to override the destination,
 * which is how a tab ended up on `internal-analytics.odoo.com/api/event` and
 * `collector.github.com/github/collect`: the engine-wide upload callback claimed whichever POST
 * the popup's page fired first, and a page's own analytics beacon beats its main-frame request
 * whenever the navigation URL is slow to resolve. The body is attached only when the capture
 * describes the same request we are actually navigating to - a mismatched body would be stashed
 * under the wrong key and never consumed anyway, since `stashPopupPost`/`consumePopupPost` key on
 * the exact URL string.
 *
 * @return the tab to open, or null when no destination could be resolved - in which case the
 *   popup is dropped rather than sent somewhere arbitrary.
 */
internal fun popupDestination(
    navigationUrl: String?,
    createTargetUrl: String?,
    capture: PopupCapture?,
): PopupNavigation? {
    val url = usablePopupUrl(navigationUrl) ?: usablePopupUrl(createTargetUrl) ?: return null
    // Fragments are compared away because Chromium does not send one to the network: a form
    // posting to `/print#page2` reaches the upload callback as `/print`, and demanding an exact
    // match would silently downgrade that submission to a GET.
    val body = capture?.takeIf { it.url.substringBefore('#') == url.substringBefore('#') }
    return PopupNavigation(
        url = url,
        postData = body?.body,
        contentType = body?.contentType,
    )
}
